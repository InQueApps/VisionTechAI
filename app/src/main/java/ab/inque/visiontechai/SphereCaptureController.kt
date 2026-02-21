package ab.inque.visiontechai

import android.graphics.Bitmap
import android.util.Log

/**
 * Manages the sphere panorama capture session.
 * State machine: IDLE -> CAPTURING -> PREVIEW
 */
class SphereCaptureController(
    private val orientationTracker: OrientationTracker,
    private val onCoverageUpdate: (Float) -> Unit,
    private val onPreviewReady: (Bitmap) -> Unit,
    private val onFrameWarning: ((FrameWarning) -> Unit)? = null
) {

    enum class FrameWarning {
        NO_OVERLAP,
        EXCESSIVE_SHIFT
    }

    enum class State { IDLE, CAPTURING, PREVIEW }

    @Volatile var state = State.IDLE
        private set

    @Volatile private var continuousCapture = false
    private var lastCaptureTimeMs = 0L
    private var frameCount = 0

    fun startSession(fovH: Float = 65f, fovV: Float = 50f) {
        SphereNative.reset()
        SphereNative.init(EQ_W, EQ_H, fovH, fovV)
        state = State.CAPTURING
        frameCount = 0
        continuousCapture = false
        Log.i(TAG, "Sphere session started (fov=${fovH}x${fovV})")
    }

    /** Add a single frame (photo mode tap). */
    fun captureFrame(bitmap: Bitmap) {
        if (state != State.CAPTURING) return
        val quat = orientationTracker.getQuaternion()
        if (frameCount == 0) {
            Log.i(TAG, "First frame: ${bitmap.width}x${bitmap.height}, quat=[${quat[0]}, ${quat[1]}, ${quat[2]}, ${quat[3]}]")
        }
        SphereNative.addFrame(bitmap, quat)
        frameCount++
        onCoverageUpdate(SphereNative.getCoverage())
        checkFrameAlignment()
    }

    private fun checkFrameAlignment() {
        val info = SphereNative.getLastFrameInfo() ?: return
        if (info.isFirstFrame) return

        when {
            info.hasLowOverlap -> {
                Log.w(TAG, "Frame ${info.frameIndex}: low overlap ${info.overlapCount}/${info.totalSamples} " +
                        "(${(info.overlapRatio * 100).toInt()}%)")
                onFrameWarning?.invoke(FrameWarning.NO_OVERLAP)
            }
            info.hasExcessiveShift -> {
                Log.w(TAG, "Frame ${info.frameIndex}: excessive alignment shift ${info.alignmentOffsetPx}px")
                onFrameWarning?.invoke(FrameWarning.EXCESSIVE_SHIFT)
            }
        }
    }

    /**
     * Feed a frame during continuous capture (video mode).
     * Throttles to ~5 fps to avoid overloading.
     */
    fun feedContinuousFrame(bitmap: Bitmap) {
        if (state != State.CAPTURING || !continuousCapture) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureTimeMs < CONTINUOUS_INTERVAL_MS) return
        lastCaptureTimeMs = now
        captureFrame(bitmap)
    }

    fun startContinuousCapture() {
        continuousCapture = true
        lastCaptureTimeMs = 0L
        Log.i(TAG, "Continuous capture started")
    }

    fun stopContinuousCapture() {
        continuousCapture = false
        Log.i(TAG, "Continuous capture stopped, $frameCount frames total")
    }

    val isContinuousCapturing get() = continuousCapture

    /** Finish capture and transition to preview state. */
    fun finishCapture() {
        if (state != State.CAPTURING) return
        continuousCapture = false
        state = State.PREVIEW
        Log.i(TAG, "Capture finished, $frameCount frames, coverage=${SphereNative.getCoverage()}")
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        if (equirect != null) onPreviewReady(equirect)
    }

    /** Get current equirectangular preview (downscaled for display). */
    fun getPreviewBitmap(): Bitmap? {
        val full = SphereNative.getEquirectBitmap(EQ_W, EQ_H) ?: return null
        return Bitmap.createScaledBitmap(full, PREVIEW_W, PREVIEW_H, true)
    }

    /** Get full-resolution equirectangular for saving. */
    fun getFullEquirect(): Bitmap? = SphereNative.getEquirectBitmap(EQ_W, EQ_H)

    fun resetSession() {
        SphereNative.reset()
        state = State.IDLE
        frameCount = 0
        continuousCapture = false
    }

    fun release() {
        SphereNative.release()
        state = State.IDLE
    }

    companion object {
        private const val TAG = "SphereCaptureCtrl"
        const val EQ_W = 4096
        const val EQ_H = 2048
        private const val PREVIEW_W = 1024
        private const val PREVIEW_H = 512
        private const val CONTINUOUS_INTERVAL_MS = 200L // ~5 fps
    }
}
