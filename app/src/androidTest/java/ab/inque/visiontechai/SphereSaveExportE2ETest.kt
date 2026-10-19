package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production DONE → SAVE on device: [SphereCaptureController.finishCapture] then
 * [SphereCaptureController.startBackgroundFullExport].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SphereSaveExportE2ETest {

    companion object {
        private const val TAG = "SphereSaveExportE2E"
        private const val EQ_W = 512
        private const val EQ_H = 256
        private const val FRAME_W = 320
        private const val FRAME_H = 240
    }

    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.reset()
        SphereNative.release()
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
        System.gc()
    }

    @Test(timeout = 180_000)
    fun doneThenFinish_producesExportEquirect() {
        val controller = stitchStripesToPreview()
        val worker = stitchWorker(controller)
        val preFinish = SphereNativeOperations.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(preFinish)
        val preBmp = preFinish!!

        val exportLatch = CountDownLatch(1)
        var exportResult: SphereFinishResult? = null
        worker.runFinishGeneration(EQ_W, EQ_H) { result ->
            exportResult = result
            exportLatch.countDown()
        }
        assertTrue("finish timed out", exportLatch.await(120, TimeUnit.SECONDS))

        val finish = exportResult ?: error("export result null")
        assertNotNull(finish.equirect)
        assertEquals(EQ_W, finish.equirect!!.width)
        assertEquals(EQ_H, finish.equirect!!.height)

        val region = SphereImageMetrics.requireContentRegion(preBmp, "preFinish")
        val band = SphereImageMetrics.overlapBandRegion(region)
        val ghostBefore = SphereImageMetrics.ghostingScore(preBmp, band)
        val ghostAfter = SphereImageMetrics.ghostingScore(finish.equirect!!, band)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostBefore, ghostAfter)
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(
            preBmp, finish.equirect!!, band
        )
        val sharpness = SphereImageMetrics.laplacianVariance(finish.equirect!!, region)
        Log.i(
            TAG,
            "DONE→finish corr=${"%.4f".format(correlation)} ghostDelta=$ghostDelta " +
                "sharpness=$sharpness durationMs=${finish.durationMs}"
        )
        assertTrue("Finish equirect must retain stripe detail (lap=$sharpness)", sharpness > 8.0)
        if (!isEmulator()) {
            assertTrue(
                "Finish export must match preview ≥ ${SphereRefinePolicy.minFinishVsPreviewCorrelation()} (was $correlation)",
                correlation >= SphereRefinePolicy.minFinishVsPreviewCorrelation()
            )
        }
        controller.release()
    }

    @Test(timeout = 600_000)
    fun doneThenSave_produces4096Export() {
        assumeFalse("4096 export skipped on emulator", isEmulator())
        val controller = stitchStripesToPreview()

        val exportLatch = CountDownLatch(1)
        var exportResult: SphereFinishResult? = null
        assertTrue(
            controller.startBackgroundFullExport { result ->
                exportResult = result
                exportLatch.countDown()
            }
        )
        assertTrue("export timed out", exportLatch.await(480, TimeUnit.SECONDS))

        val finish = exportResult ?: error("export result null")
        assertNotNull(finish.equirect)
        assertEquals(controller.exportWidth, finish.equirect!!.width)
        assertEquals(controller.exportHeight, finish.equirect!!.height)
        assertTrue(controller.isFullExportComplete())
        controller.release()
    }

    @Test
    fun saveBeforeDone_isRejected() {
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = SphereNativeOperations, executor = executor)
        val controller = SphereCaptureController(
            orientationTracker = OrientationTracker(appContext),
            extrinsicCalibration = SphereExtrinsicCalibration(appContext).apply { reset() },
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            operations = SphereNativeOperations,
            stitchWorker = worker
        )
        controller.startSession()
        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        assertFalse(controller.startBackgroundFullExport())
        controller.release()
        worker.shutdown()
        executor.shutdown()
    }

    @Test(timeout = 600_000)
    fun queuedSaveCallbacks_bothReceiveExport() {
        assumeFalse("4096 export skipped on emulator", isEmulator())
        val controller = stitchStripesToPreview()

        val received = AtomicInteger(0)
        val latch = CountDownLatch(2)
        assertTrue(controller.startBackgroundFullExport {
            received.incrementAndGet()
            latch.countDown()
        })
        assertTrue(controller.startBackgroundFullExport {
            received.incrementAndGet()
            latch.countDown()
        })
        assertTrue(latch.await(480, TimeUnit.SECONDS))
        assertEquals(2, received.get())
        assertNotNull(controller.getFullEquirect())
        controller.release()
    }

    private fun stitchStripesToPreview(): SphereCaptureController {
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = SphereNativeOperations,
            executor = executor,
            refineDelayMs = 60_000L
        )
        val controller = SphereCaptureController(
            orientationTracker = OrientationTracker(appContext),
            extrinsicCalibration = SphereExtrinsicCalibration(appContext).apply { reset() },
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            operations = SphereNativeOperations,
            stitchWorker = worker
        )
        controller.startSession()
        val frame = stripeBitmap()
        repeat(6) { i ->
            val bmp = if (i == 0) frame else shiftBitmap(frame, 5 + i)
            val q = if (i == 0) {
                floatArrayOf(0f, 0f, 0f, 1f)
            } else {
                QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 12.0))
            }
            val latch = CountDownLatch(1)
            val prior = worker.onResult
            worker.onResult = { result ->
                prior?.invoke(result)
                if (!result.refined) latch.countDown()
            }
            worker.submit(bmp, q, photoMode = true)
            val deadline = System.currentTimeMillis() + 15_000
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue("frame $i timed out", System.currentTimeMillis() < deadline)
            }
        }
        assertTrue(controller.hasCapturedFrames)
        assertTrue(controller.finishCapture())
        val previewDeadline = SystemClock.elapsedRealtime() + 480_000L
        while (
            !controller.isFinalized() &&
            SystemClock.elapsedRealtime() < previewDeadline
        ) {
            Thread.sleep(50)
        }
        assertTrue(controller.isFinalized())
        return controller
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

    private fun stitchWorker(controller: SphereCaptureController): SphereStitchWorker {
        val field = SphereCaptureController::class.java.getDeclaredField("stitchWorker")
        field.isAccessible = true
        return field.get(controller) as SphereStitchWorker
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)
}
