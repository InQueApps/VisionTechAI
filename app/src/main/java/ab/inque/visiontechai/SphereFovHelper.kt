package ab.inque.visiontechai

/**
 * @deprecated Use [EffectiveFovCalculator] with analysis frame dimensions.
 */
object SphereFovHelper {

    fun effectiveFov(
        isPortrait: Boolean,
        fovH: Float,
        fovV: Float,
        analysisWidth: Int = 0,
        analysisHeight: Int = 0,
        rotationDegrees: Int = 0
    ): Pair<Float, Float> {
        if (analysisWidth > 0 && analysisHeight > 0) {
            return EffectiveFovCalculator.compute(
                EffectiveFovCalculator.Input(
                    sensorFovHorizontalDeg = fovH,
                    sensorFovVerticalDeg = fovV,
                    analysisWidth = analysisWidth,
                    analysisHeight = analysisHeight,
                    rotationDegrees = if (rotationDegrees != 0) rotationDegrees
                    else if (isPortrait) 90 else 0
                )
            )
        }
        return if (isPortrait) fovV to fovH else fovH to fovV
    }
}
