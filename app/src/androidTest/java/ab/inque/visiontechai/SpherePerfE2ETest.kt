package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Device-gated performance budgets for the two-phase sphere pipeline.
 * See docs/sphere/SPHERE_FAST_STITCH_PLAN.md for measured baselines and targets.
 */
@RunWith(AndroidJUnit4::class)
class SpherePerfE2ETest {

    private fun texturedBitmap(w: Int, h: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        var s = seed
        for (i in px.indices) {
            s = s * 1103515245 + 12345
            val v = (s ushr 16) and 0xFF
            px[i] = Color.rgb(v, (v * 3) and 0xFF, (v * 7) and 0xFF)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun quatAz(deg: Double) =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(deg))

    /** PG-1: fast-path splat at realistic analysis resolution. */
    @Test
    fun fastPath_addFrameFast_realisticResolution_p95() {
        assumeTrue(SphereNative.isAvailable())
        val w = 640
        val h = 480
        SphereNative.init(4096, 2048, 65f, 50f)
        val durations = LongArray(8)
        for (i in durations.indices) {
            val frame = texturedBitmap(w, h, i + 1)
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else quatAz(i * 12.0)
            val t0 = SystemClock.elapsedRealtime()
            SphereNative.addFrameFast(frame, q, 0.15f)
            durations[i] = SystemClock.elapsedRealtime() - t0
        }
        val sorted = durations.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        Log.i(TAG, "addFrameFast 640x480 p95=${p95}ms samples=${durations.contentToString()}")
        assertTrue("addFrameFast p95 should be < 50ms, was ${p95}ms", p95 < 50)
        SphereNative.release()
    }

    /** PG-2: incremental coverage must be O(1), not a full-buffer scan. */
    @Test
    fun incrementalCoverage_under3ms() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(4096, 2048, 65f, 50f)
        SphereNative.addFrameFast(
            texturedBitmap(640, 480, 1),
            floatArrayOf(0f, 0f, 0f, 1f),
            0.15f
        )
        val samples = LongArray(10)
        for (i in samples.indices) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            SphereNative.getCoverage()
            SphereNative.getSolidAngleCoverage()
            samples[i] = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        }
        val maxUs = samples.max()
        Log.i(TAG, "coverage max=${maxUs}us samples=${samples.contentToString()}")
        assertTrue("coverage queries should be < 3000us, was ${maxUs}us", maxUs < 3000)
        SphereNative.release()
    }

    /** PG-4: end-to-end photo tap latency (what the user perceives). */
    @Test
    fun photoTap_submitToResult_under500ms_p95() {
        assumeTrue(SphereNative.isAvailable())
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        // Long refine delay so refine never fires during the burst (isolates fast path).
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = 60_000L
        )
        ops.init(4096, 2048, 65f, 50f)
        val latencies = mutableListOf<Long>()
        repeat(8) { i ->
            val submitAt = SystemClock.elapsedRealtime()
            val latch = CountDownLatch(1)
            worker.onResult = { r ->
                if (!r.refined) latch.countDown()
            }
            worker.submit(texturedBitmap(640, 480, i + 1), quatAz(i * 10.0))
            assertTrue("tap $i timed out", latch.await(5, TimeUnit.SECONDS))
            val us = SystemClock.elapsedRealtime() - submitAt
            latencies.add(us)
            Log.i(TAG, "tap $i: ${us}ms")
        }
        val sorted = latencies.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        Log.i(TAG, "photo-tap p95=${p95}ms avg=${latencies.average().toLong()}ms")
        assertTrue("photo-tap p95 should be < 500ms, was ${p95}ms", p95 < 500)
        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    @Test
    fun finishGeneration_runsOffMainThread() {
        assumeTrue(SphereNative.isAvailable())
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        ops.init(512, 256, 65f, 50f)
        val latch = CountDownLatch(1)
        var finishResult: SphereFinishResult? = null
        worker.submit(texturedBitmap(160, 120, 1), floatArrayOf(0f, 0f, 0f, 1f))
        Thread.sleep(300)
        worker.runFinishGeneration(512, 256) { result ->
            finishResult = result
            latch.countDown()
        }
        assertTrue("finish generation timed out", latch.await(30, TimeUnit.SECONDS))
        assertNotNull(finishResult)
        assertFalse(
            "Finish generation must not run JNI on main thread",
            finishResult!!.ranOnMainThread
        )
        assertNotNull(finishResult!!.equirect)
        Log.i(TAG, "finish generation ${finishResult!!.durationMs}ms")
        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    @Test
    fun refinePending_noOpWhenNothingDirty_isFast() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrameFast(
            texturedBitmap(160, 120, 1),
            floatArrayOf(0f, 0f, 0f, 1f),
            0.20f
        )
        assertTrue(SphereNative.refinePending())
        val t0 = SystemClock.elapsedRealtime()
        assertFalse("Second refine with no dirty frames should be no-op", SphereNative.refinePending())
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "refine no-op ${elapsed}ms")
        assertTrue("Clean refine should return quickly, was ${elapsed}ms", elapsed < 50)
        SphereNative.release()
    }

    companion object {
        private const val TAG = "SpherePerfE2E"
    }
}
