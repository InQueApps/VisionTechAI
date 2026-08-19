package ab.inque.robotics.sensors

import ab.inque.robotics.math.QuaternionMath

/** Timestamped quaternion ring buffer with SLERP lookup. */
class OrientationQuaternionHistory(private val maxSize: Int = 200) {

    data class Sample(val timestampNanos: Long, val quat: FloatArray)

    private val history = ArrayDeque<Sample>(maxSize)

    val size: Int get() = history.size

    fun clear() = history.clear()

    fun add(timestampNanos: Long, quat: FloatArray) {
        if (history.size >= maxSize) history.removeFirst()
        history.addLast(Sample(timestampNanos, quat.copyOf()))
    }

    fun interpolateAt(timestampNanos: Long, fallback: FloatArray): FloatArray {
        if (history.isEmpty()) return fallback.copyOf()

        val first = history.first()
        if (timestampNanos <= first.timestampNanos) return first.quat.copyOf()

        val last = history.last()
        if (timestampNanos >= last.timestampNanos) return last.quat.copyOf()

        var lo = 0
        var hi = history.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (history[mid].timestampNanos <= timestampNanos) lo = mid else hi = mid
        }

        val q0 = history[lo]
        val q1 = history[hi]
        val span = (q1.timestampNanos - q0.timestampNanos).toFloat()
        if (span < 1f) return q0.quat.copyOf()

        val t = ((timestampNanos - q0.timestampNanos).toFloat() / span).coerceIn(0f, 1f)
        return QuaternionMath.slerp(q0.quat, q1.quat, t)
    }
}
