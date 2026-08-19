package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage
import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.motion.AngularMotionEstimator
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Manages the sphere panorama capture session.
 * State machine: IDLE -> CAPTURING -> PREVIEW
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
    private val motionDepthEstimator: ((Bitmap) -> FloatArray)? = null,
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

    enum class State { IDLE, CAPTURING, PREVIEW }

    @Volatile var state = State.IDLE
        private set

    @Volatile var lastCoverage: Float = 0f
        private set

    @Volatile var lastSolidAngleCoverage: Float = 0f
        private set

    @Volatile var lastPreviewBitmap: Bitmap? = null
        private set

    @Volatile private var lastFullEquirect: Bitmap? = null

    @Volatile private var fullExportInProgress = false

    @Volatile private var fullExportComplete = false

    private val pendingExportCallbacks = CopyOnWriteArrayList<(SphereFinishResult) -> Unit>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Optional hook when gallery-quality export finishes (background SAVE path). */
    var onFullExportReady: ((Bitmap) -> Unit)? = null

    /** Fired on main thread after idle pre-DONE / post-DONE alignment polish updates the viewer. */
    var onBackgroundPolishReady: (() -> Unit)? = null

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

    val hasCapturedFrames: Boolean get() = frameCount > 0

    fun isFullExportInProgress(): Boolean = fullExportInProgress

    fun isFullExportComplete(): Boolean = fullExportComplete
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
            if (state == State.PREVIEW && frameCount >= 2) {
                notifyIntrinsicsConverged()
                if (SphereRefinePolicy.refreshViewerAfterBackgroundFinish()) {
                    val polished = operations.getEquirectBitmap(EQ_W, EQ_H) ?: lastFullEquirect
                    if (polished != null) {
                        lastFullEquirect = polished
                        onFullExportReady?.invoke(polished)
                    }
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
        this.bitmapAlreadyMirrored = bitmapAlreadyMirrored
        orientationResolved = !shouldProbeMirror(operations, bitmapAlreadyMirrored)
        heldProbeFrame?.first?.recycle()
        heldProbeFrame = null
        SphereNative.setMirrorCameraX(false)
        state = State.CAPTURING
        frameCount = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap = null
        lastFullEquirect = null
        fullExportInProgress = false
        fullExportComplete = false
        lastMotionQuat = null
        lastMotionTimeMs = 0L
        orientationTracker.clockSync.reset()
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
        imageRotationDegrees: Int = 0
    ) {
        if (state != State.CAPTURING) return
        ensureSessionGeometry(bitmap, imageRotationDegrees)
        val frame = prepareStitchFrame(bitmap)
        applyStitchFov(frame.fovH, frame.fovV)
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
        if (shouldProbeMirror() && !orientationResolved) {
            val held = heldProbeFrame
            if (held == null) {
                heldProbeFrame = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) to stitchQuat.copyOf()
                submitStitchFrame(frame, stitchQuat, photoMode)
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
                stitchWorker.submit(heldBitmap, heldQuat, photoMode = photoMode)
            } else {
                heldBitmap.recycle()
            }
            orientationResolved = true
        }
        submitStitchFrame(frame, stitchQuat, photoMode)
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

    private fun applyStitchFov(fovH: Float, fovV: Float) {
        if (kotlin.math.abs(fovH - stitchFovH) > 0.5f || kotlin.math.abs(fovV - stitchFovV) > 0.5f) {
            SphereNative.setSessionFov(fovH, fovV)
            stitchFovH = fovH
            stitchFovV = fovV
        }
    }

    private fun submitStitchFrame(
        frame: SphereStitchFramePrep.Result,
        stitchQuat: FloatArray,
        photoMode: Boolean
    ) {
        stitchWorker.submit(frame.bitmap, stitchQuat, photoMode = photoMode)
        if (frame.cropped) {
            frame.bitmap.recycle()
        }
    }

    private fun handleStitchResult(result: SphereStitchResult) {
        // Background refinement finished: refresh coverage + preview, no frame accounting.
        if (result.refined) {
            lastCoverage = result.coverage
            lastSolidAngleCoverage = result.solidAngleCoverage
            result.previewBitmap?.let { lastPreviewBitmap = it }
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
            val preview = result.previewBitmap ?: lastPreviewBitmap
            if (result.previewBitmap != null) lastPreviewBitmap = result.previewBitmap
            onCoverageUpdate(result.coverage, result.solidAngleCoverage, preview)
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
        val preview = result.previewBitmap ?: lastPreviewBitmap
        if (result.previewBitmap != null) {
            lastPreviewBitmap = result.previewBitmap
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
                captureFrame(bitmap, frameTimestampNanos, photoMode = false, imageRotationDegrees)
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
     * Stop capture and open the viewer immediately with the current stitch preview
     * (no full global refine — that runs on SAVE via [startBackgroundFullExport]).
     */
    fun finishCapture(): Boolean {
        if (state != State.CAPTURING || frameCount == 0) return false
        continuousCapture = false
        stitchWorker.runWhenStitchIdle {
            val previewBmp = operations.getEquirectBitmap(
                SphereStitchWorker.PREVIEW_W,
                SphereStitchWorker.PREVIEW_H
            ) ?: lastPreviewBitmap
            mainHandler.post {
                if (state != State.CAPTURING) return@post
                lastFullEquirect = previewBmp
                fullExportComplete = false
                state = State.PREVIEW
                Log.i(
                    TAG,
                    "Capture preview ready ($frameCount frames), coverage=$lastCoverage " +
                        "solid=$lastSolidAngleCoverage — full export on SAVE"
                )
                previewBmp?.let { onPreviewReady(it) }
                stitchWorker.schedulePreFinishDrain(0L)
            }
        }
        return true
    }

    /**
     * Production finish (global refine + export res) on a background thread.
     * Safe to call while the user has left sphere mode.
     */
    fun startBackgroundFullExport(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((SphereFinishResult) -> Unit)? = null
    ): Boolean {
        if (state == State.IDLE || frameCount == 0) return false
        if (fullExportComplete && lastFullEquirect != null) {
            val bmp = lastFullEquirect!!
            onComplete?.invoke(SphereFinishResult(bmp, 0L, ranOnMainThread = false))
            onFullExportReady?.invoke(bmp)
            return true
        }
        onComplete?.let { pendingExportCallbacks.add(it) }
        if (fullExportInProgress) return true
        fullExportInProgress = true
        stitchWorker.runFinishGeneration(
            EQ_W,
            EQ_H,
            onProgress = onProgress
        ) { result ->
            fullExportInProgress = false
            if (BuildConfig.DEBUG && result.ranOnMainThread) {
                Log.e(TAG, "Finish generation ran on main thread — this should never happen")
            }
            if (result.equirect != null) {
                fullExportComplete = true
                lastFullEquirect = result.equirect
                onFullExportReady?.invoke(result.equirect!!)
            }
            Log.i(
                TAG,
                "Background full export done in ${result.durationMs}ms " +
                    "(frames=$frameCount)"
            )
            val callbacks = pendingExportCallbacks.toList()
            pendingExportCallbacks.clear()
            callbacks.forEach { it(result) }
        }
        return true
    }

    /**
     * Return from post-DONE preview to live capture without clearing the stitched panorama.
     */
    fun resumeCapture(): Boolean {
        if (state != State.PREVIEW || fullExportInProgress) return false
        stitchWorker.invalidatePendingRefinement()
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
        lastFullEquirect = equirect
        if (state == State.PREVIEW) {
            Log.i(TAG, "Intrinsic finish pass updated preview equirect")
            onPreviewReady(equirect)
            if (frameCount >= 2) {
                notifyIntrinsicsConverged()
            }
        }
    }

    fun getPreviewBitmap(): Bitmap? = lastPreviewBitmap

    /** Returns the full equirect from the last finish, or null if not yet generated. */
    fun getFullEquirect(): Bitmap? = lastFullEquirect

    /** Latest native equirect for export (includes in-progress intrinsic pass if done). */
    fun exportFullEquirect(): Bitmap? =
        operations.getEquirectBitmap(EQ_W, EQ_H) ?: lastFullEquirect

    fun resetSession() {
        operations.reset()
        heldProbeFrame?.first?.recycle()
        heldProbeFrame = null
        orientationResolved = false
        state = State.IDLE
        frameCount = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap = null
        lastFullEquirect = null
        fullExportInProgress = false
        fullExportComplete = false
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
        if (fullExportInProgress) return false
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
        pendingExportCallbacks.clear()
        if (fullExportInProgress) {
            stitchWorker.invalidatePendingRefinement()
        }
        stitchWorker.shutdown()
        if (!stitchWorker.awaitTermination(awaitWorkerMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Stitch worker did not terminate within ${awaitWorkerMs}ms")
        }
        operations.release()
        state = State.IDLE
        fullExportInProgress = false
        fullExportComplete = false
    }

    companion object {
        private const val TAG = "SphereCaptureCtrl"

        /** Internal stitch / refine resolution during capture (fast background recompute). */
        const val CAPTURE_EQ_W = 1024
        const val CAPTURE_EQ_H = 512

        /** Gallery / viewer export resolution. */
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
