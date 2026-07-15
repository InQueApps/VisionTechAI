package ab.inque.visiontechai

import android.content.Context

/**
 * Persistent camera–IMU extrinsic offset (quaternion [x,y,z,w]).
 * Applied as q_world = q_imu ⊗ q_offset.
 */
class SphereExtrinsicCalibration(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOffsetQuaternion(): FloatArray = floatArrayOf(
        prefs.getFloat(KEY_QX, 0f),
        prefs.getFloat(KEY_QY, 0f),
        prefs.getFloat(KEY_QZ, 0f),
        prefs.getFloat(KEY_QW, 1f)
    )

    fun setOffsetQuaternion(q: FloatArray) {
        require(q.size >= 4)
        val n = QuaternionMath.normalize(q)
        prefs.edit()
            .putFloat(KEY_QX, n[0])
            .putFloat(KEY_QY, n[1])
            .putFloat(KEY_QZ, n[2])
            .putFloat(KEY_QW, n[3])
            .apply()
    }

    fun applyToImu(imuQuaternion: FloatArray): FloatArray =
        QuaternionMath.multiply(imuQuaternion, getOffsetQuaternion())

    fun reset() = setOffsetQuaternion(floatArrayOf(0f, 0f, 0f, 1f))

    val isIdentity: Boolean
        get() {
            val q = getOffsetQuaternion()
            return kotlin.math.abs(q[0]) < 1e-4f &&
                kotlin.math.abs(q[1]) < 1e-4f &&
                kotlin.math.abs(q[2]) < 1e-4f &&
                kotlin.math.abs(q[3] - 1f) < 1e-4f
        }

    companion object {
        private const val PREFS_NAME = "sphere_extrinsic_cal"
        private const val KEY_QX = "qx"
        private const val KEY_QY = "qy"
        private const val KEY_QZ = "qz"
        private const val KEY_QW = "qw"
    }
}
