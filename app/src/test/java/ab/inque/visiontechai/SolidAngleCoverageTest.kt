package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolidAngleCoverageTest {

    @Test
    fun computeFraction_halfGridFilled() {
        val eqW = 32
        val eqH = 16
        val weights = FloatArray(eqW * eqH)
        for (y in 0 until eqH) {
            for (x in 0 until eqW / 2) {
                weights[y * eqW + x] = 1f
            }
        }
        val fraction = SolidAngleCoverage.computeFraction(weights, eqW, eqH)
        assertTrue(fraction in 0.45f..0.55f)
    }

    @Test
    fun computeFraction_singleCellFilled() {
        val eqW = 100
        val eqH = 50
        val weights = FloatArray(eqW * eqH)
        weights[0] = 1f
        val fraction = SolidAngleCoverage.computeFraction(weights, eqW, eqH)
        assertEquals(1f / (SolidAngleCoverage.GRID_W * SolidAngleCoverage.GRID_H), fraction, 0.01f)
    }

    @Test
    fun buildHoleMask_marksEmptyCellsRed() {
        val eqW = 32
        val eqH = 16
        val weights = FloatArray(eqW * eqH)
        weights[0] = 1f
        val mask = SolidAngleCoverage.buildHoleMaskArgb(weights, eqW, eqH, 32, 16)
        assertEquals(0x00000000, mask[0])
        assertEquals(0x88FF0000.toInt(), mask[mask.size - 1])
    }
}
