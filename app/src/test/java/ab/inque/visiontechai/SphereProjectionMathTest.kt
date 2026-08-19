package ab.inque.visiontechai

import ab.inque.robotics.geometry.EquirectProjectionMath
import ab.inque.robotics.math.QuaternionMath


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EquirectProjectionMathTest {

    @Test
    fun framePixelToRay_centerPixel_pointsAlongNegativeZ() {
        val ray = EquirectProjectionMath.framePixelToRay(160, 120, 320, 240, 65f, 50f)
        assertTrue(ray[2] < 0)
        val len = kotlin.math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2])
        assertEquals(1.0, len, 0.001)
    }

    @Test
    fun worldToEquirect_northPole_mapsToTopRow() {
        val coord = EquirectProjectionMath.worldToEquirect(0.0, 0.0, 1.0, 512, 256)
        assertEquals(0, coord.y)
    }

    @Test
    fun projectFramePixel_yawRotation_movesEquirectX() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val yaw30 = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(30.0))
        val c0 = EquirectProjectionMath.projectFramePixel(160, 120, 320, 240, 65f, 50f, identity, 512, 256)
        val c1 = EquirectProjectionMath.projectFramePixel(160, 120, 320, 240, 65f, 50f, yaw30, 512, 256)
        assertNotEquals(c0.x, c1.x)
    }

    @Test
    fun equirectWorldRoundTrip_centerPixel() {
        val eqW = 512
        val eqH = 256
        val eqXf = 256.5
        val eqYf = 128.5
        val world = EquirectProjectionMath.equirectPixelToWorldRay(eqXf, eqYf, eqW, eqH)
        val back = EquirectProjectionMath.worldToEquirect(world[0], world[1], world[2], eqW, eqH)
        assertEquals(256, back.x)
        assertEquals(128, back.y)
    }

    @Test
    fun framePixelRoundTrip_center() {
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        val rt = EquirectProjectionMath.roundTripFramePixel(160, 120, 320, 240, 65f, 50f, q)
        assertNotNull(rt)
        assertEquals(160f, rt!!.first, 0.5f)
        assertEquals(120f, rt.second, 0.5f)
    }

    @Test
    fun overlappingFrames_sameFeature_mapsToNearbyEquirect() {
        val eqW = 2048
        val eqH = 1024
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q20 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0))
        val c0 = EquirectProjectionMath.projectFramePixel(320, 240, 640, 480, 65f, 50f, q0, eqW, eqH)
        val c1 = EquirectProjectionMath.projectFramePixel(320, 240, 640, 480, 65f, 50f, q20, eqW, eqH)
        val dx = kotlin.math.abs(c0.x - c1.x)
        assertTrue("20° yaw should shift equirect X (dx=$dx)", dx in 1..400)
    }
}
