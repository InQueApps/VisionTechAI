package ab.inque.visiontechai

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks device orientation via the fused rotation vector sensor.
 * Provides the current camera orientation as a quaternion (x, y, z, w).
 *
 * Maintains a timestamped ring buffer of recent quaternions so that
 * callers can retrieve the orientation at an arbitrary past timestamp
 * via SLERP interpolation — essential for synchronizing camera frames
 * with IMU data when the CameraX pipeline introduces latency.
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var quaternion = floatArrayOf(0f, 0f, 0f, 1f) // identity

    private data class TimestampedQuat(val timestampNanos: Long, val quat: FloatArray)

    private val history = ArrayDeque<TimestampedQuat>(MAX_HISTORY)
    private val historyLock = Any()

    fun start() {
        if (rotationSensor == null) {
            Log.w(TAG, "Rotation vector sensor not available")
            return
        }
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "Orientation tracking started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(historyLock) { history.clear() }
        Log.i(TAG, "Orientation tracking stopped")
    }

    /** Returns a copy of the current (latest) orientation quaternion [x, y, z, w]. */
    fun getQuaternion(): FloatArray = quaternion.copyOf()

    /**
     * Returns the interpolated quaternion at a specific timestamp (elapsedRealtimeNanos).
     * If the timestamp is outside the stored history, returns the nearest available sample.
     * Falls back to the latest quaternion if history is empty.
     */
    fun getQuaternionAt(timestampNanos: Long): FloatArray {
        synchronized(historyLock) {
            if (history.isEmpty()) return quaternion.copyOf()

            // Exact match or before first entry
            val first = history.first()
            if (timestampNanos <= first.timestampNanos) return first.quat.copyOf()

            val last = history.last()
            if (timestampNanos >= last.timestampNanos) return last.quat.copyOf()

            // Binary search for the bracketing pair
            var lo = 0
            var hi = history.size - 1
            while (lo < hi - 1) {
                val mid = (lo + hi) / 2
                if (history[mid].timestampNanos <= timestampNanos) lo = mid else hi = mid
            }

            val q0 = history[lo]
            val q1 = history[hi]
            val span = (q1.timestampNanos - q0.timestampNanos).toFloat()
            if (span < 1f) return q0.quat.copyOf()

            val t = ((timestampNanos - q0.timestampNanos).toFloat() / span).coerceIn(0f, 1f)
            return slerp(q0.quat, q1.quat, t)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)
        // SensorManager returns [w, x, y, z], we store as [x, y, z, w]
        val xyzw = floatArrayOf(q[1], q[2], q[3], q[0])
        quaternion = xyzw

        synchronized(historyLock) {
            if (history.size >= MAX_HISTORY) history.removeFirst()
            history.addLast(TimestampedQuat(event.timestamp, xyzw.copyOf()))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "OrientationTracker"
        private const val MAX_HISTORY = 200 // ~1s at SENSOR_DELAY_GAME (200 Hz)

        /**
         * Spherical linear interpolation between two unit quaternions.
         * Both q1 and q2 are [x, y, z, w].
         */
        private fun slerp(q1: FloatArray, q2: FloatArray, t: Float): FloatArray {
            var dot = q1[0]*q2[0] + q1[1]*q2[1] + q1[2]*q2[2] + q1[3]*q2[3]

            // Take shorter arc
            val q2a: FloatArray
            if (dot < 0f) {
                dot = -dot
                q2a = floatArrayOf(-q2[0], -q2[1], -q2[2], -q2[3])
            } else {
                q2a = q2
            }

            if (dot > 0.9995f) {
                // Nearly identical — linear interpolation + normalize
                val r = FloatArray(4)
                for (i in 0..3) r[i] = q1[i] + t * (q2a[i] - q1[i])
                val len = sqrt(r[0]*r[0] + r[1]*r[1] + r[2]*r[2] + r[3]*r[3])
                if (len > 1e-6f) for (i in 0..3) r[i] /= len
                return r
            }

            val theta = acos(dot.toDouble().coerceIn(-1.0, 1.0)).toFloat()
            val sinTheta = sin(theta.toDouble()).toFloat()
            val w1 = sin(((1f - t) * theta).toDouble()).toFloat() / sinTheta
            val w2 = sin((t * theta).toDouble()).toFloat() / sinTheta

            return floatArrayOf(
                w1 * q1[0] + w2 * q2a[0],
                w1 * q1[1] + w2 * q2a[1],
                w1 * q1[2] + w2 * q2a[2],
                w1 * q1[3] + w2 * q2a[3]
            )
        }
    }
}
