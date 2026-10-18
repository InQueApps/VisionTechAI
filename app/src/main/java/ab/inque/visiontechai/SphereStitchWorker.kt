package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage
import ab.inque.robotics.panorama.FrameAlignmentInfo


import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class SphereStitchResult(
    val accepted: Boolean,
    val coverage: Float,
    val solidAngleCoverage: Float,
    val frameInfo: FrameAlignmentInfo?,
    val previewBitmap: Bitmap?,
    val durationMs: Long,
    val ranOnMainThread: Boolean,
    val rejectedForOverlap: Boolean = false,
    val refined: Boolean = false,
    /** True when layer-3 intrinsic refine updated the panorama. */
    val intrinsicRefined: Boolean = false
)

/** Result of final panorama generation (refine + seam + full equirect). */
data class SphereFinishResult(
    val equirect: Bitmap?,
    val durationMs: Long,
    val ranOnMainThread: Boolean
)

/**
 * Runs [SphereStitchOperations.addFrameFast] off the UI thread with bounded back-pressure.
 *
 * Live 360 capture stack:
 *   CameraX → [VisionAnalysisPipeline] → [SphereCaptureController.captureFrame]
 *   → IMU quat ([OrientationTracker] + [SphereExtrinsicCalibration])
 *   → IMU quat → [SphereNative.addFrameFast] → native `processLivePreviewLocked`
 *   → live preview pose + cheap splat; background refine + full finish on DONE/SAVE.
 */
