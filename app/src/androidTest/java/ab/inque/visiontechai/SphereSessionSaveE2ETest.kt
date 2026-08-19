package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.controller.SphereSessionController
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Session-level E2E: inject planar frames, DONE → SAVE through [SphereSessionController].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SphereSessionSaveE2ETest {

    companion object {
        private const val EXPORT_TIMEOUT_SEC = 240L

        @get:ClassRule
        @JvmField
        val grantPermissions = E2ETestRules.grantAllRequiredPermissions()
    }

    private lateinit var activityScenario: ActivityScenario<MainActivity>

    @Before
    fun launchActivity() {
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2000)
    }

    @After
    fun closeActivity() {
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
    }

    @Test(timeout = 180_000)
    fun workflow_injectFrames_doneSave_exportCompletes() {
        assumeTrue(SphereNative.isAvailable())
        assumeFalse("4096 SAVE skipped on emulator", isEmulator())

        val ctrlRef = AtomicReference<SphereCaptureController>()
        val exportLatch = CountDownLatch(1)

        activityScenario.onActivity { activity ->
            val session = sphereSession(activity) ?: error("sphereSession null")
            session.enter()
            ctrlRef.set(session.controller ?: error("controller null"))
        }

        val ctrl = ctrlRef.get() ?: error("controller not set")
        stitchStripeFrames(stitchWorker(ctrl))
        waitForWorkerIdle(ctrl)
        assertTrue(ctrl.finishCapture())
        val previewDeadline = System.currentTimeMillis() + 15_000
        while (ctrl.state != SphereCaptureController.State.PREVIEW && System.currentTimeMillis() < previewDeadline) {
            Thread.sleep(50)
        }
        assertTrue("PREVIEW after DONE", ctrl.state == SphereCaptureController.State.PREVIEW)

        activityScenario.onActivity {
            ctrl.startBackgroundFullExport { result ->
                if (result.equirect != null) exportLatch.countDown()
            }
        }

        assertTrue(
            "Background export did not complete in ${EXPORT_TIMEOUT_SEC}s",
            exportLatch.await(EXPORT_TIMEOUT_SEC, TimeUnit.SECONDS)
        )
        activityScenario.onActivity {
            val equirect = ctrl.getFullEquirect()
            assertNotNull(equirect)
            assertTrue(equirect!!.width >= 512)
        }
    }

    @Test(timeout = 120_000)
    fun workflow_reenterBlockedWhileExportInProgress() {
        assumeTrue(SphereNative.isAvailable())

        val sessionRef = AtomicReference<SphereSessionController>()
        val ctrlRef = AtomicReference<SphereCaptureController>()

        activityScenario.onActivity { activity ->
            val session = sphereSession(activity)!!
            sessionRef.set(session)
            session.enter()
            ctrlRef.set(session.controller!!)
        }

        val session = sessionRef.get()!!
        val ctrl = ctrlRef.get()!!
        stitchStripeFrames(stitchWorker(ctrl))
        waitForWorkerIdle(ctrl)
        ctrl.finishCapture()
        val previewDeadline = System.currentTimeMillis() + 15_000
        while (ctrl.state != SphereCaptureController.State.PREVIEW && System.currentTimeMillis() < previewDeadline) {
            Thread.sleep(50)
        }

        activityScenario.onActivity { session.saveEquirectangular() }

        Thread.sleep(500)
        val exportStarted = ctrl.isFullExportInProgress() || ctrl.isFullExportComplete()
        assumeTrue("Export did not start (device too fast?)", exportStarted)

        activityScenario.onActivity { session.enter() }

        activityScenario.onActivity {
            val stillSame = session.controller != null && session.controller!!.hasCapturedFrames
            assertTrue("Re-enter must not replace the exporting session", stillSame)
        }
    }

    private fun waitForWorkerIdle(ctrl: SphereCaptureController, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ctrl.isStitchBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("stitch worker idle", !ctrl.isStitchBusy())
    }

    private fun sphereSession(activity: MainActivity): SphereSessionController? {
        val field = MainActivity::class.java.getDeclaredField("sphereSession")
        field.isAccessible = true
        return field.get(activity) as? SphereSessionController
    }

    private fun stitchWorker(controller: SphereCaptureController): SphereStitchWorker {
        val field = SphereCaptureController::class.java.getDeclaredField("stitchWorker")
        field.isAccessible = true
        return field.get(controller) as SphereStitchWorker
    }

    private fun stitchStripeFrames(worker: SphereStitchWorker) {
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
    }

    private fun stripeBitmap(): Bitmap {
        val w = 320
        val h = 240
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if ((x / 32) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
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

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)
}
