package ab.inque.visiontechai

import kotlin.math.atan
import kotlin.math.tan

/**
 * Maps sensor FOV to analysis-frame FOV using aspect ratio and display rotation.
 */
object EffectiveFovCalculator {

    data class Input(
        val sensorFovHorizontalDeg: Float,
        val sensorFovVerticalDeg: Float,
        val analysisWidth: Int,
        val analysisHeight: Int,
        val rotationDegrees: Int = 0
    )

    /**
     * @return horizontal and vertical FOV in degrees for the analysis bitmap (width × height).
     */
    fun compute(input: Input): Pair<Float, Float> {
        require(input.analysisWidth > 0 && input.analysisHeight > 0)

        val tanSensorH = tan(Math.toRadians(input.sensorFovHorizontalDeg.toDouble() / 2.0))
        val tanSensorV = tan(Math.toRadians(input.sensorFovVerticalDeg.toDouble() / 2.0))

        val rotated = ((input.rotationDegrees % 360) + 360) % 360
        val swapsAxes = rotated == 90 || rotated == 270

        var frameW = input.analysisWidth.toDouble()
        var frameH = input.analysisHeight.toDouble()
        if (swapsAxes) {
            frameW = input.analysisHeight.toDouble()
            frameH = input.analysisWidth.toDouble()
        }

        val sensorAspect = tanSensorH / tanSensorV
        val frameAspect = frameW / frameH

        val (tanHalfW, tanHalfH) = if (frameAspect >= sensorAspect) {
            val tanV = tanSensorV
            val tanW = tanSensorV * frameAspect
            if (tanW <= tanSensorH) tanW to tanV
            else tanSensorH to (tanSensorH / frameAspect)
        } else {
            val tanW = tanSensorH
            val tanH = tanSensorH / frameAspect
            if (tanH <= tanSensorV) tanW to tanH
            else (tanSensorV * frameAspect) to tanSensorV
        }

        val fovInternalW = Math.toDegrees(2.0 * atan(tanHalfW)).toFloat()
        val fovInternalH = Math.toDegrees(2.0 * atan(tanHalfH)).toFloat()

        return if (swapsAxes) fovInternalH to fovInternalW else fovInternalW to fovInternalH
    }
}
