package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Test

class SphereAdaptiveThrottlerTest {

    @Test
    fun evaluate_stationary_skips() {
        assertEquals(
            SphereAdaptiveThrottler.Decision.SKIP_STATIONARY,
            SphereAdaptiveThrottler.evaluate(1000, 0, 0.5, workerBusy = false)
        )
    }

    @Test
    fun evaluate_fastPan_skips() {
        assertEquals(
            SphereAdaptiveThrottler.Decision.SKIP_FAST_PAN,
            SphereAdaptiveThrottler.evaluate(1000, 0, 60.0, workerBusy = false)
        )
    }

    @Test
    fun evaluate_slowPan_afterInterval_accepts() {
        assertEquals(
            SphereAdaptiveThrottler.Decision.ACCEPT,
            SphereAdaptiveThrottler.evaluate(500, 0, 10.0, workerBusy = false)
        )
    }

    @Test
    fun evaluate_workerBusy_skipsThrottle() {
        assertEquals(
            SphereAdaptiveThrottler.Decision.SKIP_THROTTLE,
            SphereAdaptiveThrottler.evaluate(500, 0, 10.0, workerBusy = true)
        )
    }

    @Test
    fun intervalForVelocity_mediumPan_returns150ms() {
        assertEquals(150L, SphereAdaptiveThrottler.intervalForVelocity(30.0))
    }
}
