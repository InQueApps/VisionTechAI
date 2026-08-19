package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Worker finish path (S38): [SphereStitchWorker.runFinishGeneration] must produce
 * a valid export equirect correlated with the pre-finish panorama.
 */
@RunWith(AndroidJUnit4::class)
class SphereFinishE2ETest {

    companion object {
        private const val TAG = "SphereFinishE2E"
        private const val EQ_W = 512
        private const val EQ_H = 256
        private const val FRAME_W = 320
        private const val FRAME_H = 240
        private const val OVERLAP_GATE = 0.10f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
        System.gc()
    }

    private fun stripeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pixels[y * FRAME_W + x] =
                    if ((x / 32) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
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

    @Test
    fun finish_workerPath_producesExportEquirect() {
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = 60_000L
        )
        ops.init(EQ_W, EQ_H, 65f, 50f)

        val frame = stripeBitmap()
        repeat(6) { i ->
            val bmp = if (i == 0) frame else shiftBitmap(frame, 5 + i)
            val q = if (i == 0) {
                floatArrayOf(0f, 0f, 0f, 1f)
            } else {
                QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 12.0))
            }
            val latch = CountDownLatch(1)
            worker.onResult = { if (!it.refined) latch.countDown() }
            worker.submit(bmp, q, photoMode = true)
            assertTrue("frame $i timed out", latch.await(8, TimeUnit.SECONDS))
        }

        val preFinish = ops.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(preFinish)

        val finishLatch = CountDownLatch(1)
        var finish: SphereFinishResult? = null
        worker.runFinishGeneration(EQ_W, EQ_H) { result ->
            finish = result
            finishLatch.countDown()
        }
        assertTrue("finish timed out", finishLatch.await(120, TimeUnit.SECONDS))

        assertNotNull(finish)
        assertNotNull(finish!!.equirect)
        assertTrue(
            "full-align finish may take longer (was ${finish.durationMs}ms)",
            finish.durationMs < 120_000
        )

        val region = SphereImageMetrics.requireContentRegion(preFinish!!, "preFinish")
        val band = SphereImageMetrics.overlapBandRegion(region)
        val ghostBefore = SphereImageMetrics.ghostingScore(preFinish, band)
        val ghostAfter = SphereImageMetrics.ghostingScore(finish.equirect!!, band)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostBefore, ghostAfter)
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(
            preFinish, finish.equirect!!, band
        )
        val sharpness = SphereImageMetrics.laplacianVariance(finish.equirect!!, region)
        Log.i(
            TAG,
            "finish band ghost delta=$ghostDelta corr=$correlation sharpness=$sharpness " +
                "durationMs=${finish.durationMs} emulator=${isEmulator()}"
        )

        if (isEmulator()) {
            assertNotNull(finish.equirect)
            assertTrue("Finish equirect must retain stripe detail (lap=$sharpness)", sharpness > 8.0)
        } else {
            assertTrue(
                "Finish export must match preview ≥ ${SphereRefinePolicy.minFinishVsPreviewCorrelation()} (was $correlation)",
                correlation >= SphereRefinePolicy.minFinishVsPreviewCorrelation()
            )
            assertTrue(
                "Finish export ghost delta must be ≤ ${SphereRefinePolicy.maxFinishExportGhostDelta()} (delta=$ghostDelta)",
                ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta()
            )
            assertTrue("Finish equirect must retain stripe detail (lap=$sharpness)", sharpness > 8.0)
        }

        worker.shutdown()
        executor.shutdown()
        ops.release()
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true)
}
