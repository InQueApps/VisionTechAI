package ab.inque.visiontechai

import ab.inque.robotics.image.ImageOverlapMetrics


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOverlapMetricsTest {

    @Test
    fun correlation_identicalImages_isOne() {
        val w = 64
        val h = 32
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val region = ImageOverlapMetrics.Region(8, 4, 48, 24)
        val corr = ImageOverlapMetrics.normalizedCorrelation(
            pixels, pixels, w, h, region
        )
        assertEquals(1.0, corr, 1e-6)
    }

    @Test
    fun correlation_invertedLuminance_isLow() {
        val w = 64
        val h = 32
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val even = (x / 4 + y / 4) % 2 == 0
                a[idx] = if (even) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                b[idx] = if (even) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val region = ImageOverlapMetrics.Region(0, 0, w, h)
        val corr = ImageOverlapMetrics.normalizedCorrelation(a, b, w, h, region)
        assertTrue("inverted patterns should correlate poorly (corr=$corr)", corr < 0.35)
    }

    @Test
    fun fastRefineGhostDelta_positiveWhenRefineAddsGhost() {
        assertEquals(0.05, ImageOverlapMetrics.ghostDelta(0.10, 0.15), 1e-9)
    }

    @Test
    fun fastRefineGhostDelta_respectsPolicyThreshold() {
        val delta = ImageOverlapMetrics.ghostDelta(0.08, 0.09)
        assertTrue(delta <= SphereRefinePolicy.maxGhostRegression())
        val over = ImageOverlapMetrics.ghostDelta(0.08, 0.12)
        assertTrue(over > SphereRefinePolicy.maxGhostRegression())
    }
}
