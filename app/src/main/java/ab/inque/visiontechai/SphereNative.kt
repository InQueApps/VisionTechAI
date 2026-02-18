package ab.inque.visiontechai

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI wrapper for the C++ sphere panorama engine.
 * Manages an equirectangular buffer that accumulates camera frames
 * projected via quaternion orientation.
 */
object SphereNative {

    private const val TAG = "SphereNative"
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_sphere")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            Log.w(TAG, "vision_sphere native library not available")
        }
    }

    fun isAvailable() = loaded

    fun init(eqW: Int = 4096, eqH: Int = 2048, fovH: Float = 65f, fovV: Float = 50f): Boolean {
        if (!loaded) return false
        return nativeInit(eqW, eqH, fovH, fovV)
    }

    fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean {
        if (!loaded || quaternion.size < 4) return false
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return nativeAddFrame(pixels, w, h, quaternion[0], quaternion[1], quaternion[2], quaternion[3])
    }

    fun getEquirectBitmap(eqW: Int = 4096, eqH: Int = 2048): Bitmap? {
        if (!loaded) return null
        val pixels = IntArray(eqW * eqH)
        if (!nativeGetEquirect(pixels)) return null
        val bmp = Bitmap.createBitmap(eqW, eqH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, eqW, 0, 0, eqW, eqH)
        return bmp
    }

    fun getRectilinearBitmap(outW: Int, outH: Int, azimuth: Float, elevation: Float, fov: Float): Bitmap? {
        if (!loaded) return null
        val pixels = IntArray(outW * outH)
        if (!nativeGetRectilinear(pixels, outW, outH, azimuth, elevation, fov)) return null
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    fun loadEquirect(bitmap: Bitmap): Boolean {
        if (!loaded) return false
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return nativeLoadEquirect(pixels, w, h)
    }

    fun getCoverage(): Float {
        if (!loaded) return 0f
        return nativeGetCoverage()
    }

    fun reset() {
        if (loaded) nativeReset()
    }

    fun release() {
        if (loaded) nativeRelease()
    }

    private external fun nativeInit(eqW: Int, eqH: Int, fovH: Float, fovV: Float): Boolean
    private external fun nativeAddFrame(pixels: IntArray, w: Int, h: Int, qx: Float, qy: Float, qz: Float, qw: Float): Boolean
    private external fun nativeGetEquirect(pixelsOut: IntArray): Boolean
    private external fun nativeGetRectilinear(pixelsOut: IntArray, outW: Int, outH: Int, azimuth: Float, elevation: Float, fov: Float): Boolean
    private external fun nativeLoadEquirect(pixels: IntArray, w: Int, h: Int): Boolean
    private external fun nativeGetCoverage(): Float
    private external fun nativeReset()
    private external fun nativeRelease()
}
