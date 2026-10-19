package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.motion.AngularMotionEstimator


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AngularMotionEstimatorTest {

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)

    @Test
    fun identicalQuaternion_returnsZeroVelocity() {
        assertEquals(0.0, AngularMotionEstimator.angularVelocityDegPerSec(identity, identity, 100), 0.001)
    }

    @Test
    fun yaw90InOneSecond_returnsAbout90DegPerSec() {
        val rotated = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0))
        val vel = AngularMotionEstimator.angularVelocityDegPerSec(identity, rotated, 1000)
        assertTrue(vel in 85.0..95.0)
    }
}
