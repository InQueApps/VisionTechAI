package ab.inque.visiontechai

/**
 * Parameters for frame processors. Each mode uses the subset it needs.
 */
data class ProcessorParams(
    val nightVisionIntensity: Float = 0.5f,
    val mlNightVisionSensitivity: Float = 0.7f,
    val edgeDetectionSensitivity: Float = 0.5f,
    /** HD scale slider 0–100: 50 = 1.0x, &lt;50 = downsample, &gt;50 = upsample (used in IMG modes). */
    val hdScaleSliderValue: Int = 50,
    val redAdjustment: Float = 1.0f,
    val greenAdjustment: Float = 1.0f,
    val blueAdjustment: Float = 1.0f,
    /** Segmentation: number of color clusters (3–10). Fewer = merge more, more = finer regions. */
    val segmentationNumClusters: Int = 4,
    /** Segmentation: K-means iterations (3–15). More = stabler boundaries. */
    val segmentationIterations: Int = 6,
    /** Depth field: focus plane (0 = far, 1 = near). Pixels at this depth are brightened in the depth view. */
    val depthFieldFocus: Float = 0.5f,
    /** Text translation: source language code (e.g. "en" for English). null = off. */
    val translationSourceLanguageCode: String? = null,
    /** Text translation: target language code (e.g. "es" for Spanish). null = off. */
    val translationTargetLanguageCode: String? = null
)
