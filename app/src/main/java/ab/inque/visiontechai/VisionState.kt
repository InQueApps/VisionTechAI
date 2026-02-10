package ab.inque.visiontechai

/**
 * Holds current vision mode and processor parameters. The Activity and pipeline read from this.
 */
data class VisionState(
    var mode: VisionMode = VisionMode.NONE,
    var params: ProcessorParams = ProcessorParams(),
    var isSliderExpanded: Boolean = false
) {
    val nightVisionIntensity: Float get() = params.nightVisionIntensity
    val mlNightVisionSensitivity: Float get() = params.mlNightVisionSensitivity
    val edgeDetectionSensitivity: Float get() = params.edgeDetectionSensitivity
    val redAdjustment: Float get() = params.redAdjustment
    val greenAdjustment: Float get() = params.greenAdjustment
    val blueAdjustment: Float get() = params.blueAdjustment
}
