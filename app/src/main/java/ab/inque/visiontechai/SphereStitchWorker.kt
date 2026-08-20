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
 * Runs [SphereStitchOperations.addFrame] off the UI thread with bounded back-pressure.
 * Queues at most one pending frame while busy (replaces older queued frame).
 * Never recycles the caller's [Bitmap].
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
    private val motionDepthEstimator: ((Bitmap) -> FloatArray)? = null
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

    var onResult: ((SphereStitchResult) -> Unit)? = null

    /** Layer 3 finish/capture: full-res equirect after intrinsic pass (may arrive after DONE). */
    var onIntrinsicEquirectUpdated: ((Bitmap) -> Unit)? = null

    /** Fired on main thread after finish background global + intrinsic polish. */
    var onFinishBackgroundPolish: (() -> Unit)? = null

    private val refineRunnable = Runnable {
        if (inFlight.get() || synchronized(this) { queuedBitmap != null }) {
            scheduleCaptureRefine(SphereRefinePolicy.captureRefineDelayMs())
            return@Runnable
        }
        runCaptureRefine()
    }
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

    @Volatile
    private var intrinsicRunnable: Runnable? = null

    /** Force background refine after this many accepted fast-path frames. */
    private var acceptedSinceRefine = 0

    @Volatile
    private var isShutdown = false

    private val pendingIdleCallbacks = mutableListOf<() -> Unit>()

    val isBusy: Boolean get() = inFlight.get()

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
        cancelIntrinsicRefine()
        preFinishProgress = SpherePoseRefinement.RefineProgress()
        preFinishFrameIndex = 0
        refinementGeneration.incrementAndGet()
    }

    private fun isRefinementGenerationCurrent(generation: Int): Boolean =
        generation == refinementGeneration.get()

    fun submit(
        bitmap: Bitmap,
        quaternion: FloatArray,
        photoMode: Boolean = false,
        overlapGateOverride: Float? = null
    ) {
        val copy = copyToArgb8888(bitmap)
        if (!inFlight.compareAndSet(false, true)) {
            synchronized(this) {
                queuedBitmap?.recycle()
                queuedBitmap = copy
                queuedQuaternion = quaternion.copyOf()
                queuedPhotoMode = photoMode
            }
            pendingDrops.incrementAndGet()
            return
        }
        runStitch(copy, quaternion, photoMode, overlapGateOverride)
    }

    private fun runStitch(
        bitmap: Bitmap,
        quaternion: FloatArray,
        photoMode: Boolean = false,
        overlapGateOverride: Float? = null
    ) {
        safeExecute(executor) {
            val start = SystemClock.elapsedRealtime()
            val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
            val nearness = motionDepthEstimator?.invoke(bitmap)
            val gate = overlapGateOverride ?: overlapGate
            val status = operations.addFrameFast(bitmap, quaternion, gate, nearness)
            val accepted = status == STITCH_ACCEPTED
            val lowOverlap = status == STITCH_LOW_OVERLAP
            val coverage = operations.getCoverage()
            val solidAngle = operations.getSolidAngleCoverage()
            val info = operations.getLastFrameInfo()
            val previewWithHoles = buildPreviewWithHoles(
                photoMode = photoMode,
                solidAngle = solidAngle
            )
            bitmap.recycle()
            if (previewWithHoles == null) {
                android.util.Log.e(
                    "SphereStitchWorker",
                    "No preview bitmap after stitch (accepted=$accepted, native=${operations === SphereNativeOperations})"
                )
            } else if (!accepted) {
                android.util.Log.i("SphereStitchWorker", "Frame not merged (lowOverlap=$lowOverlap); preview retained")
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
            // Settle in-flight state (and relaunch any queued frame) BEFORE
            // delivering the result, so an observer that submits a new frame in
            // its result callback isn't dropped by a stale in-flight flag.
            //
            // Refinement is scheduled ONLY on the idle debounce — never mid-burst.
            // While the user is actively capturing (frames arriving faster than
            // refineDelayMs), the debounce keeps resetting and the expensive
            // full re-align/rebuild is deferred until they pause. This is what
            // keeps per-tap latency low (measured: removes multi-second stalls).
            if (accepted) {
                acceptedSinceRefine++
                preFinishProgress = SpherePoseRefinement.RefineProgress()
                mainHandler.removeCallbacks(preFinishRunnable)
                val frameIndex = info?.frameIndex ?: 0
                if (frameIndex >= SphereRefinePolicy.captureRefineMinFrames()) {
                    val delay = captureRefineDelay(acceptedSinceRefine)
                    scheduleCaptureRefine(delay)
                    schedulePreFinish(delay + 50L)
                }
            }
            drainQueueOrRelease()
            mainHandler.post { onResult?.invoke(result) }
        }
    }

    private fun captureRefineDelay(acceptedSinceRefine: Int): Long =
        refineDelayMs.coerceAtLeast(SphereRefinePolicy.captureRefineDelayMs())

    /** (Re)arm debounced capture pose refine (rotation + translation graph). */
    private fun scheduleCaptureRefine(delayMs: Long = SphereRefinePolicy.captureRefineDelayMs()) {
        mainHandler.removeCallbacks(refineRunnable)
        cancelIntrinsicRefine()
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
        SphereProductionStitchPipeline.configureForFinish(operations)
    }

    private fun cancelIntrinsicRefine() {
        intrinsicRunnable?.let { mainHandler.removeCallbacks(it) }
        intrinsicRunnable = null
    }

    /**
     * Mid-capture: chained rotation, motion-warp translation, global 6-DoF graph.
     * Runs on [refineExecutor] so fast-path capture stays off the critical path.
     */
    private fun runCaptureRefine() {
        val generation = refinementGeneration.get()
        safeExecute(refineExecutor) {
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            configureForCaptureRefine()
            val start = SystemClock.elapsedRealtime()
            val changed = SphereCaptureRefinement.run(
                operations = operations,
                isCancelled = { !isRefinementGenerationCurrent(generation) },
                onPass = { label ->
                    android.util.Log.i("SphereStitchWorker", "Capture $label")
                    if (label.startsWith("global")) {
                        logRefineStats("Capture $label")
                    }
                }
            )
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            if (!changed) {
                scheduleIntrinsicRefine(generation)
                schedulePreFinish(SphereRefinePolicy.preFinishDelayMs())
                return@safeExecute
            }
            acceptedSinceRefine = SphereRefinePolicy.acceptedCountAfterPeriodicRefine()
            captureRefinementRan = true
            deliverRefinePreview(start, intrinsicRefined = false, generation)
            scheduleIntrinsicRefine(generation)
            schedulePreFinish(SphereRefinePolicy.preFinishDelayMs())
        }
    }

    /**
     * Idle pre-DONE: drain all remaining pre-finish slices on [preFinishExecutor]
     * while capture stays idle (front-loads heavy work before DONE).
     */
    private fun runPreFinishSlice() {
        if (!preFinishDrainEnabled) return
        val generation = refinementGeneration.get()
        safeExecute(preFinishExecutor) {
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            if (operations.getSolidAngleCoverage() < SphereRefinePolicy.preFinishMinSolidAngle()) {
                return@safeExecute
            }
            configureForPreFinish()
            val config = SpherePreFinishRefinement.targetConfig()
            if (preFinishProgress.isCompleteFor(config)) return@safeExecute
            val start = SystemClock.elapsedRealtime()
            var changed = false
            while (
                !preFinishProgress.isCompleteFor(config) &&
                isRefinementGenerationCurrent(generation)
            ) {
                val prior = preFinishProgress
                val (sliceChanged, newProgress) = SpherePreFinishRefinement.runOneSlice(
                    operations = operations,
                    progress = preFinishProgress,
                    isCancelled = { !isRefinementGenerationCurrent(generation) },
                    onPass = { label ->
                        android.util.Log.i("SphereStitchWorker", label)
                        if (label.contains("global")) {
                            logRefineStats(label)
                        }
                    }
                )
                if (!isRefinementGenerationCurrent(generation)) return@safeExecute
                changed = changed || sliceChanged
                preFinishProgress = newProgress
                preFinishFrameIndex = operations.getLastFrameInfo()?.frameIndex ?: 0
                if (newProgress == prior && !newProgress.isCompleteFor(config)) break
            }
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            if (changed) {
                deliverRefinePreview(start, intrinsicRefined = false, generation)
            }
            if (preFinishProgress.isCompleteFor(config)) {
                captureRefinementRan = true
                android.util.Log.i(
                    "SphereStitchWorker",
                    "Pre-finish drain complete in ${SystemClock.elapsedRealtime() - start}ms " +
                        "(frame=$preFinishFrameIndex)"
                )
            }
        }
    }

    /** Layer 3: intrinsic pass — separate thread, never blocks DONE. */
    private fun scheduleIntrinsicRefine(captureGeneration: Int = refinementGeneration.get()) {
        cancelIntrinsicRefine()
        val runnable = Runnable { runIntrinsicRefine(captureGeneration) }
        intrinsicRunnable = runnable
        mainHandler.postDelayed(runnable, intrinsicDelayMs)
    }

    private fun runIntrinsicRefine(generation: Int) {
        safeExecute(intrinsicExecutor) {
            if (!isRefinementGenerationCurrent(generation)) return@safeExecute
            val start = SystemClock.elapsedRealtime()
            val changed = operations.refineIntrinsics()
            if (!changed || !isRefinementGenerationCurrent(generation)) return@safeExecute
            deliverRefinePreview(start, intrinsicRefined = true, generation)
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
        val nextBitmap: Bitmap?
        val nextQuat: FloatArray?
        val nextPhotoMode: Boolean
        synchronized(this) {
            nextBitmap = queuedBitmap
            nextQuat = queuedQuaternion
            nextPhotoMode = queuedPhotoMode
            queuedBitmap = null
            queuedQuaternion = null
            queuedPhotoMode = false
        }
        if (nextBitmap != null && nextQuat != null) {
            runStitch(nextBitmap, nextQuat, nextPhotoMode)
        } else {
            inFlight.set(false)
            dispatchStitchIdleCallbacks()
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
     * Final panorama generation: refine + seam blend + full equirect, entirely
     * off the UI thread. [onComplete] is invoked on the main thread.
     */
    fun runFinishGeneration(
        eqW: Int,
        eqH: Int,
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: (SphereFinishResult) -> Unit
    ) {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        cancelIntrinsicRefine()
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                val frameNow = operations.getLastFrameInfo()?.frameIndex ?: 0
                val startProgress = if (preFinishFrameIndex == frameNow) {
                    preFinishProgress
                } else {
                    SpherePoseRefinement.RefineProgress()
                }
                val preFinishConfig = SphereRefinePolicy.preFinishRefinementPasses()
                val totalSlices = startProgress.totalSlicesFor(preFinishConfig).coerceAtLeast(1)
                var slicesDone = startProgress.slicesDoneFor(preFinishConfig)
                SphereProductionStitchPipeline.runFinishExport(
                    operations = operations,
                    exportW = eqW,
                    exportH = eqH,
                    startProgress = startProgress,
                    isCancelled = { !isRefinementGenerationCurrent(finishGeneration) },
                    onPass = { label ->
                        android.util.Log.i("SphereStitchWorker", "Finish $label")
                        if (label.startsWith("global") || label.startsWith("converge")) {
                            logRefineStats("Finish $label")
                        }
                        slicesDone = (slicesDone + 1).coerceAtMost(totalSlices)
                        val percent = ((slicesDone * 70) / totalSlices).coerceIn(5, 70)
                        onProgress?.invoke(percent, label)
                    }
                )
                onProgress?.invoke(85, "Blending seams…")
                captureRefinementRan = false
                preFinishProgress = SpherePoseRefinement.RefineProgress()
                preFinishFrameIndex = 0
                if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                val equirect = operations.getEquirectBitmap(eqW, eqH)
                val result = SphereFinishResult(
                    equirect = equirect,
                    durationMs = SystemClock.elapsedRealtime() - start,
                    ranOnMainThread = ranOnMain
                )
                mainHandler.post { onComplete(result) }
                // L2b global + L3 intrinsics in background; light seams only (no viewer replace).
                safeExecute(intrinsicExecutor) {
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    val iStart = SystemClock.elapsedRealtime()
                    if (SphereRefinePolicy.runBackgroundGlobalPose()) {
                        operations.refineGlobalPose()
                        logRefineStats("L2 finish background")
                        operations.refineSeams(light = true)
                    }
                    if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                    operations.refineIntrinsics()
                    operations.refineSeams(light = true)
                    android.util.Log.i(
                        "SphereStitchWorker",
                        "Finish background polish (light seams) done in " +
                            "${SystemClock.elapsedRealtime() - iStart}ms"
                    )
                    mainHandler.post { onFinishBackgroundPolish?.invoke() }
                }
            }
        }
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
        cancelIntrinsicRefine()
        val finishGeneration = refinementGeneration.get()
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                SphereProductionStitchPipeline.runSweepFastFinishExport(
                    operations = operations,
                    exportW = eqW,
                    exportH = eqH,
                    imuDegradeDeg = imuDegradeDeg
                )
                if (!isRefinementGenerationCurrent(finishGeneration)) return@safeExecute
                val equirect = operations.getEquirectBitmap(eqW, eqH)
                val result = SphereFinishResult(
                    equirect = equirect,
                    durationMs = SystemClock.elapsedRealtime() - start,
                    ranOnMainThread = ranOnMain
                )
                mainHandler.post { onComplete(result) }
            }
        }
    }

    /** @deprecated Use [runWhenStitchIdle] or [runFinishGeneration]. */
    fun runWhenIdle(onComplete: () -> Unit) {
        runWhenStitchIdle { mainHandler.post(onComplete) }
    }

    fun shutdown() {
        isShutdown = true
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.removeCallbacks(preFinishRunnable)
        cancelIntrinsicRefine()
        synchronized(pendingIdleCallbacks) { pendingIdleCallbacks.clear() }
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = null
        lastPreviewAtMs = 0L
        synchronized(this) {
            queuedBitmap?.recycle()
            queuedBitmap = null
            queuedQuaternion = null
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

    private fun buildPreviewWithHoles(photoMode: Boolean, solidAngle: Float): Bitmap? {
        val now = SystemClock.elapsedRealtime()
        val throttleMs = SphereRefinePolicy.previewThrottleMs()
        if (!photoMode && now - lastPreviewAtMs < throttleMs) {
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
        const val PERIODIC_REFINE_FRAMES = 6

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
