package ab.inque.visiontechai

import kotlin.math.atan

/**
 * Horizontal / vertical field of view from sensor size and focal length.
 */
object CameraFovCalculator {

    fun fromSensor(sensorWidthMm: Float, sensorHeightMm: Float, focalLengthMm: Float): Pair<Float, Float> {
        if (focalLengthMm <= 0f) return 65f to 50f
        val fovH = (2.0 * Math.toDegrees(atan((sensorWidthMm / (2.0 * focalLengthMm)).toDouble()))).toFloat()
        val fovV = (2.0 * Math.toDegrees(atan((sensorHeightMm / (2.0 * focalLengthMm)).toDouble()))).toFloat()
        return fovH to fovV
    }
}
