package ab.inque.robotics.video

/**
 * JNI for video frame processing (ARGB → I420) used by [ProcessedVideoRecorder].
 */
object VideoNative {

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_video")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            // Native lib not built; recorder falls back to ImageUtils.bitmapToI420
        }
    }

    fun isAvailable(): Boolean = loaded

    /**
     * Converts ARGB pixels (e.g. from Bitmap.getPixels) to I420 in [outI420].
     * [outI420] must be pre-allocated with size width * height * 3 / 2.
     * @return true if successful
     */
    external fun argbToI420(
        pixelsArgb: IntArray,
        width: Int,
        height: Int,
        outI420: ByteArray
    ): Boolean
}
