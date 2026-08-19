package ab.inque.visiontechai

import ab.inque.robotics.sensors.ImuCameraClockSync
import ab.inque.robotics.sensors.OrientationQuaternionHistory


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

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

  private val history = OrientationQuaternionHistory(MAX_HISTORY)
  private val historyLock = Any()

  @Volatile
  private var quaternion = floatArrayOf(0f, 0f, 0f, 1f)

  @Volatile
  private var latestSensorTimestampNanos: Long = 0L

  val clockSync = ImuCameraClockSync()

  fun start() {
    if (rotationSensor == null) {
      Log.w(TAG, "Rotation vector sensor not available")
      return
    }
    clockSync.reset()
    sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    Log.i(TAG, "Orientation tracking started")
  }

  fun stop() {
    sensorManager.unregisterListener(this)
    synchronized(historyLock) {
      history.clear()
    }
    clockSync.reset()
    Log.i(TAG, "Orientation tracking stopped")
  }

  /** Returns a copy of the current (latest) orientation quaternion [x, y, z, w]. */
  fun getQuaternion(): FloatArray = quaternion.copyOf()

  fun getLatestSensorTimestampNanos(): Long = latestSensorTimestampNanos

  /**
   * Returns the interpolated quaternion at a specific sensor timestamp (nanoseconds).
   */
  fun getQuaternionAt(sensorTimestampNanos: Long): FloatArray {
    synchronized(historyLock) {
      return history.interpolateAt(sensorTimestampNanos, quaternion)
    }
  }

  /**
   * Maps a camera frame timestamp to sensor time (with optional clock sync) and interpolates.
   */
  fun getQuaternionForCameraFrame(cameraTimestampNanos: Long): FloatArray {
    if (cameraTimestampNanos > 0L) {
      recordClockSample(cameraTimestampNanos)
      val sensorTs = clockSync.cameraToSensorTimestamp(cameraTimestampNanos)
      return getQuaternionAt(sensorTs)
    }
    return getQuaternion()
  }

  private fun recordClockSample(cameraTimestampNanos: Long) {
    val sensorTs = latestSensorTimestampNanos
    if (sensorTs > 0L) {
      clockSync.recordSample(cameraTimestampNanos, sensorTs)
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
    val q = FloatArray(4)
    SensorManager.getQuaternionFromVector(q, event.values)
    val xyzw = floatArrayOf(q[1], q[2], q[3], q[0])
    quaternion = xyzw
    latestSensorTimestampNanos = event.timestamp

    synchronized(historyLock) {
      history.add(event.timestamp, xyzw)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  companion object {
    private const val TAG = "OrientationTracker"
    private const val MAX_HISTORY = 400
  }
}
