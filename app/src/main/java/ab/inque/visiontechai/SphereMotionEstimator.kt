package ab.inque.visiontechai

import kotlin.math.abs
import kotlin.math.acos

/**
 * Estimates angular velocity between consecutive orientation samples.
 */
object SphereMotionEstimator {

    fun angularVelocityDegPerSec(
        qPrev: FloatArray,
        qCurr: FloatArray,
        dtMs: Long
    ): Double {
        if (dtMs <= 0L) return 0.0
        val dot = abs(
            qPrev[0] * qCurr[0] + qPrev[1] * qCurr[1] +
                qPrev[2] * qCurr[2] + qPrev[3] * qCurr[3]
        ).coerceIn(0f, 1f)
        val angleRad = 2.0 * acos(dot.toDouble())
        return Math.toDegrees(angleRad) / (dtMs / 1000.0)
    }
}
