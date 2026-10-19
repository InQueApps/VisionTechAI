package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.sensors.OrientationQuaternionHistory


import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationQuaternionHistoryTest {

    private val q0 = floatArrayOf(0f, 0f, 0f, 1f)
    private val q1 = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(30.0))

    @Test
    fun interpolateAt_midpoint_betweenSamples() {
        val history = OrientationQuaternionHistory()
        history.add(0L, q0)
        history.add(1000L, q1)
        val mid = history.interpolateAt(500L, q0)
        val expected = QuaternionMath.slerp(q0, q1, 0.5f)
        for (i in 0..3) {
            assertEquals(expected[i], mid[i], 0.001f)
        }
    }

    @Test
    fun interpolateAt_beforeFirst_returnsFirst() {
        val history = OrientationQuaternionHistory()
        history.add(100L, q1)
        val out = history.interpolateAt(0L, q0)
        for (i in 0..3) assertEquals(q1[i], out[i], 0.001f)
    }

    @Test
    fun interpolateAt_afterLast_returnsLast() {
        val history = OrientationQuaternionHistory()
        history.add(100L, q1)
        val out = history.interpolateAt(500L, q0)
        for (i in 0..3) assertEquals(q1[i], out[i], 0.001f)
    }
}
