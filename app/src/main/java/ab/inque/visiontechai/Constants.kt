package ab.inque.visiontechai

/**
 * Application-wide constants.
 */
object Constants {
    const val OVERLAY_AUTO_HIDE_MS = 3000L
    const val DUAL_CAMERA_SWITCH_INTERVAL_MS = 200L
    const val FPS_UPDATE_INTERVAL_MS = 1000L

    const val HISTOGRAM_DEBUG_LOG_INTERVAL_FRAMES = 30
    const val LOADING_OVERLAY_DISMISS_MS = 500L

    /** Elevation for overlay views (e.g. info button, controls). */
    const val ELEVATION_CONTROLS = 1000f
    const val ELEVATION_HUD = 500f
    const val ELEVATION_OVERLAY = 200f
    const val ELEVATION_EFFECT_OVERLAY = 150f
    const val ELEVATION_NIGHT_VISION = 100f
    const val ELEVATION_DUAL_CAMERA = 50f

    /** Folder visible in device gallery */
    const val GALLERY_IMAGE_RELATIVE_PATH = "Pictures/PrismAI"
    const val GALLERY_VIDEO_RELATIVE_PATH = "Movies/PrismAI"
    const val GALLERY_DOCUMENT_RELATIVE_PATH = "Documents/PrismAI"

    /** Filename prefix for all saved media so they're findable in gallery and after reinstall */
    const val MEDIA_FILENAME_PREFIX = "PrismAI_"
}
