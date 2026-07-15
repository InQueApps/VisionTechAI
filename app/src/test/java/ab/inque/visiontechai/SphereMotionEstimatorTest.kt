package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereMotionEstimatorTest {

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)

    @Test
    fun identicalQuaternion_returnsZeroVelocity() {
        assertEquals(0.0, SphereMotionEstimator.angularVelocityDegPerSec(identity, identity, 100), 0.001)
    }

    @Test
    fun yaw90InOneSecond_returnsAbout90DegPerSec() {
        val rotated = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0))
        val vel = SphereMotionEstimator.angularVelocityDegPerSec(identity, rotated, 1000)
        assertTrue(vel in 85.0..95.0)
    }
}
