package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereProjectionMathTest {

    @Test
    fun framePixelToRay_centerPixel_pointsAlongNegativeZ() {
        val ray = SphereProjectionMath.framePixelToRay(160, 120, 320, 240, 65f, 50f)
        assertTrue(ray[2] < 0)
        val len = kotlin.math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2])
        assertEquals(1.0, len, 0.001)
    }

    @Test
    fun worldToEquirect_northPole_mapsToTopRow() {
        val coord = SphereProjectionMath.worldToEquirect(0.0, 0.0, 1.0, 512, 256)
        assertEquals(0, coord.y)
    }

    @Test
    fun projectFramePixel_yawRotation_movesEquirectX() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val yaw30 = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(30.0))
        val c0 = SphereProjectionMath.projectFramePixel(160, 120, 320, 240, 65f, 50f, identity, 512, 256)
        val c1 = SphereProjectionMath.projectFramePixel(160, 120, 320, 240, 65f, 50f, yaw30, 512, 256)
        assertNotEquals(c0.x, c1.x)
    }
}
