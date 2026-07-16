package ab.inque.visiontechai

import android.graphics.Bitmap

/** Result of a fast-path stitch attempt. */
const val STITCH_ACCEPTED = 1
const val STITCH_REJECTED = 0
const val STITCH_LOW_OVERLAP = 2

/**
 * Testable seam around [SphereNative] for background stitching.
 */
interface SphereStitchOperations {
    fun init(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion = LensDistortion.NONE
    ): Boolean
    fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean

    /**
     * Quick stitch: splat immediately using the raw IMU quaternion (no alignment),
     * gated by minimum edge overlap. Returns [STITCH_ACCEPTED], [STITCH_REJECTED],
     * or [STITCH_LOW_OVERLAP]. Default delegates to [addFrame] for test fakes.
     */
    fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int =
        if (addFrame(bitmap, quaternion)) STITCH_ACCEPTED else STITCH_REJECTED

    /**
     * Background refinement: re-align newly added frames and rebuild the
     * panorama. Returns true if the buffer changed (a refreshed preview is
     * warranted). Default is a no-op for test fakes.
     */
    fun refinePending(): Boolean = false

    fun getCoverage(): Float
    fun getSolidAngleCoverage(): Float
    fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap?
    fun getLastFrameInfo(): FrameAlignmentInfo?
    fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap?
    fun refineSeams(): Boolean = true
    fun reset()
    fun release()
}

object SphereNativeOperations : SphereStitchOperations {
    override fun init(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion
    ): Boolean = SphereNative.init(eqW, eqH, fovH, fovV, distortion)

    override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean =
        SphereNative.addFrame(bitmap, quaternion)

    override fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int =
        SphereNative.addFrameFast(bitmap, quaternion, overlapGate)

    override fun refinePending(): Boolean = SphereNative.refinePending()

    override fun getCoverage(): Float = SphereNative.getCoverage()

    override fun getSolidAngleCoverage(): Float = SphereNative.getSolidAngleCoverage()

    override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? =
        SphereNative.getHoleMaskBitmap(outW, outH)

    override fun getLastFrameInfo(): FrameAlignmentInfo? = SphereNative.getLastFrameInfo()

    override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap? =
        SphereNative.getEquirectBitmap(eqW, eqH)

    override fun refineSeams(): Boolean = SphereNative.refineSeams()

    override fun reset() = SphereNative.reset()

    override fun release() = SphereNative.release()
}
