package ab.inque.visiontechai

import java.nio.ByteBuffer

/**
 * JNI bridge for depth postprocess: normalize depth buffer and render to ARGB greyscale.
 * Used by [DepthEstimationProcessor] after TFLite inference.
 */
object DepthNative {

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_depth")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Native lib not built or ABI mismatch; processor will fall back to Kotlin path
        }
    }

    fun isAvailable(): Boolean = loaded

    /**
     * Renders raw depth buffer to ARGB greyscale pixels (light = close, dark = far).
     * [depthBuffer] must be a direct ByteBuffer containing either float or uint8 depth (row-major).
     * [outPixels] must be pre-allocated with length outWidth * outHeight; filled with ARGB.
     * @return true if successful; false and [outPixels] unchanged on error (e.g. native lib not loaded).
     */
    external fun depthRenderToGreyscale(
        depthBuffer: ByteBuffer,
        depthRows: Int,
        depthCols: Int,
        quantized: Boolean,
        outWidth: Int,
        outHeight: Int,
        outPixels: IntArray
    ): Boolean
}
