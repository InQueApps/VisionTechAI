package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath


import org.junit.Assert.assertEquals
import org.junit.Test

class QuaternionMathMultiplyTest {

  @Test
  fun multiply_identity_preservesQuaternion() {
    val q = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(30.0))
    val out = QuaternionMath.multiply(q, floatArrayOf(0f, 0f, 0f, 1f))
    for (i in 0..3) assertEquals(q[i], out[i], 0.001f)
  }

  @Test
  fun multiply_yaw90TimesIdentity_rotatesForwardRay() {
    val yaw90 = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0))
    val combined = QuaternionMath.multiply(yaw90, floatArrayOf(0f, 0f, 0f, 1f))
    val out = QuaternionMath.rotateVector(combined, 0.0, 0.0, -1.0)
    assertEquals(-1.0, out[0], 0.01)
  }
}
