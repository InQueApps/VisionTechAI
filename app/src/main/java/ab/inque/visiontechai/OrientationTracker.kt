package ab.inque.visiontechai

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Tracks device orientation via the fused rotation vector sensor.
 * Provides the current camera orientation as a quaternion (x, y, z, w).
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var quaternion = floatArrayOf(0f, 0f, 0f, 1f) // identity

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
        Log.i(TAG, "Orientation tracking stopped")
    }

    /** Returns a copy of the current orientation quaternion [x, y, z, w]. */
    fun getQuaternion(): FloatArray = quaternion.copyOf()

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)
        // SensorManager returns [w, x, y, z], we store as [x, y, z, w]
        quaternion = floatArrayOf(q[1], q[2], q[3], q[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "OrientationTracker"
    }
}
