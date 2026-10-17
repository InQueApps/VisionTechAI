package ab.inque.visiontechai

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-blocking nearness maps for sphere stitch (S30/S44).
 * Returns MiDaS nearness when available; otherwise null (no gradient pseudo-depth).
 */
class SphereMotionDepthEstimator(
    private val depthProcessor: DepthEstimationProcessor?
) {

    @Volatile
    private var cachedNearness: FloatArray? = null

    @Volatile
    private var cachedWidth: Int = 0

    @Volatile
    private var cachedHeight: Int = 0

    private val frameCounter = AtomicInteger(0)

    /** Called from [SphereStitchWorker] executor — must not block on MiDaS. */
    fun estimateForStitch(bitmap: Bitmap): FloatArray? {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val cached = cachedNearness
        if (cached != null && cached.size == n && cachedWidth == w && cachedHeight == h) {
            scheduleMiDaSRefresh(bitmap)
            return cached
        }
        scheduleMiDaSRefresh(bitmap)
        return null
    }

    private fun scheduleMiDaSRefresh(bitmap: Bitmap) {
        val processor = depthProcessor ?: return
        if (frameCounter.incrementAndGet() % REFRESH_EVERY_N_FRAMES != 0) return
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) ?: return
        processor.scheduleNearnessMap(copy) { map ->
            copy.recycle()
            if (map.size == bitmap.width * bitmap.height) {
                cachedNearness = map
                cachedWidth = bitmap.width
                cachedHeight = bitmap.height
            }
        }
    }

    companion object {
        private const val REFRESH_EVERY_N_FRAMES = 3
    }
}
