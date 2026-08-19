package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SphereStitchTelemetryTest {

    @Before
    fun reset() {
        SphereStitchTelemetry.reset()
    }

    @Test
    fun p95_andRejectRate_computedCorrectly() {
        repeat(10) { SphereStitchTelemetry.recordFrame(10, accepted = true) }
        repeat(2) { SphereStitchTelemetry.recordFrame(100, accepted = false) }
        assertEquals(12, SphereStitchTelemetry.acceptedFrames() + SphereStitchTelemetry.rejectedFrames())
        assertTrue(SphereStitchTelemetry.rejectRate() > 0.15f)
        assertTrue(SphereStitchTelemetry.p95LatencyMs() >= 10)
    }

    @Test
    fun passesPerfBudgets_whenWithinPolicy() {
        repeat(20) { SphereStitchTelemetry.recordFrame(30, accepted = true) }
        assertTrue(SphereStitchTelemetry.passesPerfBudgets())
    }
}
