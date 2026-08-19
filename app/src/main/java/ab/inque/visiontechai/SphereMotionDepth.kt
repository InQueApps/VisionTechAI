package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * G5: per-frame nearness map for parallax-weighted motion refine.
 * Values in [0,1]: 1 = close, 0 = far, 0.5 = unknown.
 */
object SphereMotionDepth {

    /**
     * Blocking nearness map for the stitch worker thread.
     * Uses MiDaS when [processor] is ready; otherwise a fast gradient heuristic.
     */
    fun estimateNearnessMap(
        bitmap: Bitmap,
        processor: DepthEstimationProcessor?
    ): FloatArray {
        processor?.extractNearnessMapBlocking(bitmap)?.let {
            return normalizeMetricNearness(it)
        }
        return gradientNearnessHeuristic(bitmap)
    }

    /**
     * Percentile-normalize raw depth to [0,1] nearness (1 = closest geometry).
     * Makes MiDaS / greyscale depth comparable across frames for G5 parallax weighting.
     */
    fun normalizeMetricNearness(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        val sorted = raw.sorted()
        val n = sorted.size
        val p10 = sorted[(n * 0.10).toInt().coerceIn(0, n - 1)]
        val p90 = sorted[(n * 0.90).toInt().coerceIn(0, n - 1)]
        val span = (p90 - p10).coerceAtLeast(1e-4f)
        return FloatArray(n) { i ->
            ((raw[i] - p10) / span).coerceIn(0f, 1f)
        }
    }

    /** Laplacian-edge heuristic: strong local contrast → likely nearer geometry. */
    fun gradientNearnessHeuristic(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val grey = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            grey[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / (3f * 255f)
        }
        val out = FloatArray(n) { 0.5f }
        if (w < 3 || h < 3) return out
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = grey[y * w + x]
                val lap = abs(4f * c -
                    grey[(y - 1) * w + x] -
                    grey[(y + 1) * w + x] -
                    grey[y * w + (x - 1)] -
                    grey[y * w + (x + 1)])
                out[y * w + x] = min(1f, max(0f, 0.35f + lap * 4f))
            }
        }
        return out
    }
}
