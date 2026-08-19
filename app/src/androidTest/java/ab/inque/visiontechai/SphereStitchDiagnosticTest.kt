package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Ground-truth timing diagnostics for the two-phase sphere pipeline.
 * Not a pass/fail gate — logs real device numbers under TAG=SphereDiag so we
 * can confirm the fast path is actually fast and locate the bottleneck.
 *
 * Filter logcat:  adb logcat -s SphereDiag
 */
@RunWith(AndroidJUnit4::class)
class SphereStitchDiagnosticTest {

    private fun texturedBitmap(w: Int, h: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        var s = seed
        for (i in px.indices) {
            // cheap deterministic texture so overlap/alignment has real content
            s = s * 1103515245 + 12345
            val v = (s ushr 16) and 0xFF
            px[i] = Color.rgb(v, (v * 3) and 0xFF, (v * 7) and 0xFF)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun quatAz(deg: Double) =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(deg))

    @Test
    fun diag_perFramePhaseTimings_realisticResolution() {
        assumeTrue(SphereNative.isAvailable())
        // Realistic analysis-frame resolution fed to the sphere in photo mode.
        val w = 640
        val h = 480
        SphereNative.init(4096, 2048, 65f, 50f)
        val frames = (0 until 12).map { texturedBitmap(w, h, it + 1) }

        Log.i(TAG, "=== per-frame fast-path (doAlign=false), ${w}x$h into 4096x2048 ===")
        val addMs = LongArray(frames.size)
        val covMs = LongArray(frames.size)
        val previewMs = LongArray(frames.size)
        for ((i, f) in frames.withIndex()) {
            val q = quatAz(i * 12.0)
            var t = SystemClock.elapsedRealtimeNanos()
            SphereNative.addFrameFast(f, q, 0.15f)
            addMs[i] = (SystemClock.elapsedRealtimeNanos() - t) / 1000
            t = SystemClock.elapsedRealtimeNanos()
            SphereNative.getCoverage()
            SphereNative.getSolidAngleCoverage()
            covMs[i] = (SystemClock.elapsedRealtimeNanos() - t) / 1000
            t = SystemClock.elapsedRealtimeNanos()
            SphereNative.getEquirectBitmap(1024, 512)
            previewMs[i] = (SystemClock.elapsedRealtimeNanos() - t) / 1000
            Log.i(
                TAG,
                "frame $i: addFrameFast=${addMs[i]}us coverage=${covMs[i]}us preview1024=${previewMs[i]}us"
            )
        }
        Log.i(TAG, "addFrameFast avg=${addMs.average().toLong()}us max=${addMs.max()}us")
        Log.i(TAG, "coverage    avg=${covMs.average().toLong()}us max=${covMs.max()}us")
        Log.i(TAG, "preview1024 avg=${previewMs.average().toLong()}us max=${previewMs.max()}us")

        Log.i(TAG, "=== refinePending (full re-align + rebuild) ===")
        val t = SystemClock.elapsedRealtimeNanos()
        SphereNative.refinePending()
        Log.i(TAG, "refinePending(12 dirty frames)=${(SystemClock.elapsedRealtimeNanos() - t) / 1000}us")

        SphereNative.release()
    }

    /**
     * The key question: does a fast-path frame BLOCK while a background refine
     * holds the native lock? Measures addFrameFast latency during refine.
     */
    @Test
    fun diag_lockContention_fastPathDuringRefine() {
        assumeTrue(SphereNative.isAvailable())
        val w = 640
        val h = 480
        SphereNative.init(4096, 2048, 65f, 50f)
        // Seed several frames so refinePending has real re-align work.
        for (i in 0 until 10) {
            SphereNative.addFrameFast(texturedBitmap(w, h, i + 1), quatAz(i * 12.0), 0.15f)
        }

        // Baseline fast-path latency with no contention.
        val baseline = LongArray(5)
        for (i in 0 until 5) {
            val q = quatAz(120.0 + i * 5.0)
            val t = SystemClock.elapsedRealtimeNanos()
            SphereNative.addFrameFast(texturedBitmap(w, h, 100 + i), q, 0.0f)
            baseline[i] = (SystemClock.elapsedRealtimeNanos() - t) / 1000
        }
        Log.i(TAG, "baseline addFrameFast (no refine): avg=${baseline.average().toLong()}us")

        // Now fire refinePending on a background thread and hammer fast path.
        val refineDone = CountDownLatch(1)
        val refineStart = SystemClock.elapsedRealtimeNanos()
        thread(name = "diag-refine") {
            SphereNative.refinePending()
            Log.i(TAG, "refine finished at +${(SystemClock.elapsedRealtimeNanos() - refineStart) / 1000}us")
            refineDone.countDown()
        }

        val contended = mutableListOf<Long>()
        var probe = 0
        while (refineDone.count > 0) {
            val q = quatAz(200.0 + probe * 3.0)
            val t = SystemClock.elapsedRealtimeNanos()
            SphereNative.addFrameFast(texturedBitmap(w, h, 200 + probe), q, 0.0f)
            val us = (SystemClock.elapsedRealtimeNanos() - t) / 1000
            contended.add(us)
            probe++
        }
        refineDone.await(30, TimeUnit.SECONDS)
        Log.i(
            TAG,
            "addFrameFast DURING refine: n=${contended.size} " +
                "avg=${if (contended.isEmpty()) 0 else contended.average().toLong()}us " +
                "max=${contended.maxOrNull() ?: 0}us  (samples=$contended)"
        )
        Log.i(
            TAG,
            "VERDICT: if max contended >> baseline, fast path is blocked by refine holding the lock."
        )
        SphereNative.release()
    }

    /**
     * End-to-end via the worker: simulate photo taps and measure how long from
     * submit() until the result callback fires (what the user perceives).
     */
    @Test
    fun diag_endToEnd_workerPhotoTapLatency() {
        assumeTrue(SphereNative.isAvailable())
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        // Disable idle refine during this burst so we measure pure fast-path latency.
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = 60_000L
        )
        ops.init(4096, 2048, 65f, 50f)
        val w = 640
        val h = 480

        val latencies = mutableListOf<Long>()
        // 5 taps is enough for p50/p95; 16 taps × slow refine was hanging CI.
        for (i in 0 until 5) {
            val submitAt = SystemClock.elapsedRealtimeNanos()
            val latch = CountDownLatch(1)
            worker.onResult = { r ->
                if (!r.refined) {
                    latencies.add((SystemClock.elapsedRealtimeNanos() - submitAt) / 1000)
                    latch.countDown()
                }
            }
            worker.submit(texturedBitmap(w, h, i + 1), quatAz(i * 10.0))
            if (!latch.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "tap $i timed out after 10s — refine may be blocking fast path")
                break
            }
            Log.i(TAG, "tap $i: submit->result=${latencies.last()}us")
            Thread.sleep(150) // gap between taps; refine debounce is 900ms so won't fire mid-burst
        }
        Log.i(
            TAG,
            "photo-tap latency: avg=${latencies.average().toLong()}us " +
                "max=${latencies.max()}us p50=${latencies.sorted()[latencies.size / 2]}us"
        )
        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    companion object {
        private const val TAG = "SphereDiag"
    }
}
