package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraFrameOrientationTest {

    @Test
    fun quaternionForUprightBitmap_zeroRotation_isIdentity() {
        val imu = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)
        val n = QuaternionMath.normalize(imu)
        val out = CameraFrameOrientation.quaternionForUprightBitmap(n, 0)
        assertQuatNear(n, out)
    }

    @Test
    fun quaternionForUprightBitmap_90Rotation_roundTripsWith270() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val rotated = CameraFrameOrientation.quaternionForUprightBitmap(identity, 90)
        val back = CameraFrameOrientation.quaternionForUprightBitmap(rotated, 270)
        assertQuatNear(identity, back, tol = 0.02f)
    }

    @Test
    fun inferRotationDegrees_portraitFov_defaultsTo90() {
        assertTrue(CameraFrameOrientation.inferRotationDegrees(49f, 63f, null) == 90)
        assertTrue(CameraFrameOrientation.inferRotationDegrees(65f, 50f, null) == 0)
    }

    private fun assertQuatNear(a: FloatArray, b: FloatArray, tol: Float = 1e-4f) {
        val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3])
        assertTrue("quat dot=$dot", dot > 1f - tol)
    }
}
