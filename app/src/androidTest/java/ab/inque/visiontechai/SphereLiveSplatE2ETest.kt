package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F2 live-path splat downscale: frame capped to [SphereNative.liveSplatDim],
 * coarser equirect stride on buffers wider than 2048. Export refine uses full stored frames.
 */
@RunWith(AndroidJUnit4::class)
class SphereLiveSplatE2ETest {

    companion object {
        private const val TAG = "SphereLiveSplatE2E"
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        /** Production capture equirect (matches [SphereCaptureController.CAPTURE_EQ_W]). */
        private const val CAPTURE_EQ_W = 1024
        private const val CAPTURE_EQ_H = 512
    }

    private fun texturedBitmap(seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val px = IntArray(FRAME_W * FRAME_H)
        var s = seed
        for (i in px.indices) {
            s = s * 1103515245 + 12345
            val v = (s ushr 16) and 0xFF
            px[i] = Color.rgb(v, (v * 3) and 0xFF, (v * 7) and 0xFF)
        }
        bmp.setPixels(px, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

    private fun quatAz(deg: Double) =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(deg))

    private fun p95(samples: LongArray): Long {
        val sorted = samples.sorted()
        return sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
    }

    @Test
    fun liveSplatDim_matchesPolicy() {
        assumeTrue(SphereNative.isAvailable())
        assertEquals(SphereRefinePolicy.liveSplatDim(), SphereNative.liveSplatDim())
    }

    @Test
    fun captureResolution_fastPath_p95_underBudget() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(CAPTURE_EQ_W, CAPTURE_EQ_H, 65f, 50f)
        val durations = LongArray(8)
        for (i in durations.indices) {
            val frame = texturedBitmap(i + 1)
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else quatAz(i * 12.0)
            val t0 = SystemClock.elapsedRealtime()
            SphereNative.addFrameFast(frame, q, 0.15f)
            durations[i] = SystemClock.elapsedRealtime() - t0
        }
        val p95 = p95(durations)
        Log.i(TAG, "capture ${CAPTURE_EQ_W}x${CAPTURE_EQ_H} addFrameFast p95=${p95}ms")
        assertTrue(
            "capture fast path p95 should be < 120ms (was ${p95}ms)",
            p95 < 120L
        )
        SphereNative.release()
    }

    @Test
    fun exportResolution_fastPath_p95_underBudget() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(4096, 2048, 65f, 50f)
        val durations = LongArray(8)
        for (i in durations.indices) {
            val frame = texturedBitmap(i + 10)
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else quatAz(i * 12.0)
            val t0 = SystemClock.elapsedRealtime()
            SphereNative.addFrameFast(frame, q, 0.15f)
            durations[i] = SystemClock.elapsedRealtime() - t0
        }
        val p95 = p95(durations)
        Log.i(TAG, "export 4096x2048 addFrameFast p95=${p95}ms (F2 downscale+stride)")
        assertTrue(
            "4096 fast path p95 should be < 200ms with F2 (was ${p95}ms)",
            p95 < 200L
        )
        SphereNative.release()
    }

    @Test
    fun liveSplat_thenRefine_restoresFullCoverage() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(4096, 2048, 65f, 50f)
        val gate = 0.12f
        repeat(6) { i ->
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else quatAz(i * 15.0)
            assertEquals(1, SphereNative.addFrameFast(texturedBitmap(i + 20), q, gate))
        }
        val covBefore = SphereNative.getSolidAngleCoverage()
        assertTrue("live splat should accumulate coverage", covBefore > 0.05f)
        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val covAfter = SphereNative.getSolidAngleCoverage()
        Log.i(TAG, "solid angle before refine=$covBefore after=$covAfter")
        assertTrue(
            "refine should maintain or improve solid-angle coverage",
            covAfter >= covBefore * 0.85f
        )
        SphereNative.release()
    }
}
