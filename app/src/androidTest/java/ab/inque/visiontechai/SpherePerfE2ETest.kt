package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
 * See docs/sphere/SPHERE_FAST_STITCH_PLAN.md and SPHERE_NON_PINHOLE_MOTION_PLAN.md.
 *
 * Phase G (warp field) adds cost only on [refinePending] / finish — fast path stays identity.
 */
@RunWith(AndroidJUnit4::class)
class SpherePerfE2ETest {

    companion object {
        private const val TAG = "SpherePerfE2E"

        // PG-1: fast-path splat + F2 downscale + stride on large equirect.
        private const val FAST_PATH_P95_MS = 200L

        // PG-G1: background refine with rotational align + per-cell warp + recency composite.
        // Plan: < 3 s / 10 frames on SD845-class; 8 s leaves headroom for slower devices.
        private const val WARP_REFINE_10_FRAME_BUDGET_MS = 8_000L

        // PG-G2: worker debounced refine (align + warp rebuild + preview).
        private const val WORKER_WARP_REFINE_BUDGET_MS = 10_000L

        private const val EQ_W = 4096
        private const val EQ_H = 2048
        private const val FRAME_W = 640
        private const val FRAME_H = 480
    }

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

    private fun p95(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
    }

    private fun seedOverlappingFrames(count: Int, w: Int = FRAME_W, h: Int = FRAME_H) {
        for (i in 0 until count) {
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else quatAz(i * 12.0)
            SphereNative.addFrameFast(texturedBitmap(w, h, i + 1), q, 0.15f)
        }
    }

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
        assertTrue("addFrameFast p95 should be < ${FAST_PATH_P95_MS}ms, was ${p95}ms", p95 < FAST_PATH_P95_MS)
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
            refineDelayMs = 60_000L,
            periodicRefineEvery = 999
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

    /** S25: [SphereStitchTelemetry] p95 + reject rate on worker fast path. */
    @Test
    fun workerTelemetry_p95AndRejectRate_withinBudget() {
        assumeTrue(SphereNative.isAvailable())
        SphereStitchTelemetry.reset()
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = 60_000L,
            periodicRefineEvery = 999
        )
        ops.init(1024, 512, 65f, 50f)
        repeat(10) { i ->
            val latch = CountDownLatch(1)
            worker.onResult = { if (!it.refined) latch.countDown() }
            worker.submit(
                texturedBitmap(640, 480, i + 1),
                quatAz(i * 10.0),
                photoMode = true
            )
            assertTrue("frame $i timed out", latch.await(8, TimeUnit.SECONDS))
        }
        SphereStitchTelemetry.logSummary("perfE2E")
        val p95 = SphereStitchTelemetry.p95LatencyMs()
        val reject = SphereStitchTelemetry.rejectRate()
        Log.i(TAG, "telemetry p95=${p95}ms rejectRate=$reject accepted=${SphereStitchTelemetry.acceptedFrames()}")
        val p95Budget = maxOf(
            SphereRefinePolicy.maxAddFrameP95Ms() * 3,
            FAST_PATH_P95_MS
        )
        assertTrue(
            "worker p95 should be ≤ ${p95Budget}ms, was ${p95}ms",
            p95 <= p95Budget
        )
        assertTrue(
            "telemetry must record stitch samples",
            SphereStitchTelemetry.acceptedFrames() + SphereStitchTelemetry.rejectedFrames() >= 8
        )
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

    /** PG-G1: refinePending with Phase G warp on realistic session size. */
    @Test
    fun warpRefine_refinePending_tenFrames_withinBudget() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        seedOverlappingFrames(10)

