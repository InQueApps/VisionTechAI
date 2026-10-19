package ab.inque.robotics.sensors

/**
 * Estimates offset between camera (elapsedRealtimeNanos) and IMU sensor timestamps.
 */
class ImuCameraClockSync(
    private val calibrationSamples: Int = 10
) {
    private val samples = ArrayDeque<Long>(calibrationSamples)
    private var offsetNanos: Long? = null

    fun reset() {
        samples.clear()
        offsetNanos = null
    }

    /** cameraTimestampNanos - sensorTimestampNanos */
    fun recordSample(cameraTimestampNanos: Long, sensorTimestampNanos: Long) {
        if (cameraTimestampNanos <= 0L || sensorTimestampNanos <= 0L) return
        if (samples.size >= calibrationSamples) samples.removeFirst()
        samples.addLast(cameraTimestampNanos - sensorTimestampNanos)
        if (samples.size >= calibrationSamples) {
            offsetNanos = samples.sorted()[samples.size / 2]
        }
    }

    fun cameraToSensorTimestamp(cameraTimestampNanos: Long): Long {
        if (cameraTimestampNanos <= 0L) return cameraTimestampNanos
        val offset = offsetNanos ?: return cameraTimestampNanos
        return cameraTimestampNanos - offset
    }

    val isCalibrated: Boolean get() = offsetNanos != null
}
