package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuaternionMathTest {

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)

    @Test
    fun slerp_sameQuaternion_returnsNormalizedCopy() {
        val out = QuaternionMath.slerp(identity, identity, 0.5f)
        assertEquals(1f, QuaternionMath.magnitude(out), 0.001f)
    }

    @Test
    fun rotateVector_identity_preservesRay() {
        val out = QuaternionMath.rotateVector(identity, 0.0, 0.0, -1.0)
        assertEquals(-1.0, out[2], 0.001)
    }

    @Test
    fun fromAxisAngle_yawAroundY_rotatesForwardRay() {
        val q = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0))
        val out = QuaternionMath.rotateVector(q, 0.0, 0.0, -1.0)
        assertEquals(-1.0, out[0], 0.01)
        assertEquals(0.0, out[2], 0.01)
    }
}
