package ab.inque.visiontechai

import android.graphics.Bitmap
import android.util.Log

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
    private val operations: SphereStitchOperations = SphereNativeOperations,
    stitchWorker: SphereStitchWorker = SphereStitchWorker(operations = operations)
) {

    private val stitchWorker: SphereStitchWorker = stitchWorker

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

    @Volatile private var continuousCapture = false
    private var lastCaptureTimeMs = 0L
    private var lastMotionQuat: FloatArray? = null
    private var lastMotionTimeMs = 0L
    private var frameCount = 0

    init {
        stitchWorker.onResult = { result ->
            handleStitchResult(result)
        }
    }

    fun startSession(
        fovH: Float = 65f,
        fovV: Float = 50f,
        distortion: LensDistortion = LensDistortion.NONE
    ) {
        operations.reset()
        val ok = operations.init(EQ_W, EQ_H, fovH, fovV, distortion)
        if (!ok) {
            Log.e(TAG, "Sphere native init failed — is vision_sphere loaded?")
        }
        state = State.CAPTURING
        frameCount = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap = null
        lastFullEquirect = null
        lastMotionQuat = null
        lastMotionTimeMs = 0L
        orientationTracker.clockSync.reset()
        Log.i(TAG, "Sphere session started (fov=${fovH}x${fovV})")
    }

    /**
     * Add a single frame (photo mode tap).
     */
    fun captureFrame(bitmap: Bitmap, frameTimestampNanos: Long = 0) {
        if (state != State.CAPTURING) return
        val imuQuat = orientationTracker.getQuaternionForCameraFrame(frameTimestampNanos)
        val quat = extrinsicCalibration.applyToImu(imuQuat)
        if (frameCount == 0) {
            Log.i(
                TAG,
                "First frame: ${bitmap.width}x${bitmap.height}, quat=[${quat[0]}, ${quat[1]}, ${quat[2]}, ${quat[3]}]"
            )
        }
        stitchWorker.submit(bitmap, quat)
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
            Log.i(TAG, "Panorama refined: coverage=${result.coverage} solid=${result.solidAngleCoverage}")
            return
        }

        // Frame gated for insufficient overlap: tell the user to pan slower.
        if (result.rejectedForOverlap) {
            Log.w(TAG, "Frame gated — insufficient overlap (pan slower)")
            onFrameWarning?.invoke(FrameWarning.INSUFFICIENT_OVERLAP)
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
    fun feedContinuousFrame(bitmap: Bitmap, frameTimestampNanos: Long = 0) {
        if (state != State.CAPTURING || !continuousCapture) return
        val now = System.currentTimeMillis()
        val imuQuat = orientationTracker.getQuaternionForCameraFrame(frameTimestampNanos)
        val quat = extrinsicCalibration.applyToImu(imuQuat)
        val angularVel = estimateAngularVelocity(quat, now)
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
                captureFrame(bitmap, frameTimestampNanos)
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
        return SphereMotionEstimator.angularVelocityDegPerSec(prev, currentQuat, nowMs - prevMs)
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

    fun finishCapture(): Boolean {
        if (state != State.CAPTURING) return false
        continuousCapture = false
        stitchWorker.runFinishGeneration(EQ_W, EQ_H) { result ->
            if (BuildConfig.DEBUG && result.ranOnMainThread) {
                Log.e(TAG, "Finish generation ran on main thread — this should never happen")
            }
            lastFullEquirect = result.equirect
            state = State.PREVIEW
            Log.i(
                TAG,
                "Capture finished in ${result.durationMs}ms, $frameCount frames, " +
                    "coverage=$lastCoverage solid=$lastSolidAngleCoverage"
            )
            result.equirect?.let { onPreviewReady(it) }
        }
        return true
    }

    fun getPreviewBitmap(): Bitmap? = lastPreviewBitmap

    /** Returns the full equirect from the last finish, or null if not yet generated. */
    fun getFullEquirect(): Bitmap? = lastFullEquirect

    fun resetSession() {
        operations.reset()
        state = State.IDLE
        frameCount = 0
        continuousCapture = false
        lastCoverage = 0f
        lastSolidAngleCoverage = 0f
        lastPreviewBitmap = null
        lastFullEquirect = null
    }

    fun release() {
        stitchWorker.shutdown()
        operations.release()
        state = State.IDLE
    }

    companion object {
        private const val TAG = "SphereCaptureCtrl"
        const val EQ_W = 4096
        const val EQ_H = 2048
    }
}
