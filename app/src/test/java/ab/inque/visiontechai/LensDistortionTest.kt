package ab.inque.visiontechai

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDistortionTest {

    @Test
    fun undistort_zeroCoeffs_returnsInput() {
        val out = LensDistortionMath.undistortNormalized(0.2, -0.1, LensDistortion.NONE)
        assertEquals(0.2, out[0], 0.001)
        assertEquals(-0.1, out[1], 0.001)
    }

    @Test
    fun framePixelToRay_barrelK1_movesEdgeRay() {
        val undistorted = SphereProjectionMath.framePixelToRay(
            0, 120, 320, 240, 65f, 50f, LensDistortion.NONE
        )
        val distorted = SphereProjectionMath.framePixelToRay(
            0, 120, 320, 240, 65f, 50f, LensDistortion(k1 = 0.15f)
        )
        assertNotEquals(undistorted[0], distorted[0], 0.001)
    }

    @Test
    fun framePixelToRay_centerUnchangedWithRadialDistortion() {
        val c0 = SphereProjectionMath.framePixelToRay(160, 120, 320, 240, 65f, 50f)
        val c1 = SphereProjectionMath.framePixelToRay(
            160, 120, 320, 240, 65f, 50f, LensDistortion(k1 = 0.2f)
        )
        assertEquals(c0[2], c1[2], 0.01)
    }

    private fun assertEquals(expected: Double, actual: Double, delta: Double) {
        org.junit.Assert.assertEquals(expected, actual, delta)
    }
}
