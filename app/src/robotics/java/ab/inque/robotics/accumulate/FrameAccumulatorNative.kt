package ab.inque.robotics.accumulate

import android.graphics.Bitmap
import android.util.Log

/**
 * Reusable multi-frame accumulator with translational alignment.
 * Any filter can call [accumulate] to stack frames, then feed the
 * returned bitmap into its own enhancement pipeline.
 */
object FrameAccumulatorNative {

    private const val TAG = "FrameAccumulatorNative"

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_accumulator")
            loaded = true
            Log.i(TAG, "Native frame accumulator library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vision_accumulator: ${e.message}")
        }
    }

    val isLoaded: Boolean get() = loaded

    /**
     * Add [bitmap] to the running average. Returns the current accumulated
     * image as a new Bitmap, or null on failure.
     *
     * @param numFrames  Averaging window size (alpha = 1/numFrames).
     * @param align      If true, align the new frame to the accumulated reference
     *                   using pyramid-based SSD matching.
     */
    fun accumulate(bitmap: Bitmap, numFrames: Int, align: Boolean = true): Bitmap? {
        if (!loaded) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val src = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }

        val n = w * h
        val pixIn = IntArray(n)
        val pixOut = IntArray(n)
        src.getPixels(pixIn, 0, w, 0, 0, w, h)

        val ok = nativeAccumulate(pixIn, pixOut, w, h, numFrames, align)
        if (!ok) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixOut, 0, w, 0, 0, w, h)
        return result
    }

    fun clear() {
        if (loaded) nativeClear()
    }

    fun getFrameCount(): Int {
        return if (loaded) nativeGetFrameCount() else 0
    }

    private external fun nativeAccumulate(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        numFrames: Int,
        doAlign: Boolean
    ): Boolean

    private external fun nativeClear()
    private external fun nativeGetFrameCount(): Int
}
