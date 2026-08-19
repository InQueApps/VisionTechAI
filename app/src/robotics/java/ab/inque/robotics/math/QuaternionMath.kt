package ab.inque.robotics.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion math shared by [OrientationTracker] and sphere tests.
 * Convention: [x, y, z, w].
 */
object QuaternionMath {

    fun slerp(q1: FloatArray, q2: FloatArray, t: Float): FloatArray {
        require(q1.size >= 4 && q2.size >= 4)
        var dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]

        val q2a: FloatArray
        if (dot < 0f) {
            dot = -dot
            q2a = floatArrayOf(-q2[0], -q2[1], -q2[2], -q2[3])
        } else {
            q2a = q2
        }

        if (dot > 0.9995f) {
            val r = FloatArray(4)
            for (i in 0..3) r[i] = q1[i] + t * (q2a[i] - q1[i])
            return normalize(r)
        }

        val theta = acos(dot.toDouble().coerceIn(-1.0, 1.0)).toFloat()
        val sinTheta = sin(theta.toDouble()).toFloat()
        val w1 = sin(((1f - t) * theta).toDouble()).toFloat() / sinTheta
        val w2 = sin((t * theta).toDouble()).toFloat() / sinTheta

        return normalize(
            floatArrayOf(
                w1 * q1[0] + w2 * q2a[0],
                w1 * q1[1] + w2 * q2a[1],
                w1 * q1[2] + w2 * q2a[2],
                w1 * q1[3] + w2 * q2a[3]
            )
        )
    }

    fun normalize(q: FloatArray): FloatArray {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
    }

    /** Rotation vector [x,y,z,w] applied to device-space unit ray. Matches C++ quatRotate. */
    fun rotateVector(q: FloatArray, vx: Double, vy: Double, vz: Double): DoubleArray {
        val x = q[0].toDouble()
        val y = q[1].toDouble()
        val z = q[2].toDouble()
        val w = q[3].toDouble()
        val tx = 2.0 * (y * vz - z * vy)
        val ty = 2.0 * (z * vx - x * vz)
        val tz = 2.0 * (x * vy - y * vx)
        return doubleArrayOf(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx)
        )
    }

    /** Quaternion from axis-angle (axis need not be normalized). Angle in radians. */
    fun fromAxisAngle(ax: Double, ay: Double, az: Double, angleRad: Double): FloatArray {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-9) return floatArrayOf(0f, 0f, 0f, 1f)
        val ux = ax / len
        val uy = ay / len
        val uz = az / len
        val half = angleRad * 0.5
        val s = sin(half)
        return floatArrayOf(
            (ux * s).toFloat(),
            (uy * s).toFloat(),
            (uz * s).toFloat(),
            cos(half).toFloat()
        )
    }

    fun magnitude(q: FloatArray): Float =
        sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])

    /** Hamilton product a ⊗ b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 4 && b.size >= 4)
        return floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        )
    }
}
