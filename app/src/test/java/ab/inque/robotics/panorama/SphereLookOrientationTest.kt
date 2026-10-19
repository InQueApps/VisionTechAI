package ab.inque.robotics.panorama

import ab.inque.robotics.math.QuaternionMath
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLookOrientationTest {

    @Test
    fun quatToAzEl_stableForSameOrientation() {
        val q = QuaternionMath.normalize(floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f))
        val (az1, el1) = SphereLookOrientation.quatToAzEl(q)
        val (az2, el2) = SphereLookOrientation.quatToAzEl(q)
        assertEquals(az1, az2, 1e-5f)
        assertEquals(el1, el2, 1e-5f)
    }

    @Test
    fun relativeRotation_identityWhenEqual() {
        val q = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)
        val n = QuaternionMath.normalize(q)
        val rel = SphereLookOrientation.relativeRotation(n, n)
        assertTrue(abs(rel[0]) < 0.02f)
        assertTrue(abs(rel[1]) < 0.02f)
        assertTrue(abs(rel[2]) < 0.02f)
        assertTrue(abs(rel[3] - 1f) < 0.02f)
    }
}
