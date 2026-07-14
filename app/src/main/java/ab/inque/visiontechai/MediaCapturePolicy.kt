package ab.inque.visiontechai

/**
 * Decides how photo/video capture should behave for the active vision mode.
 */
object MediaCapturePolicy {

    /** True when the filter overlay may hold a processed bitmap worth saving. */
    fun shouldCaptureOverlayBitmap(mode: VisionMode): Boolean = when (mode) {
        VisionMode.NIGHT_VISION,
        VisionMode.EDGE_DETECTION,
        VisionMode.COLOR_ADJUSTMENT,
        VisionMode.HD,
        VisionMode.OPTICAL_FLOW,
        VisionMode.SEGMENTATION,
        VisionMode.DEPTH_FIELD,
        VisionMode.TEXT_TRANSLATION -> true
        else -> false
    }

    /** True when video should be encoded from processed pipeline frames instead of raw CameraX. */
    fun shouldRecordProcessedVideo(mode: VisionMode): Boolean =
        mode != VisionMode.NONE && mode != VisionMode.OBJECT_DETECTION
}
