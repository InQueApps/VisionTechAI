package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G2/G3/G5 motion refine: translated overlap + depth-weighted global pose rebuild.
 */
@RunWith(AndroidJUnit4::class)
class SphereMotionParallaxE2ETest {

    companion object {
        private const val TAG = "SphereMotionParallaxE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
    }

    @After
    fun teardown() {
        SphereNative.release()
    }

    private fun checkerboardBitmap(): Bitmap {
        val w = FRAME_W
        val h = FRAME_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val cell = 12
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] =
                    if (((x / cell) + (y / cell)) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun shiftBitmap(src: Bitmap, dx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) dstPx[y * w + x] = srcPx[y * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }

    @Test
    fun translatedOverlap_globalRefineWithDepth_retainsSharpness() {
        val base = checkerboardBitmap()
        val shifted = shiftBitmap(base, dx = 10)
        val nearness = SphereMotionDepth.gradientNearnessHeuristic(shifted)

        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(16.0))

        SphereNative.addFrameFast(base, q0, 0f)
        SphereNative.addFrameFast(shifted, q1, 0.10f, nearness)

        assertTrue(
            "Global motion refine must run",
            SphereNative.refinePending(applyGlobalPose = true)
        )

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, "motionParallax")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, "global motion refine sharpness=$sharpness")
        assertTrue("Motion refine should retain checkerboard detail", sharpness > 20.0)
    }
}
