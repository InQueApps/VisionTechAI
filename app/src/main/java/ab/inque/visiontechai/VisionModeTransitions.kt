package ab.inque.visiontechai

/**
 * Pure rules for native/UI cleanup when switching [VisionMode].
 * Extracted for unit testing without Activity or View bindings.
 */
object VisionModeTransitions {

    data class CleanupFlags(
        val clearSphere: Boolean,
        val clearFrameAccumulator: Boolean,
        val clearHdCache: Boolean
    )

    fun cleanupOnModeChange(previous: VisionMode, next: VisionMode): CleanupFlags {
        return CleanupFlags(
            clearSphere = previous == VisionMode.SPHERE && next != VisionMode.SPHERE,
            clearFrameAccumulator = (previous == VisionMode.NIGHT_VISION && next != VisionMode.NIGHT_VISION) ||
                (previous == VisionMode.HD && next != VisionMode.HD),
            clearHdCache = previous == VisionMode.HD && next != VisionMode.HD
        )
    }
}
