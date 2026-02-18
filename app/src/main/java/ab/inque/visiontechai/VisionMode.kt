package ab.inque.visiontechai

/**
 * Single vision enhancement mode. Only one can be active at a time.
 */
enum class VisionMode {
    NONE,
    NIGHT_VISION,
    EDGE_DETECTION,
    COLOR_ADJUSTMENT,
    HD,
    OPTICAL_FLOW,
    SEGMENTATION,
    DEPTH_FIELD,
    OBJECT_DETECTION,
    TEXT_TRANSLATION;

    val category: Category
        get() = when (this) {
            NIGHT_VISION -> Category.NV
            EDGE_DETECTION, COLOR_ADJUSTMENT, HD -> Category.IMG
            OPTICAL_FLOW, SEGMENTATION, DEPTH_FIELD, OBJECT_DETECTION -> Category.ROB
            TEXT_TRANSLATION -> Category.TXT
            NONE -> Category.NONE
        }

    val hasSlider: Boolean
        get() = when (this) {
            NIGHT_VISION, EDGE_DETECTION, COLOR_ADJUSTMENT, HD, OPTICAL_FLOW, SEGMENTATION, DEPTH_FIELD, TEXT_TRANSLATION -> true
            else -> false
        }

    enum class Category { NONE, NV, IMG, ROB, TXT }
}
