package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SphereMotionDepthEstimatorTest {

    @Test
    fun estimateForStitch_returnsNullWithoutProcessor() {
        val estimator = SphereMotionDepthEstimator(depthProcessor = null)
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val px = IntArray(32 * 24) { Color.GRAY }
        bmp.setPixels(px, 0, 32, 0, 0, 32, 24)
        assertNull(estimator.estimateForStitch(bmp))
    }

    @Test
    fun estimateForStitch_reusesCachedMapForSameSize() {
        val estimator = SphereMotionDepthEstimator(depthProcessor = null)
        val bmp = checkerboard(32, 24)
        val cached = FloatArray(32 * 24) { 0.6f }
        estimator.javaClass.getDeclaredField("cachedNearness").apply {
            isAccessible = true
            set(estimator, cached)
        }
        estimator.javaClass.getDeclaredField("cachedWidth").apply {
            isAccessible = true
            set(estimator, 32)
        }
        estimator.javaClass.getDeclaredField("cachedHeight").apply {
            isAccessible = true
            set(estimator, 24)
        }
        val second = estimator.estimateForStitch(bmp)
        assertEquals(cached.size, second!!.size)
        assertEquals(cached[0], second[0], 1e-6f)
    }

    private fun checkerboard(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if ((x / 4 + y / 4) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
