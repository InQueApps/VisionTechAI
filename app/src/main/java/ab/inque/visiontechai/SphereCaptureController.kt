package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage
import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.motion.AngularMotionEstimator
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages the sphere panorama capture session.
 * State machine: IDLE -> CAPTURING -> PREVIEW_REFINING -> PREVIEW_FINALIZED
 */
class SphereCaptureController(
    private val orientationTracker: OrientationTracker,
    private val extrinsicCalibration: SphereExtrinsicCalibration,
    private val onCoverageUpdate: (areaCoverage: Float, solidAngleCoverage: Float, preview: Bitmap?) -> Unit,
    private val onPreviewReady: (Bitmap) -> Unit,
    private val onFrameWarning: ((FrameWarning) -> Unit)? = null,
    private val onFrameAccepted: (() -> Unit)? = null,
    private val onIntrinsicsConverged: ((LensDistortion) -> Unit)? = null,
    private val operations: SphereStitchOperations = SphereNativeOperations,
    private val motionDepthEstimator: ((Bitmap) -> FloatArray?)? = null,
    stitchWorker: SphereStitchWorker = SphereStitchWorker(
        operations = operations,
        motionDepthEstimator = motionDepthEstimator
    )
) {

    private var stitchWorker: SphereStitchWorker = stitchWorker

    enum class FrameWarning {
        NO_OVERLAP,
        EXCESSIVE_SHIFT,
        ALIGNMENT_FAILED,
        INSUFFICIENT_OVERLAP
    }

    enum class State { IDLE, CAPTURING, PREVIEW_REFINING, PREVIEW_FINALIZED }

    fun isPreviewSession(): Boolean =
        state == State.PREVIEW_REFINING || state == State.PREVIEW_FINALIZED

    @Volatile var state = State.IDLE
        private set

    @Volatile var lastCoverage: Float = 0f
        private set

    @Volatile var lastSolidAngleCoverage: Float = 0f
        private set

    @Volatile var lastPreviewBitmap: Bitmap? = null
        private set

    @Volatile private var lastFullEquirect: Bitmap? = null

    @Volatile private var finalizationInProgress = false

    fun isFinalizationInProgress(): Boolean = finalizationInProgress

    fun isFinalized(): Boolean =
        state == State.PREVIEW_FINALIZED && hasFullResExportReady()

    private fun hasFullResExportReady(): Boolean {
        val bmp = lastFullEquirect
        return bmp != null && !bmp.isRecycled && bmp.width >= exportWidth && bmp.height >= exportHeight
    }

    /** @deprecated use [isFinalizationInProgress] */
    fun isPreviewRefineInProgress(): Boolean = isFinalizationInProgress()

    /** @deprecated use [isFinalized] */
    fun isPreviewRefineComplete(): Boolean = isFinalized()

    /** @deprecated use [isFinalizationInProgress] */
    fun isFullExportInProgress(): Boolean = isFinalizationInProgress()

    /** @deprecated use [isFinalized] */
    fun isFullExportComplete(): Boolean = isFinalized()

    private var lastViewerRefreshMs = 0L

    private val viewerSnapshotRunnable = Runnable { requestViewerSnapshot(force = true) }

    private val viewerSnapshotExecutor = Executors.newSingleThreadExecutor()

    private val pendingExportCallbacks = CopyOnWriteArrayList<(SphereFinishResult) -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Final 4096 gallery bitmap ready — SAVE can write immediately. */
    var onFullExportReady: ((Bitmap) -> Unit)? = null

    /** Stitch snapshot copied off the worker thread for the display-only viewer. */
    var onExportViewerSnapshot: ((Bitmap) -> Unit)? = null

    /** Progress while post-DONE finalization runs (percent, stage label). */
    var onExportProgress: ((Int, String) -> Unit)? = null

    /** Fired on main thread after idle pre-DONE / post-DONE alignment polish updates the viewer. */
    var onBackgroundPolishReady: (() -> Unit)? = null

    /** True while a frame stitch (or queued stitch) is running on the worker. */
    var onStitchProcessingChanged: ((Boolean) -> Unit)? = null

    /** Debug capture hook: bitmap + camera timestamp + raw IMU + calibrated quaternions. */
    var onDebugFrameCaptured: ((Bitmap, Long, FloatArray, FloatArray) -> Unit)? = null

    var onDebugFrameStitchResult: ((
        accepted: Boolean,
        overlapRatio: Float,
        alignmentOffsetDeg: Float,
        wasRejected: Boolean
    ) -> Unit)? = null

    @Volatile private var continuousCapture = false
    private var lastCaptureTimeMs = 0L
    private var lastMotionQuat: FloatArray? = null
    private var lastMotionTimeMs = 0L
    private var frameCount = 0
    private var stitchFrameW = 0
    private var stitchFrameH = 0

    val hasCapturedFrames: Boolean get() = frameCount > 0

    /** DONE/SAVE equirect size matching the captured frames' angular resolution. */
    val exportWidth: Int get() = exportSize().width
    val exportHeight: Int get() = exportSize().height

    private fun exportSize(): SphereExportSize.Size =
        SphereExportSize.fromFrame(stitchFrameW, stitchFrameH, stitchFovH, stitchFovV)

    private var sessionFovH = 65f
    private var sessionFovV = 50f
    /** FOV passed to native for the actual stitch bitmap (center square when enabled). */
    private var stitchFovH = 65f
    private var stitchFovV = 50f
    private var sensorFovH = 65f
    private var sensorFovV = 50f
    private var sessionDistortion: LensDistortion = LensDistortion.NONE
    private var sessionGeometryResolved = false
    private var bitmapAlreadyMirrored = false
    private var orientationResolved = false
    private var heldProbeFrame: Pair<Bitmap, FloatArray>? = null
    /** Set after live mirror probe (frame 2). */
    var mirrorCameraXApplied: Boolean = false
        private set

    init {
        wireStitchWorker()
    }

    private fun wireStitchWorker() {
        stitchWorker.onResult = { result -> handleStitchResult(result) }
        stitchWorker.onIntrinsicEquirectUpdated = { equirect ->
            if (SphereRefinePolicy.refreshViewerAfterBackgroundFinish()) {
                handleIntrinsicEquirectUpdate(equirect)
            }
        }
        stitchWorker.onFinishBackgroundPolish = {
            if (isPreviewSession() && frameCount >= 2) {
                notifyIntrinsicsConverged()
                if (SphereRefinePolicy.refreshViewerAfterBackgroundFinish()) {
                    val polished = retainBitmap(
                        operations.getEquirectBitmap(exportWidth, exportHeight) ?: lastFullEquirect
                    )
                    if (polished != null) {
                        replaceFullEquirect(polished)
                        onFullExportReady?.invoke(polished)
                    }
                    requestViewerSnapshot(force = true)
                }
                onBackgroundPolishReady?.invoke()
            }
        }
    }

    fun startSession(
        sensorFovH: Float = 65f,
        sensorFovV: Float = 50f,
        fovH: Float = sensorFovH,
        fovV: Float = sensorFovV,
        distortion: LensDistortion = LensDistortion.NONE,
        bitmapAlreadyMirrored: Boolean = false
    ) {
        operations.reset()
        this.sensorFovH = sensorFovH
        this.sensorFovV = sensorFovV
        sessionFovH = fovH
        sessionFovV = fovV
        stitchFovH = fovH
        stitchFovV = fovV
        sessionDistortion = distortion
        sessionGeometryResolved = false
        val ok = operations.init(CAPTURE_EQ_W, CAPTURE_EQ_H, fovH, fovV, distortion)
        if (!ok) {
            Log.e(TAG, "Sphere native init failed — is vision_sphere loaded?")
        }
        SphereStitchTelemetry.reset()
        this.bitmapAlreadyMirrored = bitmapAlreadyMirrored
        orientationResolved = !shouldProbeMirror(operations, bitmapAlreadyMirrored)
        heldProbeFrame?.first?.recycle()
        heldProbeFrame = null
        SphereNative.setMirrorCameraX(false)
        state = State.CAPTURING
        frameCount = 0
        stitchFrameW = 0
        stitchFrameH = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = null
        replaceFullEquirect(null)
        finalizationInProgress = false
        lastMotionQuat = null
        lastMotionTimeMs = 0L
        orientationTracker.clockSync.reset()
        stitchWorker.resumeLiveCapture()
        Log.i(TAG, "Sphere session started (sensorFov=${sensorFovH}x${sensorFovV} effFov=${fovH}x${fovV})")
    }

    /**
     * Match native pinhole FOV to the upright analysis bitmap (sensor FOV + crop/aspect).
     * Re-inits on the first frame when real dimensions are known.
     */
    private fun ensureSessionGeometry(bitmap: Bitmap, imageRotationDegrees: Int) {
        val (effH, effV) = EffectiveFovCalculator.compute(
            EffectiveFovCalculator.Input(
                sensorFovHorizontalDeg = sensorFovH,
                sensorFovVerticalDeg = sensorFovV,
                analysisWidth = bitmap.width,
                analysisHeight = bitmap.height,
                rotationDegrees = imageRotationDegrees
            )
        )
        val fovChanged = kotlin.math.abs(effH - sessionFovH) > 0.75f ||
            kotlin.math.abs(effV - sessionFovV) > 0.75f
        if (!sessionGeometryResolved) {
            sessionFovH = effH
            sessionFovV = effV
            if (frameCount == 0 && heldProbeFrame == null) {
                // First frame not stitched yet — update FOV without clearing a prior stitch.
                SphereNative.setSessionFov(effH, effV)
            } else {
                operations.init(CAPTURE_EQ_W, CAPTURE_EQ_H, effH, effV, sessionDistortion)
            }
            sessionGeometryResolved = true
            Log.i(
                TAG,
                "Session geometry from frame: ${bitmap.width}x${bitmap.height} " +
                    "fov=${"%.1f".format(effH)}x${"%.1f".format(effV)}"
            )
        } else if (fovChanged) {
            sessionFovH = effH
            sessionFovV = effV
            SphereNative.setSessionFov(effH, effV)
            Log.i(TAG, "Session FOV updated: ${"%.1f".format(effH)}x${"%.1f".format(effV)}")
        }
    }

    /**
     * IMU pose for the stitcher. Bitmaps are already upright ([ImageUtils.rotateToUpright]);
     * do not apply [CameraFrameOrientation.quaternionForUprightBitmap] here — offline replay
     * selects rotation=0 for the same upright JPEGs (see orientation probe on af77be51).
     */
    private fun stitchQuaternion(calQuat: FloatArray, @Suppress("UNUSED_PARAMETER") imageRotationDegrees: Int): FloatArray =
        calQuat.copyOf()

    /**
     * Add a single frame (photo mode tap).
     */
    fun captureFrame(
        bitmap: Bitmap,
        frameTimestampNanos: Long = 0,
        photoMode: Boolean = true,
        imageRotationDegrees: Int = 0,
        angularVelocityDegPerSec: Double? = null
    ) {
        if (state != State.CAPTURING) return
        ensureSessionGeometry(bitmap, imageRotationDegrees)
        val frame = prepareStitchFrame(bitmap)
        applyStitchFov(frame.fovH, frame.fovV)
        rememberStitchFrameSize(frame.bitmap.width, frame.bitmap.height)
        val imuQuat = orientationTracker.getQuaternionForCameraFrame(frameTimestampNanos)
        val calQuat = extrinsicCalibration.applyToImu(imuQuat)
        val stitchQuat = stitchQuaternion(calQuat, imageRotationDegrees)
        onDebugFrameCaptured?.invoke(frame.bitmap, frameTimestampNanos, imuQuat, calQuat)
        if (frameCount == 0) {
            Log.i(
                TAG,
                "First frame: ${frame.bitmap.width}x${frame.bitmap.height} " +
                    "(source ${bitmap.width}x${bitmap.height} centerSquare=${SphereRefinePolicy.useCenterSquareStitch()}), " +
                    "quat=[${stitchQuat[0]}, ${stitchQuat[1]}, ${stitchQuat[2]}, ${stitchQuat[3]}] " +
                    "rot=$imageRotationDegrees stitchFov=${"%.1f".format(frame.fovH)}x${"%.1f".format(frame.fovV)}"
            )
        }
        val angularVel = angularVelocityDegPerSec
            ?: estimateAngularVelocity(calQuat, SystemClock.elapsedRealtime())
        if (shouldProbeMirror() && !orientationResolved) {
            val held = heldProbeFrame
            if (held == null) {
                heldProbeFrame = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) to stitchQuat.copyOf()
                submitStitchFrame(frame, stitchQuat, photoMode, angularVel)
                return
            }
            val mirror = ab.inque.visiontechai.sphere.debug.SphereStitchOrientationProbe.resolveLiveMirror(
                CAPTURE_EQ_W, CAPTURE_EQ_H, stitchFovH, stitchFovV, sessionDistortion,
                held.first, held.second, frame.bitmap, stitchQuat, bitmapAlreadyMirrored
            )
            val heldBitmap = held.first
            val heldQuat = held.second
            heldProbeFrame = null
            if (mirror != mirrorCameraXApplied) {
                operations.reset()
                operations.init(CAPTURE_EQ_W, CAPTURE_EQ_H, stitchFovH, stitchFovV, sessionDistortion)
                SphereNative.setMirrorCameraX(mirror)
                mirrorCameraXApplied = mirror
                stitchWorker.submit(
                    heldBitmap,
                    heldQuat,
                    photoMode = photoMode,
                    angularVelocityDegPerSec = angularVel
                )
            } else {
                heldBitmap.recycle()
            }
            orientationResolved = true
        }
        submitStitchFrame(frame, stitchQuat, photoMode, angularVel)
    }

    private fun submitStitchFrame(
        frame: SphereStitchFramePrep.Result,
        stitchQuat: FloatArray,
        photoMode: Boolean,
        angularVelocityDegPerSec: Double
    ) {
        stitchWorker.submit(
            frame.bitmap,
            stitchQuat,
            photoMode = photoMode,
            angularVelocityDegPerSec = angularVelocityDegPerSec
        )
        notifyStitchProcessingChanged()
        if (frame.cropped) {
            frame.bitmap.recycle()
        }
    }

    private fun prepareStitchFrame(bitmap: Bitmap): SphereStitchFramePrep.Result {
        if (!SphereRefinePolicy.useCenterSquareStitch()) {
            return SphereStitchFramePrep.Result(
                bitmap = bitmap,
                cropped = false,
                fovH = sessionFovH,
                fovV = sessionFovV
            )
        }
        return SphereStitchFramePrep.centerSquare(bitmap, sessionFovH, sessionFovV)
    }

    private fun rememberStitchFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == stitchFrameW && height == stitchFrameH) return
        stitchFrameW = width
        stitchFrameH = height
        val export = exportSize()
        Log.i(
            TAG,
            "Stitch input ${width}x${height} fov=${"%.1f".format(stitchFovH)}x${"%.1f".format(stitchFovV)} " +
                "→ export ${export.width}x${export.height}"
        )
    }

    private fun applyStitchFov(fovH: Float, fovV: Float) {
        if (kotlin.math.abs(fovH - stitchFovH) > 0.5f || kotlin.math.abs(fovV - stitchFovV) > 0.5f) {
            SphereNative.setSessionFov(fovH, fovV)
            stitchFovH = fovH
            stitchFovV = fovV
        }
    }

    private fun notifyStitchProcessingChanged() {
        onStitchProcessingChanged?.invoke(isStitchBusy())
    }

    private fun handleStitchResult(result: SphereStitchResult) {
        if (isPreviewSession() && !result.refined) {
            notifyStitchProcessingChanged()
            return
        }
        if (isPreviewSession() && result.refined && !finalizationInProgress) {
            return
        }
        // Background refinement finished: refresh coverage + preview, no frame accounting.
        if (result.refined) {
            lastCoverage = result.coverage
            lastSolidAngleCoverage = result.solidAngleCoverage
            result.previewBitmap?.let { bmp ->
                lastPreviewBitmap?.recycle()
                lastPreviewBitmap = retainBitmap(bmp)
            }
            onCoverageUpdate(
                result.coverage,
                result.solidAngleCoverage,
                result.previewBitmap ?: lastPreviewBitmap
            )
            val layer = if (result.intrinsicRefined) "intrinsic" else "global"
            Log.i(TAG, "Panorama refined ($layer): coverage=${result.coverage} solid=${result.solidAngleCoverage}")
            if (result.intrinsicRefined) {
                notifyIntrinsicsConverged()
            }
            return
        }

        // Frame gated for insufficient overlap: tell the user to pan slower.
        if (result.rejectedForOverlap) {
            Log.w(TAG, "Frame gated — insufficient overlap (pan slower)")
            onFrameWarning?.invoke(FrameWarning.INSUFFICIENT_OVERLAP)
            result.frameInfo?.let { info ->
                onDebugFrameStitchResult?.invoke(
                    false,
                    info.overlapRatio,
                    info.alignmentOffsetDeg,
                    true
                )
            }
            lastCoverage = result.coverage
            lastSolidAngleCoverage = result.solidAngleCoverage
            val preview = result.previewBitmap?.let { retainBitmap(it) } ?: lastPreviewBitmap
            if (result.previewBitmap != null) {
                lastPreviewBitmap?.recycle()
                lastPreviewBitmap = preview
            }
            onCoverageUpdate(result.coverage, result.solidAngleCoverage, preview)
            notifyStitchProcessingChanged()
            return
        }

        if (result.accepted) {
            frameCount++
            onFrameAccepted?.invoke()
            Log.i(
                TAG,
                "Frame accepted (#$frameCount): coverage=${result.coverage} solid=${result.solidAngleCoverage} " +
                    "preview=${result.previewBitmap?.width}x${result.previewBitmap?.height}"
            )
        } else {
            Log.w(TAG, "Frame rejected — preview unchanged (coverage=${result.coverage})")
        }
        lastCoverage = result.coverage
        lastSolidAngleCoverage = result.solidAngleCoverage
        val preview = result.previewBitmap?.let { retainBitmap(it) } ?: lastPreviewBitmap
        if (result.previewBitmap != null) {
            lastPreviewBitmap?.recycle()
            lastPreviewBitmap = preview
        }
        onCoverageUpdate(result.coverage, result.solidAngleCoverage, preview)
        if (result.accepted && preview == null) {
            Log.e(TAG, "Frame accepted but preview bitmap is null — native stitch may have failed")
        }
        result.frameInfo?.let { info ->
            onDebugFrameStitchResult?.invoke(
                result.accepted,
                info.overlapRatio,
                info.alignmentOffsetDeg,
                info.wasRejected
            )
            SphereAlignmentPolicy.classifyWarning(info)?.let { onFrameWarning?.invoke(it) }
        }
        notifyStitchProcessingChanged()
        if (BuildConfig.DEBUG && result.ranOnMainThread) {
            Log.e(TAG, "Sphere stitch ran on main thread — this should never happen")
        }
    }

    /**
     * Feed a frame during continuous capture (video mode).
     * Uses adaptive throttling based on angular velocity.
     */
    fun feedContinuousFrame(
        bitmap: Bitmap,
        frameTimestampNanos: Long = 0,
        imageRotationDegrees: Int = 0
    ) {
        if (state != State.CAPTURING || !continuousCapture) return
        val now = System.currentTimeMillis()
        val imuQuat = orientationTracker.getQuaternionForCameraFrame(frameTimestampNanos)
        val calQuat = extrinsicCalibration.applyToImu(imuQuat)
        val angularVel = estimateAngularVelocity(calQuat, now)
        when (
            SphereAdaptiveThrottler.evaluate(
                now,
                lastCaptureTimeMs,
                angularVel,
                stitchWorker.isBusy
            )
        ) {
            SphereAdaptiveThrottler.Decision.ACCEPT -> {
                lastCaptureTimeMs = now
                captureFrame(
                    bitmap,
                    frameTimestampNanos,
                    photoMode = false,
                    imageRotationDegrees,
                    angularVelocityDegPerSec = angularVel
                )
            }
            SphereAdaptiveThrottler.Decision.SKIP_FAST_PAN ->
                onFrameWarning?.invoke(FrameWarning.EXCESSIVE_SHIFT)
            SphereAdaptiveThrottler.Decision.SKIP_STATIONARY,
            SphereAdaptiveThrottler.Decision.SKIP_THROTTLE -> Unit
        }
    }

    private fun estimateAngularVelocity(currentQuat: FloatArray, nowMs: Long): Double {
        val prev = lastMotionQuat
        val prevMs = lastMotionTimeMs
        lastMotionQuat = currentQuat.copyOf()
        lastMotionTimeMs = nowMs
        if (prev == null || prevMs == 0L) return 0.0
        return AngularMotionEstimator.angularVelocityDegPerSec(prev, currentQuat, nowMs - prevMs)
    }

    fun startContinuousCapture() {
        continuousCapture = true
        lastCaptureTimeMs = 0L
        lastMotionQuat = null
        lastMotionTimeMs = 0L
        Log.i(TAG, "Continuous capture started")
    }

    fun stopContinuousCapture() {
        continuousCapture = false
        Log.i(TAG, "Continuous capture stopped, $frameCount frames total")
    }

    val isContinuousCapturing get() = continuousCapture

    fun isStitchBusy(): Boolean = stitchWorker.isBusy

    /**
     * Stop capture, show live preview immediately, then finalize asynchronously.
     */
    fun finishCapture(): Boolean {
        if (state != State.CAPTURING || frameCount == 0) return false
        continuousCapture = false
        stitchWorker.abortLiveCapture()
        stitchWorker.awaitStitchIdle()
        operations.flushPendingFrameStores()
        state = State.PREVIEW_REFINING
        finalizationInProgress = false
        val previewBmp = retainBitmap(
            lastPreviewBitmap
                ?: operations.getEquirectBitmap(
                    SphereStitchWorker.PREVIEW_W,
                    SphereStitchWorker.PREVIEW_H
                )
        )
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = previewBmp
        replaceFullEquirect(previewBmp)
        mainHandler.post {
            Log.i(
                TAG,
                "Capture preview ready ($frameCount frames), coverage=$lastCoverage " +
                    "solid=$lastSolidAngleCoverage — finalizing in background"
            )
            if (previewBmp != null) {
                onPreviewReady(previewBmp)
            } else {
                Log.w(TAG, "DONE with no preview bitmap — viewer may be empty")
            }
            notifyStitchProcessingChanged()
        }
        startBackgroundFinalization(
            onProgress = { percent, stage ->
                mainHandler.post { onExportProgress?.invoke(percent, stage) }
            }
        )
        return true
    }

    /**
     * Post-DONE: 2048 feather preview then input-matched export finalization.
     */
    fun startBackgroundFinalization(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((SphereFinishResult) -> Unit)? = null
    ): Boolean {
        if (state == State.IDLE || frameCount == 0) return false
        if (isFinalized() && lastFullEquirect != null) {
            val bmp = retainBitmap(lastFullEquirect)!!
            onComplete?.invoke(SphereFinishResult(bmp, 0L, ranOnMainThread = false))
            onFullExportReady?.invoke(bmp)
            return true
        }
        onComplete?.let { pendingExportCallbacks.add(it) }
        if (finalizationInProgress) return true
        finalizationInProgress = true
        state = State.PREVIEW_REFINING
        val onFinish: (SphereFinishResult) -> Unit = { result ->
            finalizationInProgress = false
            if (BuildConfig.DEBUG && result.ranOnMainThread) {
                Log.e(TAG, "Finalization ran on main thread — this should never happen")
            }
            val exportBmp = resolveFullResExportBitmap(result.equirect)
            if (exportBmp != null) {
                state = State.PREVIEW_FINALIZED
                replaceFullEquirect(exportBmp)
                onFullExportReady?.invoke(exportBmp)
                postViewerSnapshotCopy(exportBmp)
            } else {
                Log.e(
                    TAG,
                    "Post-DONE finalization failed — no ${exportWidth}x${exportHeight} export " +
                        "(frames=$frameCount, hadResult=${result.equirect != null})"
                )
                state = State.PREVIEW_REFINING
            }
            if (frameCount >= 2) {
                notifyIntrinsicsConverged()
            }
            Log.i(
                TAG,
                "Post-DONE finalization done in ${result.durationMs}ms (frames=$frameCount, " +
                    "export=${lastFullEquirect?.width}x${lastFullEquirect?.height})"
            )
            mainHandler.post {
                onExportProgress?.invoke(
                    if (hasFullResExportReady()) 100 else 0,
                    if (hasFullResExportReady()) "finalized" else "finalization_failed"
                )
            }
            val callbacks = pendingExportCallbacks.toList()
            pendingExportCallbacks.clear()
            callbacks.forEach { it(result) }
        }
        stitchWorker.runPostDoneFinalization(
            previewW = SphereRefinePolicy.donePreviewWidth(),
            previewH = SphereRefinePolicy.donePreviewHeight(),
            exportW = exportWidth,
            exportH = exportHeight,
            onProgress = onProgress,
            onStageViewerRefresh = { requestViewerSnapshot() },
            onIntermediatePreview = { intermediate ->
                replaceFullEquirect(intermediate)
                postViewerSnapshotCopy(intermediate)
            },
            onComplete = onFinish
        )
        return true
    }

    /** @deprecated use [startBackgroundFinalization] */
    fun startBackgroundPreviewRefine(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((SphereFinishResult) -> Unit)? = null
    ): Boolean = startBackgroundFinalization(onProgress, onComplete)

    /**
     * @deprecated Finalization runs after DONE; SAVE only writes the finalized bitmap.
     */
    fun startBackgroundFullExport(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((SphereFinishResult) -> Unit)? = null
    ): Boolean = startBackgroundFinalization(onProgress, onComplete)

    /**
     * Snapshot stitch state into a display bitmap (off stitch thread) and deliver to the viewer.
     * Never loads into [SphereNative] — viewer uses [SphereViewerNative].
     */
    private fun requestViewerSnapshot(force: Boolean = false) {
        if (!isPreviewSession()) return
        val throttleMs = SphereRefinePolicy.viewerRefreshThrottleMs()
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastViewerRefreshMs
        mainHandler.removeCallbacks(viewerSnapshotRunnable)
        if (!force && elapsed < throttleMs) {
            mainHandler.postDelayed(viewerSnapshotRunnable, throttleMs - elapsed)
            return
        }
        viewerSnapshotExecutor.execute {
            if (!isPreviewSession()) return@execute
            val raw = operations.getEquirectBitmap(
                SphereStitchWorker.PREVIEW_W,
                SphereStitchWorker.PREVIEW_H
            )
            val copy = retainBitmap(raw)
            if (raw != null && raw !== copy) raw.recycle()
            if (copy == null) return@execute
            lastViewerRefreshMs = SystemClock.elapsedRealtime()
            mainHandler.post { onExportViewerSnapshot?.invoke(copy) }
        }
    }

    private fun postViewerSnapshotCopy(source: Bitmap) {
        val copy = retainBitmap(source)
        if (copy != null) {
            mainHandler.post { onExportViewerSnapshot?.invoke(copy) }
        }
    }

    private fun resolveFullResExportBitmap(primary: Bitmap?): Bitmap? {
        val fromPrimary = primary?.takeIf { !it.isRecycled && it.width >= exportWidth && it.height >= exportHeight }
        if (fromPrimary != null) return retainBitmap(fromPrimary)
        val native = operations.getEquirectBitmap(exportWidth, exportHeight)
        val fromNative = native?.takeIf { !it.isRecycled && it.width >= exportWidth && it.height >= exportHeight }
        val copy = retainBitmap(fromNative)
        if (native != null && native !== copy) native.recycle()
        return copy
    }

    private fun replaceFullEquirect(bitmap: Bitmap?) {
        if (bitmap == null) {
            lastFullEquirect?.let { prev ->
                if (prev !== lastPreviewBitmap) prev.recycle()
            }
            lastFullEquirect = null
            return
        }
        val prev = lastFullEquirect
        if (prev != null && prev !== lastPreviewBitmap && prev !== bitmap) {
            prev.recycle()
        }
        lastFullEquirect = bitmap
    }

    private fun retainBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Return from post-DONE preview to live capture without clearing the stitched panorama.
     */
    fun resumeCapture(): Boolean {
        if (!isPreviewSession()) return false
        if (finalizationInProgress) {
            stitchWorker.invalidatePendingRefinement()
            finalizationInProgress = false
        } else {
            stitchWorker.invalidatePendingRefinement()
        }
        stitchWorker.resumeLiveCapture()
        continuousCapture = false
        state = State.CAPTURING
        val preview = operations.getEquirectBitmap(
            SphereStitchWorker.PREVIEW_W,
            SphereStitchWorker.PREVIEW_H
        )
        val holeMask = operations.getHoleMaskBitmap(
            SphereStitchWorker.PREVIEW_W,
            SphereStitchWorker.PREVIEW_H
        )
        lastPreviewBitmap = SphereHoleOverlay.compositePreview(preview, holeMask)
            ?: preview?.copy(Bitmap.Config.ARGB_8888, false)
        onCoverageUpdate(lastCoverage, lastSolidAngleCoverage, lastPreviewBitmap)
        Log.i(TAG, "Resumed capture ($frameCount frames), coverage=$lastCoverage")
        return true
    }

    private fun notifyIntrinsicsConverged() {
        val k1 = operations.getSessionDistortionK1()
        onIntrinsicsConverged?.invoke(LensDistortion(k1 = k1))
        Log.i(TAG, "Session intrinsics converged: k1=$k1")
    }

    private fun handleIntrinsicEquirectUpdate(equirect: Bitmap) {
        val copy = retainBitmap(equirect) ?: return
        replaceFullEquirect(copy)
        if (isPreviewSession()) {
            Log.i(TAG, "Intrinsic finish pass updated preview equirect")
            onPreviewReady(copy)
            if (frameCount >= 2) {
                notifyIntrinsicsConverged()
            }
        }
    }

    fun getPreviewBitmap(): Bitmap? = lastPreviewBitmap

    /** Returns the full equirect from the last finish, or null if not yet generated. */
    fun getFullEquirect(): Bitmap? =
        lastFullEquirect?.takeIf { !it.isRecycled }

    /**
     * Full-resolution bitmap for SAVE. Live stitching runs at preview resolution; this
     * resplats stored frames at [exportWidth]×[exportHeight] (input-matched).
     */
    fun ensureFullResExportBitmap(): Bitmap? {
        val cached = lastFullEquirect
        if (
            cached != null &&
            !cached.isRecycled &&
            cached.width >= exportWidth &&
            cached.height >= exportHeight
        ) {
            return retainBitmap(cached)
        }
        operations.flushPendingFrameStores()
        if (!operations.prepareExportEquirect(exportWidth, exportHeight)) {
            Log.w(TAG, "prepareExportEquirect($exportWidth x $exportHeight) failed")
            return null
        }
        val native = operations.getEquirectBitmap(exportWidth, exportHeight)
        val copy = retainBitmap(native)
        if (native != null && native !== copy) native.recycle()
        if (copy != null && copy.width >= exportWidth && copy.height >= exportHeight) {
            replaceFullEquirect(copy)
            if (state == State.PREVIEW_REFINING) {
                state = State.PREVIEW_FINALIZED
            }
        }
        return copy
    }

    /** Latest native equirect for export (includes in-progress intrinsic pass if done). */
    fun exportFullEquirect(): Bitmap? =
        operations.getEquirectBitmap(exportWidth, exportHeight)?.takeIf { !it.isRecycled }
            ?: getFullEquirect()?.takeIf { it.width >= exportWidth && it.height >= exportHeight }

    fun resetSession() {
        val failedExport = SphereFinishResult(
            equirect = null,
            durationMs = 0L,
            ranOnMainThread = false
        )
        val callbacks = pendingExportCallbacks.toList()
        pendingExportCallbacks.clear()
        if (finalizationInProgress) {
            stitchWorker.invalidatePendingRefinement()
        }
        operations.flushPendingFrameStores()
        operations.reset()
        heldProbeFrame?.first?.recycle()
        heldProbeFrame = null
        orientationResolved = false
        state = State.IDLE
        frameCount = 0
        stitchFrameW = 0
        stitchFrameH = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap?.recycle()
        lastPreviewBitmap = null
        replaceFullEquirect(null)
        finalizationInProgress = false
        callbacks.forEach { it(failedExport) }
    }

    /**
     * Clears the stitched panorama and starts a fresh capture session.
     * Used when the user cancels or closes the viewer to retake the sphere.
     */
    fun restartCapture(
        fovH: Float = 65f,
        fovV: Float = 50f,
        distortion: LensDistortion = LensDistortion.NONE
    ): Boolean {
        if (finalizationInProgress) return false
        continuousCapture = false
        stitchWorker.shutdown()
        stitchWorker = SphereStitchWorker(
            operations = operations,
            motionDepthEstimator = motionDepthEstimator
        )
        wireStitchWorker()
        startSession(
            sensorFovH = fovH,
            sensorFovV = fovV,
            fovH = fovH,
            fovV = fovV,
            distortion = distortion
        )
        Log.i(TAG, "Sphere capture restarted")
        return true
    }

    fun release(awaitWorkerMs: Long = RELEASE_AWAIT_MS) {
        val failedExport = SphereFinishResult(
            equirect = null,
            durationMs = 0L,
            ranOnMainThread = false
        )
        val callbacks = pendingExportCallbacks.toList()
        pendingExportCallbacks.clear()
        if (finalizationInProgress) {
            stitchWorker.invalidatePendingRefinement()
        }
        stitchWorker.shutdown()
        if (!stitchWorker.awaitTermination(awaitWorkerMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Stitch worker did not terminate within ${awaitWorkerMs}ms")
        }
        viewerSnapshotExecutor.shutdown()
        if (!viewerSnapshotExecutor.awaitTermination(2_000L, TimeUnit.MILLISECONDS)) {
            viewerSnapshotExecutor.shutdownNow()
        }
        operations.flushPendingFrameStores()
        operations.release()
        state = State.IDLE
        finalizationInProgress = false
        callbacks.forEach { it(failedExport) }
    }

    companion object {
        private const val TAG = "SphereCaptureCtrl"

        /** Internal stitch / refine resolution during capture (fast background recompute). */
        const val CAPTURE_EQ_W = 1024
        const val CAPTURE_EQ_H = 512

        /** Default gallery export when input size is unknown. Actual SAVE size is [exportWidth]. */
        const val EQ_W = 4096
        const val EQ_H = 2048

        private const val RELEASE_AWAIT_MS = 10_000L

        private fun shouldProbeMirror(
            operations: SphereStitchOperations,
            bitmapAlreadyMirrored: Boolean
        ): Boolean =
            SphereRefinePolicy.probeMirrorOnLiveCapture() &&
                !bitmapAlreadyMirrored &&
                operations === SphereNativeOperations &&
                SphereNative.isAvailable()
    }

    private fun shouldProbeMirror(): Boolean =
        shouldProbeMirror(operations, bitmapAlreadyMirrored)
}
