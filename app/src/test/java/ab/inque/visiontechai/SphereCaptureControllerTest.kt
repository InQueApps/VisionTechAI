package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.FrameAlignmentInfo


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private open class FakeOps : SphereStitchOperations {
        var solidAngle = 0.1f
        var areaCoverage = 0.1f
        var addCount = 0
        var sessionK1 = 0f

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
        open override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(
                eqW.coerceAtMost(SphereCaptureController.EQ_W),
                eqH.coerceAtMost(SphereCaptureController.EQ_H),
                Bitmap.Config.ARGB_8888
            )
        override fun refinePending(applyGlobalPose: Boolean): Boolean = true
        override fun refineGlobalPose(): Boolean = true
        override fun refineIntrinsics(): Boolean = true
        override fun refineStoredPoses(): Boolean = true
        override fun rebuildFullAlign(): Boolean = true
        override fun refineSeams(light: Boolean): Boolean = true
        override fun preparePreviewResplat(outW: Int, outH: Int): Boolean = true
        override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean = true
        override fun flushPendingFrameStores() {}
        override fun setPoseGraphOnlyGlobal(enabled: Boolean) {}
        override fun setFastFinishRefine(enabled: Boolean) {}
        override fun setGlobalRefinePasses(passes: Int) {}
        override fun buildFeatureGraphCache(): Boolean = false
        override fun getSessionDistortionK1(): Float = sessionK1
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
    fun captureFrame_setsExportSizeFromInputAngularResolution() {
        controller.startSession()
        val bmp = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        controller.captureFrame(bmp)
        val expected = SphereExportSize.fromFrame(1080, 1080, 65f, 50f)
        assertEquals(expected.width, controller.exportWidth)
        assertEquals(expected.height, controller.exportHeight)
        assertTrue(controller.exportWidth > SphereCaptureController.EQ_W)
        bmp.recycle()
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
    fun finishCapture_allowedAtAnyCoverage() {
        controller.startSession()
        ops.solidAngle = 0.1f
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val deadline = System.currentTimeMillis() + 5000
        while (!previewReady && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.isPreviewSession())
        assertTrue(previewReady)
    }

    @Test
    fun finishCapture_succeedsAt85PercentSolidAngle() {
        controller.startSession()
        ops.solidAngle = 0.9f
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val deadline = System.currentTimeMillis() + 5000
        while (!previewReady && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val finalizeDeadline = System.currentTimeMillis() + 30_000
        while (!controller.isFinalized() && System.currentTimeMillis() < finalizeDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SphereCaptureController.State.PREVIEW_FINALIZED, controller.state)
        assertTrue(previewReady)
        assertNotNull(controller.getFullEquirect())
    }

    @Test
    fun resumeCapture_fromPreview_returnsToCapturing() {
        controller.startSession()
        ops.solidAngle = 0.5f
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val deadline = System.currentTimeMillis() + 5000
        while (!controller.isPreviewSession() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.isPreviewSession())
        assertTrue(controller.resumeCapture())
        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
    }

    @Test
    fun resumeCapture_fromCapturing_isNoOp() {
        controller.startSession()
        assertFalse(controller.resumeCapture())
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
    fun finishCapture_notifiesIntrinsicsConverged() {
        var persisted: LensDistortion? = null
        previewReady = false
        ops.sessionK1 = -0.06f
        controller = SphereCaptureController(
            orientationTracker,
            calibration,
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = { previewReady = true },
            onIntrinsicsConverged = { persisted = it },
            stitchWorker = worker,
            operations = ops
        )
        controller.startSession()
        submitFrameAndAwait(shutdownExecutor = false)
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val previewDeadline = System.currentTimeMillis() + 5000
        while (!previewReady && System.currentTimeMillis() < previewDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(controller.startBackgroundFullExport())
        val deadline = System.currentTimeMillis() + 30_000
        while (
            (!controller.isFullExportComplete() || persisted == null) &&
            System.currentTimeMillis() < deadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.isFullExportComplete())
        assertNotNull(persisted)
        assertEquals(-0.06f, persisted!!.k1, 0.001f)
    }

    @Test
    fun startBackgroundFullExport_deliversQueuedCallbacks() {
        controller.startSession()
        submitFrameAndAwait(shutdownExecutor = false)
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val previewDeadline = System.currentTimeMillis() + 5000
        while (!controller.isPreviewSession() &&
            System.currentTimeMillis() < previewDeadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        var first = false
        var second = false
        assertTrue(controller.startBackgroundFullExport { first = true })
        assertTrue(controller.startBackgroundFullExport { second = true })
        val deadline = System.currentTimeMillis() + 30_000
        while ((!first || !second) && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(first)
        assertTrue(second)
    }

    @Test
    fun restartCapture_clearsFramesAndReturnsToCapturing() {
        controller.startSession()
        ops.solidAngle = 0.5f
        submitFrameAndAwait()
        assertTrue(controller.lastSolidAngleCoverage > 0f)

        controller.restartCapture()

        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        assertEquals(0f, controller.lastCoverage, 0.001f)
        assertEquals(0f, controller.lastSolidAngleCoverage, 0.001f)
        assertEquals(null, controller.getPreviewBitmap())
        assertEquals(null, controller.getFullEquirect())
    }

    @Test
    fun restartCapture_duringFinalization_staysCapturing() {
        controller.startSession()
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        assertTrue(controller.restartCapture())
        assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        repeat(10) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            assertEquals(SphereCaptureController.State.CAPTURING, controller.state)
        }
    }

    @Test
    fun ensureFullResExportBitmap_rejectsUndersizedNativeBuffer() {
        val undersized = object : FakeOps() {
            override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
                Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        }
        val undersizedWorker = SphereStitchWorker(
            operations = undersized,
            executor = Executors.newSingleThreadExecutor()
        )
        val ctrl = SphereCaptureController(
            orientationTracker,
            calibration,
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            stitchWorker = undersizedWorker,
            operations = undersized
        )
        ctrl.startSession()
        ctrl.captureFrame(solidBitmap())
        undersizedWorker.awaitStitchIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(ctrl.ensureFullResExportBitmap())
        undersizedWorker.shutdown()
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

    @Test
    fun release_deliversPendingExportCallbacks() {
        controller.startSession()
        submitFrameAndAwait(shutdownExecutor = false)
        submitFrameAndAwait(shutdownExecutor = false)
        assertTrue(controller.finishCapture())
        val previewDeadline = System.currentTimeMillis() + 5000
        while (!controller.isPreviewSession() &&
            System.currentTimeMillis() < previewDeadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        var exportDelivered = false
        assertTrue(controller.startBackgroundFullExport { exportDelivered = true })
        controller.release()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Release must deliver pending export callbacks", exportDelivered)
    }

    private fun submitFrameAndAwait(shutdownExecutor: Boolean = true) {
        controller.captureFrame(solidBitmap())
        val deadline = System.currentTimeMillis() + 3000
        while (controller.lastCoverage == 0f && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        worker.awaitStitchIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (shutdownExecutor) {
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun solidBitmap(): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
}
