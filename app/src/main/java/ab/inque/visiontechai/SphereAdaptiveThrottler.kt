package ab.inque.visiontechai

/**
 * Adaptive continuous-capture gate based on pan speed and worker load.
 */
object SphereAdaptiveThrottler {

  const val STATIONARY_DEG_PER_SEC = 2.0
  const val SLOW_PAN_DEG_PER_SEC = 15.0
  const val FAST_PAN_DEG_PER_SEC = 45.0
  const val SLOW_INTERVAL_MS = SphereCaptureThrottler.DEFAULT_INTERVAL_MS
  const val MEDIUM_INTERVAL_MS = 150L

  enum class Decision {
    ACCEPT,
    SKIP_THROTTLE,
    SKIP_STATIONARY,
    SKIP_FAST_PAN
  }

  fun intervalForVelocity(angularVelocityDegPerSec: Double): Long = when {
    angularVelocityDegPerSec < STATIONARY_DEG_PER_SEC -> Long.MAX_VALUE
    angularVelocityDegPerSec < SLOW_PAN_DEG_PER_SEC -> SLOW_INTERVAL_MS
    angularVelocityDegPerSec < FAST_PAN_DEG_PER_SEC -> MEDIUM_INTERVAL_MS
    else -> Long.MAX_VALUE
  }

  fun evaluate(
      nowMs: Long,
      lastCaptureMs: Long,
      angularVelocityDegPerSec: Double,
      workerBusy: Boolean
  ): Decision {
    if (workerBusy) return Decision.SKIP_THROTTLE
    if (angularVelocityDegPerSec < STATIONARY_DEG_PER_SEC) return Decision.SKIP_STATIONARY
    if (angularVelocityDegPerSec >= FAST_PAN_DEG_PER_SEC) return Decision.SKIP_FAST_PAN
    val interval = intervalForVelocity(angularVelocityDegPerSec)
    if (nowMs - lastCaptureMs < interval) return Decision.SKIP_THROTTLE
    return Decision.ACCEPT
  }
}
