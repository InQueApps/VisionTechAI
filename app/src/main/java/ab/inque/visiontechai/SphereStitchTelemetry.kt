package ab.inque.visiontechai

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * S25: In-session stitch latency and reject-rate telemetry (logcat + test hooks).
 */
object SphereStitchTelemetry {

    private const val TAG = "SphereStitchTelemetry"
    private const val MAX_SAMPLES = 256

    private val durationsMs = ArrayDeque<Long>(MAX_SAMPLES)
    private val acceptedCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)
    private val totalDurationMs = AtomicLong(0)

    @Synchronized
    fun recordFrame(durationMs: Long, accepted: Boolean) {
        if (durationsMs.size >= MAX_SAMPLES) {
            durationsMs.removeFirst()
        }
        durationsMs.addLast(durationMs)
        totalDurationMs.addAndGet(durationMs)
        if (accepted) acceptedCount.incrementAndGet() else rejectedCount.incrementAndGet()
    }

    fun acceptedFrames(): Int = acceptedCount.get()

    fun rejectedFrames(): Int = rejectedCount.get()

    fun rejectRate(): Float {
        val total = acceptedCount.get() + rejectedCount.get()
        return if (total == 0) 0f else rejectedCount.get().toFloat() / total
    }

    @Synchronized
    fun p95LatencyMs(): Long {
        if (durationsMs.isEmpty()) return 0L
        val sorted = durationsMs.sorted()
        return sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
    }

    fun reset() {
        synchronized(this) { durationsMs.clear() }
        acceptedCount.set(0)
        rejectedCount.set(0)
        totalDurationMs.set(0)
    }

    fun logSummary(label: String = "session") {
        Log.i(
            TAG,
            "$label: frames=${acceptedFrames() + rejectedFrames()} " +
                "rejectRate=${"%.1f".format(rejectRate() * 100)}% " +
                "p95=${p95LatencyMs()}ms"
        )
    }

    fun passesPerfBudgets(): Boolean =
        p95LatencyMs() <= SphereRefinePolicy.maxAddFrameP95Ms() &&
            rejectRate() <= SphereRefinePolicy.maxFrameRejectRate()
}
