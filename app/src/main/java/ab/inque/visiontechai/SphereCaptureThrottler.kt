package ab.inque.visiontechai

/**
 * Throttles continuous sphere capture to ~5 fps.
 */
object SphereCaptureThrottler {
    const val DEFAULT_INTERVAL_MS = 200L

    fun shouldAcceptFrame(
        nowMs: Long,
        lastCaptureMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Boolean = nowMs - lastCaptureMs >= intervalMs
}
