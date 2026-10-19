package ab.inque.visiontechai

/**
 * Maps HD slider position (0–100) to resample scale factor.
 * 50 = 1.0x, below 50 downscales toward 0.01x, above 50 upscales toward 4.0x.
 */
object HdScaleMapper {
    private const val MIN_SCALE = 0.01f
    private const val MAX_SCALE = 4.0f
    private const val CENTER_SCALE = 1.0f
    private const val CENTER_SLIDER = 50

    fun sliderToScale(sliderValue: Int): Float {
        return when {
            sliderValue < CENTER_SLIDER -> {
                val normalized = sliderValue / CENTER_SLIDER.toFloat()
                CENTER_SCALE - (CENTER_SCALE - MIN_SCALE) * (1f - normalized)
            }
            sliderValue > CENTER_SLIDER -> {
                val normalized = (sliderValue - CENTER_SLIDER) / CENTER_SLIDER.toFloat()
                CENTER_SCALE + (MAX_SCALE - CENTER_SCALE) * normalized
            }
            else -> CENTER_SCALE
        }
    }
}
