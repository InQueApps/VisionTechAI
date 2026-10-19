package ab.inque.robotics.resample

/**
 * JNI for fast bicubic resample and unsharp mask (HD algorithm-only, no ML).
 */
object ResampleNative {

    init {
        try {
            System.loadLibrary("vision_resample")
        } catch (_: UnsatisfiedLinkError) {
            // Fallback to Kotlin in ImageResampleUtils
        }
    }

    /** Returns resampled ARGB pixels (outW * outH), or null if native unavailable. */
    @JvmStatic
    external fun bicubicResample(
        inPixels: IntArray,
        inW: Int,
        inH: Int,
        outW: Int,
        outH: Int
    ): IntArray?

    /** Applies unsharp mask in-place to pixels. */
    @JvmStatic
    external fun unsharpMask(
        pixels: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        amount: Float
    )
}