class SphereStitchWorker(
    private val operations: SphereStitchOperations = SphereNativeOperations,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val previewWidth: Int = PREVIEW_W,
    private val previewHeight: Int = PREVIEW_H,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val refineExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val intrinsicExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val preFinishExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val overlapGate: Float = DEFAULT_OVERLAP_GATE,
    private val refineDelayMs: Long = DEFAULT_REFINE_DELAY_MS,
    private val intrinsicDelayMs: Long = DEFAULT_INTRINSIC_DELAY_MS,
    private val periodicRefineEvery: Int = PERIODIC_REFINE_FRAMES,
    private val motionDepthEstimator: ((Bitmap) -> FloatArray?)? = null
) {
    private val inFlight = AtomicBoolean(false)
    private val pendingDrops = AtomicInteger(0)

    /** S37/S42: bumped on resume/finish to drop stale L2/L3 callbacks. */
    private val refinementGeneration = AtomicInteger(0)

    @Volatile
    private var queuedBitmap: Bitmap? = null

    @Volatile
    private var queuedQuaternion: FloatArray? = null

    @Volatile
    private var queuedPhotoMode: Boolean = false

    @Volatile
    private var queuedCameraEv: Float = 0f

    var onResult: ((SphereStitchResult) -> Unit)? = null

    /** Layer 3 finish/capture: full-res equirect after intrinsic pass (may arrive after DONE). */
    var onIntrinsicEquirectUpdated: ((Bitmap) -> Unit)? = null

    /** Fired on main thread after finish background global + intrinsic polish. */
    var onFinishBackgroundPolish: (() -> Unit)? = null

    private val refineRunnable = Runnable { runCaptureRefine(periodic = false) }
    /** When false, [schedulePreFinishDrain] is ignored (sweep-fast replay on constrained devices). */
    @Volatile
    var preFinishDrainEnabled: Boolean = true

    private val preFinishRunnable = Runnable { runPreFinishSlice() }

    @Volatile
    private var captureRefinementRan = false

    @Volatile
    private var preFinishProgress = SpherePoseRefinement.RefineProgress()

    /** Frame index when [preFinishProgress] was last advanced (staleness guard). */
    @Volatile
    private var preFinishFrameIndex = 0

    @Volatile
    private var lastPreviewBitmap: Bitmap? = null

    @Volatile
    private var lastPreviewAtMs = 0L

    /** Force background refine after this many accepted fast-path frames. */
    private var acceptedSinceRefine = 0

    @Volatile
    private var isShutdown = false

    @Volatile
    private var liveCaptureAborted = false

    private val pendingIdleCallbacks = mutableListOf<() -> Unit>()

    val isBusy: Boolean
        get() = inFlight.get() || synchronized(this) { queuedBitmap != null }

    /** Wait until no live stitch frame is in flight or queued (for tests / SAVE). */
    fun awaitStitchIdle(timeoutMs: Long = 5000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isBusy && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(5)
        }
        return !isBusy
    }

    /** Submit a task to an executor, ignoring rejection after shutdown. */
    private fun safeExecute(executor: ExecutorService, block: () -> Unit) {
        if (isShutdown) return
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor terminated between the check and submit; drop silently.
        }
    }

    val droppedWhileBusy: Int get() = pendingDrops.get()

    /** Cancel scheduled L2/L3 and invalidate in-flight refine callbacks (S37, S42). */
    fun invalidatePendingRefinement() {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        preFinishProgress = SpherePoseRefinement.RefineProgress()
        preFinishFrameIndex = 0
        refinementGeneration.incrementAndGet()
        // Do not clear pendingIdleCallbacks — queued export/finish blocks must run so
        // their finally blocks can deliver onComplete (generation token drops stale work).
    }

    /**
     * Stop live capture stitching: cancel refine/pre-finish, drop queued frames, and ignore
     * in-flight / queued stitch work. Used when the user taps DONE to view immediately.
     */
    fun abortLiveCapture() {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        preFinishProgress = SpherePoseRefinement.RefineProgress()
        preFinishFrameIndex = 0
        // Do not bump refinementGeneration — SAVE/export must keep its completion token.
        liveCaptureAborted = true
        synchronized(this) {
            queuedBitmap?.recycle()
            queuedBitmap = null
            queuedQuaternion = null
            queuedPhotoMode = false
            queuedCameraEv = 0f
        }
    }

    /** Re-enable live capture after [abortLiveCapture] (e.g. ADD MORE). */
    fun resumeLiveCapture() {
        liveCaptureAborted = false
    }

    @Volatile
    private var lastAngularVelocityDegPerSec = 0.0

    private fun isRefinementGenerationCurrent(generation: Int): Boolean =
        generation == refinementGeneration.get()

    fun submit(
        bitmap: Bitmap,
        quaternion: FloatArray,
        photoMode: Boolean = false,
        overlapGateOverride: Float? = null,
        angularVelocityDegPerSec: Double = 0.0,
        cameraEv: Float = 0f
    ) {
        if (liveCaptureAborted) {
            return
        }
        lastAngularVelocityDegPerSec = angularVelocityDegPerSec
        cancelScheduledCaptureRefine()
        val copy = copyToArgb8888(bitmap)
        if (!inFlight.compareAndSet(false, true)) {
            synchronized(this) {
                queuedBitmap?.recycle()
                queuedBitmap = copy
                queuedQuaternion = quaternion.copyOf()
                queuedPhotoMode = photoMode
                queuedCameraEv = cameraEv
            }
            pendingDrops.incrementAndGet()
            return
        }
        runStitch(copy, quaternion, photoMode, overlapGateOverride, cameraEv)
    }

    private fun runStitch(
        bitmap: Bitmap,
        quaternion: FloatArray,
        photoMode: Boolean = false,
        overlapGateOverride: Float? = null,
        cameraEv: Float = 0f
    ) {
        safeExecute(executor) {
            try {
                if (liveCaptureAborted) {
                    bitmap.recycle()
                    return@safeExecute
                }
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                val nearness = if (photoMode) null else motionDepthEstimator?.invoke(bitmap)
                val gate = overlapGateOverride ?: overlapGate
                val status = operations.addFrameFast(bitmap, quaternion, gate, nearness, cameraEv)
                val accepted = status == STITCH_ACCEPTED
                val lowOverlap = status == STITCH_LOW_OVERLAP
                val coverage = operations.getCoverage()
                val solidAngle = operations.getSolidAngleCoverage()
                val info = operations.getLastFrameInfo()
                val previewWithHoles = buildPreviewWithHoles(
                    photoMode = photoMode,
                    solidAngle = solidAngle,
                    forceRefresh = accepted
                )
                bitmap.recycle()
                if (previewWithHoles == null) {
                    android.util.Log.e(
                        "SphereStitchWorker",
                        "No preview bitmap after stitch (accepted=$accepted, native=${operations === SphereNativeOperations})"
                    )
                } else if (!accepted) {
                    android.util.Log.i(
                        "SphereStitchWorker",
                        "Frame not merged (lowOverlap=$lowOverlap); preview retained"
                    )
                }
                val duration = SystemClock.elapsedRealtime() - start
                SphereStitchTelemetry.recordFrame(duration, accepted)
                android.util.Log.i(
                    "SphereStitchWorker",
                    "Stitch ${if (photoMode) "photo" else "fast"} ${duration}ms accepted=$accepted"
                )
                val result = SphereStitchResult(
                    accepted = accepted,
                    coverage = coverage,
                    solidAngleCoverage = solidAngle,
                    frameInfo = info,
                    previewBitmap = previewWithHoles,
                    durationMs = duration,
                    ranOnMainThread = ranOnMain,
                    rejectedForOverlap = lowOverlap
                )
                if (liveCaptureAborted) {
                    return@safeExecute
                }
                mainHandler.post { onResult?.invoke(result) }
                if (accepted) {
                    acceptedSinceRefine++
                    preFinishProgress = SpherePoseRefinement.RefineProgress()
                    mainHandler.removeCallbacks(preFinishRunnable)
                    val frameIndex = info?.frameIndex ?: 0
                    if (frameIndex >= SphereRefinePolicy.captureRefineMinFrames()) {
                        scheduleCaptureRefineMotionAware()
                        schedulePreFinish(
                            SphereRefinePolicy.captureMotionRefineStableMs() + 50L
                        )
                    }
                }
            } catch (t: Throwable) {
                bitmap.recycle()
                android.util.Log.e("SphereStitchWorker", "Stitch failed", t)
            } finally {
                drainQueueOrRelease()
            }
        }
    }

    private fun captureRefineDelay(acceptedSinceRefine: Int): Long =
        refineDelayMs.coerceAtLeast(SphereRefinePolicy.captureRefineDelayMs())

    private fun cancelScheduledCaptureRefine() {
        mainHandler.removeCallbacks(refineRunnable)
    }

    private fun scheduleCaptureRefineMotionAware() {
        val vel = lastAngularVelocityDegPerSec
        val delay = if (vel < SphereRefinePolicy.captureMotionRefineMaxDegPerSec()) {
            SphereRefinePolicy.captureMotionRefineStableMs()
        } else {
            captureRefineDelay(acceptedSinceRefine)
        }
        scheduleCaptureRefine(delay)
    }

    /** (Re)arm debounced capture pose refine (rotation + translation graph). */
    private fun scheduleCaptureRefine(delayMs: Long = SphereRefinePolicy.captureRefineDelayMs()) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.postDelayed(refineRunnable, delayMs)
    }

    private fun schedulePreFinish(delayMs: Long) {
        mainHandler.removeCallbacks(preFinishRunnable)
        mainHandler.postDelayed(preFinishRunnable, delayMs)
    }

    /** After DONE, drain remaining pre-finish slices and refresh the preview (non-blocking). */
    fun schedulePreFinishDrain(delayMs: Long = 0L) {
        if (!preFinishDrainEnabled) return
        schedulePreFinish(delayMs)
    }

    private fun maybeContinuePreFinish() {
        schedulePreFinish(SphereRefinePolicy.preFinishInterSliceDelayMs())
    }

    private fun configureForCaptureRefine() {
        SphereProductionStitchPipeline.configureForCaptureRefine(operations)
    }

    private fun configureForPreFinish() {
        SphereProductionStitchPipeline.configureForPreFinish(operations)
    }

    private fun configureForFinish() {
        SphereProductionStitchPipeline.configureForTargetedFinalization(operations)
    }

    /**
     * Mid-capture: pose-only global solve + preview feather resplat.
     * Runs on [refineExecutor] so fast-path capture stays off the critical path.
     */
    private fun runCaptureRefine(periodic: Boolean = false) {
        if (isBusy) {
            if (!periodic) scheduleCaptureRefineMotionAware()
            return
        }
        val generation = refinementGeneration.get()
        safeExecute(refineExecutor) {
            if (!isRefinementGenerationCurrent(generation) || isBusy) return@safeExecute
            operations.flushPendingFrameStores()
            configureForCaptureRefine()
            val start = SystemClock.elapsedRealtime()
            val isCancelled = {
                !isRefinementGenerationCurrent(generation) || isBusy
            }
            val changed = if (periodic) {
                SphereCaptureRefinement.runPeriodic(
                    operations = operations,
                    isCancelled = isCancelled,
                    onPass = { label ->
                        android.util.Log.i("SphereStitchWorker", "Capture periodic $label")
                        if (label.startsWith("global")) {
                            logRefineStats("Capture periodic $label")
                        }
                    }
                )
            } else {
                SphereCaptureRefinement.run(
                    operations = operations,
                    isCancelled = isCancelled,
                    onPass = { label ->
                        android.util.Log.i("SphereStitchWorker", "Capture $label")
                        if (label.startsWith("global")) {
                            logRefineStats("Capture $label")
                        }
                    }
                )
            }
            if (!isRefinementGenerationCurrent(generation) || isBusy) return@safeExecute
            if (!changed) {
                if (!periodic) {
                    schedulePreFinish(SphereRefinePolicy.preFinishDelayMs())
                }
                return@safeExecute
            }
            acceptedSinceRefine = SphereRefinePolicy.acceptedCountAfterPeriodicRefine()
            captureRefinementRan = true
            deliverRefinePreview(start, intrinsicRefined = false, generation)
            if (!periodic) {
                schedulePreFinish(SphereRefinePolicy.preFinishDelayMs())
            }
        }
    }

    private fun maybeRunPeriodicCaptureRefine() {
        if (liveCaptureAborted || isBusy) return
        if (!SphereRefinePolicy.shouldRunPeriodicRefine(acceptedSinceRefine, periodicRefineEvery)) {
            return
        }
        runCaptureRefine(periodic = true)
    }

    /**
     * Idle pre-DONE: cache feature graph edges (no panorama rebuild).
     */
    private fun runPreFinishSlice() {
        if (!preFinishDrainEnabled) return
        val generation = refinementGeneration.get()
        safeExecute(preFinishExecutor) {
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            operations.flushPendingFrameStores()
            if (operations.getSolidAngleCoverage() < SphereRefinePolicy.preFinishMinSolidAngle()) {
                return@safeExecute
            }
            configureForPreFinish()
            val start = SystemClock.elapsedRealtime()
            if (SpherePreFinishRefinement.runGraphCacheSlice(operations)) {
                preFinishFrameIndex = operations.getLastFrameInfo()?.frameIndex ?: 0
                android.util.Log.i(
                    "SphereStitchWorker",
                    "Pre-finish graph cache in ${SystemClock.elapsedRealtime() - start}ms " +
                        "(frame=$preFinishFrameIndex)"
                )
            }
        }
    }

    private fun deliverRefinePreview(
        startMs: Long,
        intrinsicRefined: Boolean,
        generation: Int = refinementGeneration.get()
    ) {
        if (!isRefinementGenerationCurrent(generation)) return
        val coverage = operations.getCoverage()
        val solidAngle = operations.getSolidAngleCoverage()
        val info = operations.getLastFrameInfo()
        val previewWithHoles = buildPreviewWithHoles(photoMode = true, solidAngle = solidAngle)
        val result = SphereStitchResult(
            accepted = false,
            coverage = coverage,
            solidAngleCoverage = solidAngle,
            frameInfo = info,
            previewBitmap = previewWithHoles,
            durationMs = SystemClock.elapsedRealtime() - startMs,
            ranOnMainThread = false,
            refined = true,
            intrinsicRefined = intrinsicRefined
        )
        mainHandler.post { onResult?.invoke(result) }
    }

    private fun drainQueueOrRelease() {
        if (liveCaptureAborted) {
            synchronized(this) {
                queuedBitmap?.recycle()
                queuedBitmap = null
                queuedQuaternion = null
                queuedPhotoMode = false
                queuedCameraEv = 0f
            }
            inFlight.set(false)
            dispatchStitchIdleCallbacks()
            return
        }
        val nextBitmap: Bitmap?
        val nextQuat: FloatArray?
        val nextPhotoMode: Boolean
        val nextCameraEv: Float
        synchronized(this) {
            nextBitmap = queuedBitmap
            nextQuat = queuedQuaternion
            nextPhotoMode = queuedPhotoMode
            nextCameraEv = queuedCameraEv
            queuedBitmap = null
            queuedQuaternion = null
            queuedPhotoMode = false
            queuedCameraEv = 0f
        }
        if (nextBitmap != null && nextQuat != null) {
            runStitch(nextBitmap, nextQuat, nextPhotoMode, cameraEv = nextCameraEv)
        } else {
            inFlight.set(false)
            dispatchStitchIdleCallbacks()
            maybeRunPeriodicCaptureRefine()
        }
    }

    private fun dispatchStitchIdleCallbacks() {
        val callbacks = synchronized(pendingIdleCallbacks) {
            if (pendingIdleCallbacks.isEmpty()) return
            pendingIdleCallbacks.toList().also { pendingIdleCallbacks.clear() }
        }
        callbacks.forEach { it() }
    }

    /**
     * Runs [block] on the stitch executor once no frame is in flight and the
     * one-slot queue is empty.
     */
    fun runWhenStitchIdle(block: () -> Unit) {
        val idleNow = !inFlight.get() && synchronized(this) { queuedBitmap == null }
        if (idleNow) {
            safeExecute(executor, block)
            return
        }
        synchronized(pendingIdleCallbacks) {
            pendingIdleCallbacks.add { safeExecute(executor, block) }
        }
    }

    /**
     * DONE viewer: one fast orientation solve + preview rebuild (capture resolution).
     */
    fun runPreviewRefine(
        eqW: Int,
        eqH: Int,
        onProgress: ((Int, String) -> Unit)? = null,
        onStageViewerRefresh: (() -> Unit)? = null,
        onComplete: (SphereFinishResult) -> Unit
    ) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                var equirect: Bitmap? = null
                try {
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    val frameNow = operations.getLastFrameInfo()?.frameIndex ?: 0
                    val startProgress = if (preFinishFrameIndex == frameNow) {
                        preFinishProgress
                    } else {
                        SpherePoseRefinement.RefineProgress()
                    }
                    val previewConfig = SphereRefinePolicy.donePreviewRefinementPasses()
                    val totalSlices = SpherePoseRefinement.RefineProgress()
                        .totalSlicesFor(previewConfig).coerceAtLeast(1)
                    var slicesDone = startProgress.slicesDoneFor(previewConfig)
                    SphereProductionStitchPipeline.runDonePreviewRefine(
                        operations = operations,
                        startProgress = startProgress,
                        isCancelled = { !isRefinementGenerationCurrent(finishGeneration) },
                        onPass = { label ->
                            android.util.Log.i("SphereStitchWorker", "Preview $label")
                            if (label.contains("global")) {
                                logRefineStats("Preview $label")
                            }
                            slicesDone = (slicesDone + 1).coerceAtMost(totalSlices)
                            val percent = ((slicesDone * 85) / totalSlices).coerceIn(10, 85)
                            onProgress?.invoke(percent, label)
                            postStageViewerRefresh(onStageViewerRefresh)
                        }
                    )
                    captureRefinementRan = false
                    preFinishProgress = SpherePoseRefinement.RefineProgress()
                    preFinishFrameIndex = 0
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    equirect = operations.getEquirectBitmap(eqW, eqH)
                    postStageViewerRefresh(onStageViewerRefresh)
                    onProgress?.invoke(100, "done")
                } catch (t: Throwable) {
                    android.util.Log.e("SphereStitchWorker", "Preview refine failed", t)
                } finally {
                    val result = SphereFinishResult(
                        equirect = retainFinishEquirect(equirect),
                        durationMs = SystemClock.elapsedRealtime() - start,
                        ranOnMainThread = ranOnMain
                    )
                    mainHandler.post { onComplete(result) }
                }
            }
        }
    }

    /**
     * Post-DONE: 2048 feather preview then input-matched export finalization.
     */
    fun runPostDoneFinalization(
        previewW: Int,
        previewH: Int,
        exportW: Int,
        exportH: Int,
        onProgress: ((Int, String) -> Unit)? = null,
        onStageViewerRefresh: (() -> Unit)? = null,
        onIntermediatePreview: ((Bitmap) -> Unit)? = null,
        onComplete: (SphereFinishResult) -> Unit
    ) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                var equirect: Bitmap? = null
                var completed = false
                try {
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    val frameNow = operations.getLastFrameInfo()?.frameIndex ?: 0
                    val startProgress = if (preFinishFrameIndex == frameNow) {
                        preFinishProgress
                    } else {
                        SpherePoseRefinement.RefineProgress()
                    }
                    val previewConfig = SphereRefinePolicy.donePreviewRefinementPasses()
                    val previewSlices = SpherePoseRefinement.RefineProgress()
                        .totalSlicesFor(previewConfig).coerceAtLeast(1)
                    var slicesDone = startProgress.slicesDoneFor(previewConfig)
                    SphereProductionStitchPipeline.runDonePreviewRefine(
                        operations = operations,
                        startProgress = startProgress,
                        isCancelled = { !isRefinementGenerationCurrent(finishGeneration) },
                        onPass = { label ->
                            android.util.Log.i("SphereStitchWorker", "Finalizing $label")
                            if (label.contains("global")) {
                                logRefineStats("Finalizing $label")
                            }
                            slicesDone = (slicesDone + 1).coerceAtMost(previewSlices)
                            val percent = ((slicesDone * 30) / previewSlices).coerceIn(5, 30)
                            onProgress?.invoke(percent, label)
                            postStageViewerRefresh(onStageViewerRefresh)
                        }
                    )
                    captureRefinementRan = false
                    preFinishProgress = SpherePoseRefinement.RefineProgress()
                    preFinishFrameIndex = 0
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    val intermediate = retainFinishEquirect(
                        operations.getEquirectBitmap(previewW, previewH)
                    )
                    if (intermediate != null) {
                        onProgress?.invoke(35, "previewResplat")
                        mainHandler.post { onIntermediatePreview?.invoke(intermediate) }
                    }
                    val finalizeSlices = SphereRefinePolicy.targetedFinalizationPassCount().coerceAtLeast(1)
                    var finalizeDone = 0
                    SphereProductionStitchPipeline.runPostDoneFinalization(
                        operations = operations,
                        exportW = exportW,
                        exportH = exportH,
                        isCancelled = { !isRefinementGenerationCurrent(finishGeneration) },
                        onPass = { label ->
                            android.util.Log.i("SphereStitchWorker", "Finalizing $label")
                            if (label.contains("global") || label.startsWith("converge")) {
                                logRefineStats("Finalizing $label")
                            }
                            if (!label.startsWith("prepare") && !label.startsWith("seams") &&
                                !label.startsWith("finalResplat")
                            ) {
                                finalizeDone = (finalizeDone + 1).coerceAtMost(finalizeSlices)
                            }
                            val percent = 35 + ((finalizeDone * 55) / finalizeSlices).coerceIn(0, 55)
                            onProgress?.invoke(percent.coerceIn(35, 90), label)
                            postStageViewerRefresh(onStageViewerRefresh)
                        }
                    )
                    onProgress?.invoke(95, "Encoding…")
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    equirect = operations.getEquirectBitmap(exportW, exportH)
                    postStageViewerRefresh(onStageViewerRefresh)
                    completed = true
                } catch (t: Throwable) {
                    android.util.Log.e("SphereStitchWorker", "Post-DONE finalization failed", t)
                } finally {
                    val result = SphereFinishResult(
                        equirect = retainFinishEquirect(equirect),
                        durationMs = SystemClock.elapsedRealtime() - start,
                        ranOnMainThread = ranOnMain
                    )
                    mainHandler.post { onComplete(result) }
                    if (!completed) {
                        android.util.Log.w(
                            "SphereStitchWorker",
                            "Post-DONE finalization ended without equirect (cancelled or error)"
                        )
                    }
                }
            }
        }
    }

    /**
     * SAVE gallery: full polish at export resolution, off the UI thread.
     */
    fun runFinishGeneration(
        eqW: Int,
        eqH: Int,
        onProgress: ((Int, String) -> Unit)? = null,
        onStageViewerRefresh: (() -> Unit)? = null,
        onComplete: (SphereFinishResult) -> Unit
    ) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                var equirect: Bitmap? = null
                var completed = false
                try {
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    val totalSlices = SphereRefinePolicy.targetedFinalizationPassCount().coerceAtLeast(1)
                    var slicesDone = 0
                    SphereProductionStitchPipeline.runSavePolish(
                        operations = operations,
                        exportW = eqW,
                        exportH = eqH,
                        isCancelled = { !isRefinementGenerationCurrent(finishGeneration) },
                        onPass = { label ->
                            android.util.Log.i("SphereStitchWorker", "Save $label")
                            if (label.contains("global") || label.startsWith("converge")) {
                                logRefineStats("Save $label")
                            }
                            if (!label.startsWith("prepare") && !label.startsWith("seams")) {
                                slicesDone = (slicesDone + 1).coerceAtMost(totalSlices)
                            }
                            val percent = ((slicesDone * 80) / totalSlices).coerceIn(5, 80)
                            onProgress?.invoke(percent, label)
                            postStageViewerRefresh(onStageViewerRefresh)
                        }
                    )
                    onProgress?.invoke(90, "Encoding…")
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    equirect = operations.getEquirectBitmap(eqW, eqH)
                    postStageViewerRefresh(onStageViewerRefresh)
                    completed = true
                    // L2b global in background; light seams only (no viewer replace).
                    safeExecute(intrinsicExecutor) {
                        if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                        val iStart = SystemClock.elapsedRealtime()
                        if (SphereRefinePolicy.runBackgroundGlobalPose()) {
                            operations.refineGlobalPose()
                            logRefineStats("L2 finish background")
                            if (SphereRefinePolicy.runExportSeamRefine()) {
                                operations.refineSeams(light = true)
                            }
                        }
                        if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                        android.util.Log.i(
                            "SphereStitchWorker",
                            "Finish background polish (light seams) done in " +
                                "${SystemClock.elapsedRealtime() - iStart}ms"
                        )
                        postStageViewerRefresh(onStageViewerRefresh)
                        mainHandler.post { onFinishBackgroundPolish?.invoke() }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("SphereStitchWorker", "Finish export failed", t)
                } finally {
                    val ownedEquirect = retainFinishEquirect(equirect)
                    val result = SphereFinishResult(
                        equirect = ownedEquirect,
                        durationMs = SystemClock.elapsedRealtime() - start,
                        ranOnMainThread = ranOnMain
                    )
                    mainHandler.post { onComplete(result) }
                    if (!completed) {
                        android.util.Log.w(
                            "SphereStitchWorker",
                            "Finish export ended without equirect (cancelled or error)"
                        )
                    }
                }
            }
        }
    }

    private fun postStageViewerRefresh(onStageViewerRefresh: (() -> Unit)?) {
        if (onStageViewerRefresh == null) return
        mainHandler.post(onStageViewerRefresh)
    }

    private fun retainFinishEquirect(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        val copy = copyToArgb8888(bitmap)
        if (copy !== bitmap) bitmap.recycle()
        return copy
    }

    /**
     * Light DONE export for IMU sweep-fast: capture refine + global/converge finish on
     * degraded IMU; no background polish or long pre-finish drain.
     * [onComplete] is invoked on the main thread.
     */
    fun runSweepFastExport(
        eqW: Int,
        eqH: Int,
        imuDegradeDeg: Float,
        onComplete: (SphereFinishResult) -> Unit
    ) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                var equirect: Bitmap? = null
                try {
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    SphereProductionStitchPipeline.runSweepFastFinishExport(
                        operations = operations,
                        exportW = eqW,
                        exportH = eqH,
                        imuDegradeDeg = imuDegradeDeg
                    )
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    equirect = operations.getEquirectBitmap(eqW, eqH)
                } catch (t: Throwable) {
                    android.util.Log.e("SphereStitchWorker", "Sweep-fast export failed", t)
                } finally {
                    val result = SphereFinishResult(
                        equirect = equirect,
                        durationMs = SystemClock.elapsedRealtime() - start,
                        ranOnMainThread = ranOnMain
                    )
                    mainHandler.post { onComplete(result) }
                }
            }
        }
    }

    /** @deprecated Use [runWhenStitchIdle] or [runFinishGeneration]. */
    fun runWhenIdle(onComplete: () -> Unit) {
        runWhenStitchIdle { mainHandler.post(onComplete) }
    }

    fun detachCallbacks() {
        onResult = null
        onIntrinsicEquirectUpdated = null
        onFinishBackgroundPolish = null
    }

    fun shutdown() {
        isShutdown = true
        detachCallbacks()
        abortLiveCapture()
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        synchronized(pendingIdleCallbacks) { pendingIdleCallbacks.clear() }
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = null
        lastPreviewAtMs = 0L
        synchronized(this) {
            queuedBitmap?.recycle()
            queuedBitmap = null
            queuedQuaternion = null
            queuedPhotoMode = false
            queuedCameraEv = 0f
        }
        executor.shutdown()
        refineExecutor.shutdown()
        intrinsicExecutor.shutdown()
        preFinishExecutor.shutdown()
    }

    fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        executor.awaitTermination(timeout, unit) &&
            refineExecutor.awaitTermination(timeout, unit) &&
            intrinsicExecutor.awaitTermination(timeout, unit) &&
            preFinishExecutor.awaitTermination(timeout, unit)

    private fun buildPreviewWithHoles(
        photoMode: Boolean,
        solidAngle: Float,
        forceRefresh: Boolean = false
    ): Bitmap? {
        val now = SystemClock.elapsedRealtime()
        val throttleMs = SphereRefinePolicy.previewThrottleMs()
        if (!forceRefresh && !photoMode && now - lastPreviewAtMs < throttleMs) {
            return lastPreviewBitmap
        }
        val preview = operations.getEquirectBitmap(previewWidth, previewHeight) ?: return null
        val holeMask = if (solidAngle < 0.92f) {
            operations.getHoleMaskBitmap(previewWidth, previewHeight)
        } else {
            null
        }
        val composited = SphereHoleOverlay.compositePreview(preview, holeMask) ?: preview
        lastPreviewAtMs = now
        if (composited !== lastPreviewBitmap) {
            lastPreviewBitmap?.recycle()
            lastPreviewBitmap = composited
        }
        return composited
    }

    private fun logRefineStats(label: String) {
        val stats = operations.getLastRefineStats() ?: return
        if (!stats.ran) return
        android.util.Log.i(
            "SphereStitchWorker",
            "$label refine global=${stats.applyGlobalPose} " +
                "SSD ${stats.ssdBefore}->${stats.ssdAfter} " +
                "realigned=${stats.framesRealigned}"
        )
    }

    companion object {
        const val PREVIEW_W = 1024
        const val PREVIEW_H = 512

        /** Minimum edge-overlap fraction required to merge a frame. */
        const val DEFAULT_OVERLAP_GATE = 0.10f

        /**
         * Idle delay before background refinement kicks in. Refine only runs
         * once frames stop arriving for this long, keeping it off the capture
         * critical path entirely.
         */
        const val DEFAULT_REFINE_DELAY_MS = 600L

        /** Delay after layer-2 global refine before layer-3 intrinsic pass. */
        const val DEFAULT_INTRINSIC_DELAY_MS = 150L

        /** Refine at least every N accepted frames during continuous capture. */
        const val PERIODIC_REFINE_FRAMES = 5

        /** Full visual re-align passes on DONE (matches offline replay). */
        const val FINISH_ALIGN_PASSES = 3

        internal fun copyToArgb8888(bitmap: Bitmap): Bitmap {
            if (bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.isRecycled) {
                return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
            return out
        }
    }
}
