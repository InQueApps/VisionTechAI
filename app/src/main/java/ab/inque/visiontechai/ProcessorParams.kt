package ab.inque.visiontechai

/**
 * Parameters for frame processors. Each mode uses the subset it needs.
 */
data class ProcessorParams(
    val nightVisionIntensity: Float = 0.5f,
    /** Night vision: number of frames to average for exposure stacking (1 = off, 2–10 = multi-frame). */
    val nightVisionExposureFrames: Int = 1,
    val edgeDetectionSensitivity: Float = 0.5f,
    /** HD scale slider 0–100: 50 = 1.0x, &lt;50 = downsample, &gt;50 = upsample (used in IMG modes). */
    val hdScaleSliderValue: Int = 50,
    /** HD: number of frames to average before resampling (1 = off, 2–10 = multi-frame). */
    val hdExposureFrames: Int = 1,
    val redAdjustment: Float = 1.0f,
    val greenAdjustment: Float = 1.0f,
    val blueAdjustment: Float = 1.0f,
    /** Optical flow: downscale factor 2–16 (higher = blockier). */
    val opticalFlowPixelation: Int = 8,
    /** Optical flow: color overlay intensity 0.0–1.0. */
    val opticalFlowIntensity: Float = 0.7f,
    /** Optical flow: sensitivity 0.0–1.0 (higher = detects flow on smooth surfaces, not just edges). */
    val opticalFlowSensitivity: Float = 0.3f,
    /** Segmentation: number of color clusters (3–10). Fewer = merge more, more = finer regions. */
    val segmentationNumClusters: Int = 4,
    /** Segmentation: K-means iterations (3–15). More = stabler boundaries. */
    val segmentationIterations: Int = 6,
    /** Depth field: focus plane (0 = far, 1 = near). Pixels at this depth are brightened in the depth view. */
    val depthFieldFocus: Float = 0.5f,
    /** Text translation: source language code (e.g. "en" for English). null = off. */
    val translationSourceLanguageCode: String? = null,
    /** Text translation: target language code (e.g. "es" for Spanish). null = off. */
    val translationTargetLanguageCode: String? = null,
    /** Text translation: overlay text scale multiplier (0.5–3.0). */
    val translationTextScale: Float = 1.0f,
    /** Text translation: background visibility (0.0 = black behind text, 1.0 = full camera feed). */
    val translationBackgroundAlpha: Float = 1.0f,
    /** Text translation: minimum OCR confidence to display (0.0–1.0). Words below this are hidden. */
    val translationMinConfidence: Float = 0.0f,
    /** Text translation: minimum character length for a sentence to be translated. */
    val translationMinLength: Int = 0
)
