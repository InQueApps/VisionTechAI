package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereCaptureThrottlerTest {

    @Test
    fun shouldAcceptFrame_afterInterval() {
        assertTrue(SphereCaptureThrottler.shouldAcceptFrame(500, 0))
        assertTrue(SphereCaptureThrottler.shouldAcceptFrame(400, 200))
    }

    @Test
    fun shouldRejectFrame_beforeInterval() {
        assertFalse(SphereCaptureThrottler.shouldAcceptFrame(150, 0))
    }
}
