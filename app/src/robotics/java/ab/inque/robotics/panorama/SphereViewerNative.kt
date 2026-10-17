package ab.inque.robotics.panorama

import android.graphics.Bitmap
import android.util.Log

/**
 * Display-only equirect buffer for interactive 360° viewing.
 * Never modifies [SphereNative] stitch / accumulator state.
 */
object SphereViewerNative {

    private const val TAG = "SphereViewerNative"
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_sphere")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            Log.w(TAG, "vision_sphere native library not available")
        }
    }

    fun isAvailable(): Boolean = loaded

    fun loadEquirect(bitmap: Bitmap): Boolean {
        if (!loaded || bitmap.isRecycled) return false
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return nativeLoadEquirect(pixels, w, h)
    }

    fun getRectilinearBitmap(
        outW: Int,
        outH: Int,
        azimuth: Float,
        elevation: Float,
        fov: Float
    ): Bitmap? {
        if (!loaded) return null
        val pixels = IntArray(outW * outH)
        if (!nativeGetRectilinear(pixels, outW, outH, azimuth, elevation, fov)) return null
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    fun release() {
        if (!loaded) return
        nativeRelease()
    }

    private external fun nativeLoadEquirect(pixels: IntArray, w: Int, h: Int): Boolean

    private external fun nativeGetRectilinear(
        pixels: IntArray,
        outW: Int,
        outH: Int,
        azimuth: Float,
        elevation: Float,
        fov: Float
    ): Boolean

    private external fun nativeRelease()
}
