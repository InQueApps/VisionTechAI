package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SphereMotionDepthTest {

    @Test
    fun gradientNearnessHeuristic_edgesHigherThanFlat() {
        val w = 32
        val h = 24
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { Color.rgb(128, 128, 128) }
        // Vertical edge: left dark, right bright.
        for (y in 0 until h) {
            for (x in 0 until w / 2) {
                pixels[y * w + x] = Color.rgb(40, 40, 40)
            }
            for (x in w / 2 until w) {
                pixels[y * w + x] = Color.rgb(220, 220, 220)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val map = SphereMotionDepth.gradientNearnessHeuristic(bmp)
        assertEquals(w * h, map.size)

        val edgeX = w / 2
        val edgeY = h / 2
        val flatX = w / 4
        val flatY = h / 2
        assertTrue(
            "Edge pixels should score nearer than flat interior",
            map[edgeY * w + edgeX] > map[flatY * w + flatX]
        )
    }

    @Test
    fun normalizeMetricNearness_spreadsDynamicRange() {
        val raw = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
        val norm = SphereMotionDepth.normalizeMetricNearness(raw)
        assertTrue(norm.first() <= 0.15f)
        assertTrue(norm.last() >= 0.85f)
    }

    @Test
    fun estimateNearnessMap_withoutProcessor_usesHeuristic() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val map = SphereMotionDepth.estimateNearnessMap(bmp, processor = null)
        assertEquals(64, map.size)
        map.forEach { v ->
            assertTrue(v in 0f..1f)
        }
    }
}
