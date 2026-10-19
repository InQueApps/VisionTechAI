package ab.inque.visiontechai

/**
 * Relaxed acceptance when IMU+SSD alignment is borderline (no OpenCV dependency).
 */
object SphereAlignmentFallback {

    const val REJECT_SSD_THRESH = 8000f
    const val RELAXED_MULTIPLIER = 1.35f
    const val MIN_OVERLAP_SAMPLES = 80
    const val MICRO_SHIFT_MIN_OVERLAP = 60
    const val MICRO_SHIFT_PIXEL_RANGE = 10
    const val MICRO_SHIFT_PIXEL_STEP = 5

    fun shouldAcceptRelaxed(
        residualSsd: Float,
        overlapCount: Int,
        wasRejected: Boolean
    ): Boolean {
        if (!wasRejected) return true
        if (overlapCount < MIN_OVERLAP_SAMPLES) return false
        return residualSsd <= REJECT_SSD_THRESH * RELAXED_MULTIPLIER
    }
}
