package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

object NightVisionNative {

    private const val TAG = "NightVisionNative"

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_nightvision")
            loaded = true
            Log.i(TAG, "Native night vision library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vision_nightvision: ${e.message}")
        }
    }

    fun enhance(bitmap: Bitmap, intensity: Float): Bitmap? {
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

        val ok = nativeEnhance(pixIn, pixOut, w, h, intensity.coerceIn(0f, 1f))
        if (!ok) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixOut, 0, w, 0, 0, w, h)
        return result
    }

    private external fun nativeEnhance(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        intensity: Float
    ): Boolean
}
