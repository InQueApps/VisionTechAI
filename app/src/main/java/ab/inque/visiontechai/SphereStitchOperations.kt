package ab.inque.visiontechai

import android.graphics.Bitmap

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
