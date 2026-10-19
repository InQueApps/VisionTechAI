package ab.inque.visiontechai

import ab.inque.robotics.image.TileGainMap


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGainMapTest {

    @Test
    fun computeGains_brighterNewFrame_increasesGainBelowOne() {
        val samples = listOf(
            Pair(0, TileGainMap.Rgb(200f, 200f, 200f)),
            Pair(20, TileGainMap.Rgb(200f, 200f, 200f))
        )
        val existing = listOf(
            Pair(0, TileGainMap.Rgb(100f, 100f, 100f)),
            Pair(20, TileGainMap.Rgb(100f, 100f, 100f))
        )
        val gains = TileGainMap.computeGains(samples, existing, 320, 240)
        assertTrue(gains[0] < 1f)
    }

    @Test
    fun clampGain_limitsRange() {
        assertEquals(2f, TileGainMap.clampGain(5f), 0.001f)
        assertEquals(0.5f, TileGainMap.clampGain(0.1f), 0.001f)
    }

    @Test
    fun sampleBilinear_center_returnsTileGain() {
        val gains = FloatArray(TileGainMap.TILES * TileGainMap.TILES * 3) { 1f }
        gains[0] = 1.5f
        val g = TileGainMap.sampleBilinear(gains, 0, 0, 320, 240, 0)
        assertEquals(1.5f, g, 0.01f)
    }
}
