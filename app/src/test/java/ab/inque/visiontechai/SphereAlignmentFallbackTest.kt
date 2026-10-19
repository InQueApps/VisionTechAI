package ab.inque.visiontechai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereAlignmentFallbackTest {

    @Test
    fun shouldAcceptRelaxed_whenBorderlineSsdAndStrongOverlap() {
        assertTrue(
            SphereAlignmentFallback.shouldAcceptRelaxed(
                residualSsd = 9000f,
                overlapCount = 100,
                wasRejected = true
            )
        )
    }

    @Test
    fun shouldRejectRelaxed_whenSsdTooHigh() {
        assertFalse(
            SphereAlignmentFallback.shouldAcceptRelaxed(
                residualSsd = 12000f,
                overlapCount = 100,
                wasRejected = true
            )
        )
    }

    @Test
    fun shouldAccept_whenNotRejected() {
        assertTrue(
            SphereAlignmentFallback.shouldAcceptRelaxed(
                residualSsd = 20000f,
                overlapCount = 0,
                wasRejected = false
            )
        )
    }
}
