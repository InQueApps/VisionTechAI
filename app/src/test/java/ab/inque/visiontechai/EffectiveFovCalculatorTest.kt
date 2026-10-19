package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveFovCalculatorTest {

    @Test
    fun landscape16x9_cropsVerticalFov() {
        val (h, v) = EffectiveFovCalculator.compute(
            EffectiveFovCalculator.Input(
                sensorFovHorizontalDeg = 78f,
                sensorFovVerticalDeg = 62f,
                analysisWidth = 1920,
                analysisHeight = 1080,
                rotationDegrees = 0
            )
        )
        assertEquals(78f, h, 0.5f)
        assertTrue(v < 62f)
        assertTrue(v > 40f)
    }

    @Test
    fun portrait9x16_swapsAndMatchesLandscapeSolidAngle() {
        val landscape = EffectiveFovCalculator.compute(
            EffectiveFovCalculator.Input(78f, 62f, 1920, 1080, 0)
        )
        val portrait = EffectiveFovCalculator.compute(
            EffectiveFovCalculator.Input(78f, 62f, 1080, 1920, 90)
        )
        assertEquals(landscape.first, portrait.second, 0.5f)
        assertEquals(landscape.second, portrait.first, 0.5f)
    }

    @Test
    fun squareFrame_matchesSensorOnNarrowAxis() {
        val (h, v) = EffectiveFovCalculator.compute(
            EffectiveFovCalculator.Input(70f, 55f, 1080, 1080, 0)
        )
        assertEquals(55f, v, 0.5f)
        assertTrue(h < 70f)
    }
}
