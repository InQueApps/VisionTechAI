package ab.inque.visiontechai

import android.graphics.Bitmap
import android.util.Log

object OpticalFlowNative {

    private const val TAG = "OpticalFlowNative"

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_opticalflow")
            loaded = true
            Log.i(TAG, "Native optical flow library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vision_opticalflow: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = loaded

    fun processOpticalFlow(
        bitmap: Bitmap,
        downscale: Int,
        intensity: Float,
        sensitivity: Float
    ): Bitmap? {
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

        val ok = nativeProcessOpticalFlow(
            pixIn, pixOut, w, h,
            downscale.coerceIn(2, 16),
            intensity.coerceIn(0f, 1f),
            sensitivity.coerceIn(0f, 1f)
        )
        if (!ok) return null

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixOut, 0, w, 0, 0, w, h)
        return result
    }

    fun clear() {
        if (loaded) nativeClear()
    }

    private external fun nativeProcessOpticalFlow(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        downscale: Int,
        intensity: Float,
        sensitivity: Float
    ): Boolean

    private external fun nativeClear()
}
