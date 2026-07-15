package ab.inque.visiontechai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereCaptureControllerTest {

    private class FakeOps : SphereStitchOperations {
        var solidAngle = 0.1f
        var areaCoverage = 0.1f
        var addCount = 0

        override fun init(
            eqW: Int,
            eqH: Int,
            fovH: Float,
            fovV: Float,
            distortion: LensDistortion
        ) = true

        override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean {
            addCount++
            return true
        }

        override fun getCoverage(): Float = areaCoverage
        override fun getSolidAngleCoverage(): Float = solidAngle
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo(): FrameAlignmentInfo? =
            FrameAlignmentInfo(addCount, 50, 200, 1f, 100f, false)
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        override fun reset() {
            addCount = 0
            solidAngle = 0.1f
            areaCoverage = 0.1f
        }
        override fun release() {}
    }

    private lateinit var orientationTracker: OrientationTracker
    private lateinit var calibration: SphereExtrinsicCalibration
    private lateinit var ops: FakeOps
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var worker: SphereStitchWorker
    private lateinit var controller: SphereCaptureController
    private var lastArea = -1f
    private var lastSolid = -1f
    private var previewReady = false

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        orientationTracker = OrientationTracker(ctx)
        calibration = SphereExtrinsicCalibration(ctx).apply { reset() }
        ops = FakeOps()
        executor = Executors.newSingleThreadExecutor()
        worker = SphereStitchWorker(operations = ops, executor = executor)
        controller = SphereCaptureController(
            orientationTracker,
            calibration,
            onCoverageUpdate = { area, solid, _ ->
                lastArea = area
                lastSolid = solid
            },
            onPreviewReady = { previewReady = true },
            stitchWorker = worker,
            operations = ops
        )
    }

    @Test
    fun startSession_resetsCoverageAndEntersCapturing() {
        controller.startSession()
        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        assertEquals(0f, controller.lastCoverage, 0.001f)
        assertEquals(0f, controller.lastSolidAngleCoverage, 0.001f)
    }

    @Test
    fun captureFrame_ignoredWhenNotCapturing() {
        val bmp = solidBitmap()
        controller.captureFrame(bmp)
        assertEquals(0, ops.addCount)
    }

    @Test
    fun stitchResult_updatesDualCoverage() {
        controller.startSession()
        ops.areaCoverage = 0.42f
        ops.solidAngle = 0.35f
        submitFrameAndAwait()
        assertEquals(0.42f, controller.lastCoverage, 0.001f)
        assertEquals(0.35f, controller.lastSolidAngleCoverage, 0.001f)
        assertEquals(0.42f, lastArea, 0.001f)
        assertEquals(0.35f, lastSolid, 0.001f)
    }

    @Test
    fun finishCapture_blockedBelow85PercentSolidAngle() {
        controller.startSession()
        ops.solidAngle = 0.5f
        submitFrameAndAwait()
        assertFalse(controller.finishCapture())
        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        assertFalse(previewReady)
    }

    @Test
    fun finishCapture_succeedsAt85PercentSolidAngle() {
        controller.startSession()
        ops.solidAngle = 0.9f
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SphereCaptureController.State.PREVIEW, controller.state)
        assertTrue(previewReady)
    }

    @Test
    fun resetSession_returnsToIdle() {
        controller.startSession()
        ops.solidAngle = 0.9f
        submitFrameAndAwait()
        controller.resetSession()
        assertEquals(SphereCaptureController.State.IDLE, controller.state)
        assertEquals(0f, controller.lastSolidAngleCoverage, 0.001f)
    }

    @Test
    fun stitchResult_deliversPreviewInCallback() {
        var preview: Bitmap? = null
        val ctrl = SphereCaptureController(
            orientationTracker,
            calibration,
            onCoverageUpdate = { _, _, p -> preview = p },
            onPreviewReady = {},
            stitchWorker = worker,
            operations = ops
        )
        ctrl.startSession()
        ctrl.captureFrame(solidBitmap())
        val deadline = System.currentTimeMillis() + 3000
        while (preview == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertNotNull(preview)
    }

    @Test
    fun continuousCapture_stationary_skipsFrame() {
        val ctrl = SphereCaptureController(
            orientationTracker,
            calibration,
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            stitchWorker = worker,
            operations = ops
        )
        ctrl.startSession()
        ctrl.startContinuousCapture()
        val bmp = solidBitmap()
        ctrl.feedContinuousFrame(bmp, 0L)
        ctrl.feedContinuousFrame(bmp, 50_000_000L)
        assertEquals(0, ops.addCount)
    }

    private fun submitFrameAndAwait(shutdownExecutor: Boolean = true) {
        controller.captureFrame(solidBitmap())
        val deadline = System.currentTimeMillis() + 3000
        while (controller.lastCoverage == 0f && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        if (shutdownExecutor) {
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun solidBitmap(): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
}
