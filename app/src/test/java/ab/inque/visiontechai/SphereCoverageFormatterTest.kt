package ab.inque.visiontechai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereCoverageFormatterTest {

    @Test
    fun format_showsBothMetrics() {
        val text = SphereCoverageFormatter.format(0.72f, 0.58f)
        assertTrue(text.contains("72%"))
        assertTrue(text.contains("58%"))
    }

    @Test
    fun canFinish_requires85PercentSolid() {
        assertTrue(SphereCoverageFormatter.canFinish(0.85f))
        assertFalse(SphereCoverageFormatter.canFinish(0.84f))
    }
}
