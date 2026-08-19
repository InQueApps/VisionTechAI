package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SphereMotionDepthEstimatorTest {

    @Test
    fun estimateForStitch_returnsGradientWithoutProcessor() {
        val estimator = SphereMotionDepthEstimator(depthProcessor = null)
        val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val px = IntArray(32 * 24) { Color.GRAY }
        bmp.setPixels(px, 0, 32, 0, 0, 32, 24)
        val map = estimator.estimateForStitch(bmp)
        assertEquals(32 * 24, map.size)
    }

    @Test
    fun estimateForStitch_reusesCachedMapForSameSize() {
        val estimator = SphereMotionDepthEstimator(depthProcessor = null)
        val bmp = checkerboard(32, 24)
        val first = estimator.estimateForStitch(bmp)
        val second = estimator.estimateForStitch(bmp)
        assertEquals(first.size, second.size)
        assertEquals(first[0], second[0], 1e-6f)
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