        val t0 = SystemClock.elapsedRealtime()
        assertTrue("Refine must run with dirty frames", SphereNative.refinePending())
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "warp refinePending(10 frames)=${elapsed}ms")

        assertTrue(
            "warp refinePending(10 frames) should be < ${WARP_REFINE_10_FRAME_BUDGET_MS}ms, was ${elapsed}ms",
            elapsed < WARP_REFINE_10_FRAME_BUDGET_MS
        )

        // Verify warp actually ran (second call is no-op).
        val noopT0 = SystemClock.elapsedRealtime()
        assertFalse(SphereNative.refinePending())
        val noopElapsed = SystemClock.elapsedRealtime() - noopT0
        assertTrue("post-refine no-op should be fast, was ${noopElapsed}ms", noopElapsed < 50)

        SphereNative.release()
    }

    /**
     * PG-G1b: fast path must stay within budget even when frames are queued for
     * background warp refine (warp estimation does NOT run on addFrameFast).
     */
    @Test
    fun warpRefine_fastPath_p95_afterFramesQueuedForRefine() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        seedOverlappingFrames(8)

        val durations = mutableListOf<Long>()
        repeat(6) { i ->
            val t0 = SystemClock.elapsedRealtime()
            SphereNative.addFrameFast(
                texturedBitmap(FRAME_W, FRAME_H, 100 + i),
                quatAz(96.0 + i * 8.0),
                0.15f
            )
            durations.add(SystemClock.elapsedRealtime() - t0)
        }
        val p95 = p95(durations)
        Log.i(TAG, "addFrameFast after queued refine p95=${p95}ms samples=$durations")
        assertTrue(
            "fast path p95 should stay < ${FAST_PATH_P95_MS}ms with pending warp refine, was ${p95}ms",
            p95 < FAST_PATH_P95_MS
        )
        SphereNative.release()
    }

    /** PG-G2: debounced worker background refine (align + warp + preview). */
    @Test
    fun warpRefine_workerBackgroundRefine_withinBudget() {
        assumeTrue(SphereNative.isAvailable())
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = 400L
        )
        ops.init(EQ_W, EQ_H, 65f, 50f)

        val refineLatch = CountDownLatch(1)
        var refineDurationMs = -1L
        var refineOnMain = true

        // Long debounce so refine fires only after all frames are submitted.
        worker.onResult = { r ->
            if (r.refined) {
                refineDurationMs = r.durationMs
                refineOnMain = r.ranOnMainThread
                refineLatch.countDown()
            }
        }

        repeat(8) { i ->
            val tapLatch = CountDownLatch(1)
            worker.onResult = { r ->
                if (r.refined) {
                    refineDurationMs = r.durationMs
                    refineOnMain = r.ranOnMainThread
                    refineLatch.countDown()
                } else {
                    tapLatch.countDown()
                }
            }
            worker.submit(texturedBitmap(FRAME_W, FRAME_H, i + 1), quatAz(i * 12.0))
            assertTrue("fast tap $i timed out", tapLatch.await(8, TimeUnit.SECONDS))
        }

        val waitStart = SystemClock.elapsedRealtime()
        assertTrue(
            "background warp refine timed out",
            refineLatch.await(WORKER_WARP_REFINE_BUDGET_MS, TimeUnit.MILLISECONDS)
        )
        val totalWaitMs = SystemClock.elapsedRealtime() - waitStart
        Log.i(TAG, "worker warp refine duration=${refineDurationMs}ms wait=${totalWaitMs}ms")

        assertTrue(
            "worker warp refine should finish < ${WORKER_WARP_REFINE_BUDGET_MS}ms " +
                "(duration=${refineDurationMs}ms wait=${totalWaitMs}ms)",
            refineDurationMs in 1..WORKER_WARP_REFINE_BUDGET_MS
        )
        assertFalse("worker warp refine must not run on main thread", refineOnMain)

        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    /** PG-G4: finish generation path includes warp refine and stays off main thread. */
    @Test
    fun warpRefine_finishGeneration_withinBudget_offMainThread() {
        assumeTrue(SphereNative.isAvailable())
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor, refineDelayMs = 60_000L)
        ops.init(EQ_W, EQ_H, 65f, 50f)

        repeat(6) { i ->
            val latch = CountDownLatch(1)
            worker.onResult = { if (!it.refined) latch.countDown() }
            worker.submit(texturedBitmap(FRAME_W, FRAME_H, i + 1), quatAz(i * 14.0))
            assertTrue("seed frame $i timed out", latch.await(5, TimeUnit.SECONDS))
        }

        val finishLatch = CountDownLatch(1)
        var finish: SphereFinishResult? = null
        val t0 = SystemClock.elapsedRealtime()
        worker.runFinishGeneration(1024, 512) { result ->
            finish = result
            finishLatch.countDown()
        }
        assertTrue("finish generation timed out", finishLatch.await(30, TimeUnit.SECONDS))
        val elapsed = SystemClock.elapsedRealtime() - t0

        assertNotNull(finish)
        assertFalse("finish must not run JNI on main thread", finish!!.ranOnMainThread)
        assertNotNull(finish!!.equirect)
        Log.i(TAG, "finish with warp refine=${elapsed}ms (reported=${finish!!.durationMs}ms)")
        assertTrue(
            "finish generation with warp refine should be < ${WORKER_WARP_REFINE_BUDGET_MS}ms, was ${elapsed}ms",
            elapsed < WORKER_WARP_REFINE_BUDGET_MS
        )

        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    /** PG-R3: global refine must measurably lower overlap-bank SSD on a noisy session. */
    @Test
    fun refineGlobalPose_lowersOverlapBankSSD() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(1024, 512, 65f, 50f)

        val frame = texturedBitmap(FRAME_W, FRAME_H, 42)
        val gate = 0.08f
        val baseQ = floatArrayOf(0f, 0f, 0f, 1f)
        val noisyPairs = listOf(
            6 to QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(14.0 + 2.5)),
            7 to QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(28.0 - 3.0)),
            5 to QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(42.0 + 4.0)),
            8 to QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(56.0 - 2.0))
        )

        SphereNative.addFrameFast(frame, baseQ, gate)
        for ((shift, q) in noisyPairs) {
            val shifted = shiftBitmap(frame, shift)
            val nearness = SphereMotionDepth.gradientNearnessHeuristic(shifted)
            SphereNative.addFrameFast(shifted, q, gate, nearness)
        }

        assertTrue(
            "overlap bank should be populated",
            SphereNative.getOverlapBankSize() >= 20
        )
        val ssdBefore = SphereNative.evalOverlapBankSSD()
        assertTrue("SSD before refine must be finite", ssdBefore < 1e10f)

        assertTrue("global refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val ssdAfter = SphereNative.evalOverlapBankSSD()
        val stats = SphereNative.getLastRefineStats()
        assertNotNull(stats)
        assertTrue("telemetry must record global refine", stats!!.applyGlobalPose)
        assertEquals(ssdBefore, stats.ssdBefore, ssdBefore * 0.01f)
        assertEquals(ssdAfter, stats.ssdAfter, ssdAfter * 0.01f + 1e-3f)

        val improvement = (ssdBefore - ssdAfter) / ssdBefore.coerceAtLeast(1e-6f)
        Log.i(
            TAG,
            "PG-R3 SSD before=$ssdBefore after=$ssdAfter improvement=${"%.3f".format(improvement)} stats=$stats"
        )
        assertTrue(
            "Global refine SSD must drop ≥ ${SphereRefinePolicy.minRefineSsdImprovementRatio()} " +
                "(before=$ssdBefore after=$ssdAfter)",
            ssdAfter <= ssdBefore * (1.0 - SphereRefinePolicy.minRefineSsdImprovementRatio()).toFloat() ||
                ssdAfter < ssdBefore
        )

        SphereNative.release()
    }

    private fun shiftBitmap(src: Bitmap, dx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) dstPx[y * w + x] = srcPx[y * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }
}
