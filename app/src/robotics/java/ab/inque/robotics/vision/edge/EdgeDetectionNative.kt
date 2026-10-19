package ab.inque.robotics.vision.edge

import android.graphics.Bitmap
import android.util.Log

object EdgeDetectionNative {

    private const val TAG = "EdgeDetectionNative"

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_edge")
            loaded = true
            Log.i(TAG, "Native edge detection library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vision_edge: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = loaded

    fun detectEdges(bitmap: Bitmap, sensitivity: Float): Bitmap? {
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

        val ok = nativeDetectEdges(pixIn, pixOut, w, h, sensitivity.coerceIn(0f, 1f))
        if (!ok) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixOut, 0, w, 0, 0, w, h)
        return result
    }

    private external fun nativeDetectEdges(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        sensitivity: Float
    ): Boolean
}
