package ab.inque.visiontechai.controller

import ab.inque.robotics.math.LensDistortion
import ab.inque.visiontechai.OrientationTracker
import ab.inque.visiontechai.R
import ab.inque.visiontechai.SphereCaptureController
import ab.inque.visiontechai.SphereExtrinsicCalibration
import ab.inque.visiontechai.SphereStitchOperations
import ab.inque.visiontechai.SphereStitchWorker
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.databinding.ActivityMainBinding
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereSessionControllerSaveTest {

    private class SaveOps : SphereStitchOperations {
        var frames = 0
        override fun init(eqW: Int, eqH: Int, fovH: Float, fovV: Float, distortion: LensDistortion) = true
        override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean {
            frames++
            return true
        }
        override fun getCoverage(): Float = frames * 0.2f
        override fun getSolidAngleCoverage(): Float = frames * 0.15f
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo() = null
        override fun hasRefineWorkPending(applyGlobalPose: Boolean) = false
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(
                eqW.coerceAtMost(SphereCaptureController.EQ_W),
                eqH.coerceAtMost(SphereCaptureController.EQ_H),
                Bitmap.Config.ARGB_8888
            ).apply {
                eraseColor(Color.BLUE)
            }
        override fun preparePreviewResplat(outW: Int, outH: Int): Boolean = true
        override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean = true
        override fun refineSeams(light: Boolean): Boolean = true
        override fun reset() { frames = 0 }
        override fun release() {}
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var ops: SaveOps
    private lateinit var controller: SphereCaptureController
    private lateinit var session: SphereSessionController
    private lateinit var worker: SphereStitchWorker

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_PrismAI)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        ops = SaveOps()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executors.newSingleThreadExecutor()
        worker = SphereStitchWorker(operations = ops, executor = executor)
        controller = SphereCaptureController(
            OrientationTracker(ctx),
            SphereExtrinsicCalibration(ctx).apply { reset() },
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            operations = ops,
            stitchWorker = worker
        )
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        var visionMode = VisionMode.SPHERE
        session = SphereSessionController(
            activity = activity,
            binding = binding,
            orientationTracker = OrientationTracker(ctx),
            setVisionMode = { mode ->
                if (visionMode == VisionMode.SPHERE && mode != VisionMode.SPHERE) {
                    session.onSphereCleanupFromModeSwitch()
                }
                visionMode = mode
            },
            getCameraFovH = { 65f },
            getCameraFovV = { 50f },
            getAnalysisWidth = { 640 },
            getAnalysisHeight = { 480 },
            getDisplayRotationDegrees = { 0 }
        )
        session.controller = controller
        controller.startSession()
    }

    @Test
    fun saveEquirectangular_beforeDone_isRejected() {
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        snapPhoto(raw)
        awaitFrames(1)
        session.saveEquirectangular()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.controller!!.state == SphereCaptureController.State.CAPTURING)
    }

    @Test
    fun saveEquirectangular_keepsSessionAliveThroughModeCleanup() {
        captureFramesAndFinish(2)
        session.saveEquirectangular()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(session.controller)
        assertTrue(session.controller!!.hasCapturedFrames)
        assertTrue(session.controller!!.isFinalized() || ops.frames > 0)
    }

    @Test
    fun cancelCapture_fromFinalizedPreview_returnsToCapturing() {
        captureFramesAndFinish(2)
        session.cancelCapture()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SphereCaptureController.State.CAPTURING, session.controller!!.state)
        assertEquals(View.VISIBLE, binding.sphereCaptureOverlay.visibility)
    }

    @Test
    fun exit_fromFinalizedPreview_dismissesViewer() {
        captureFramesAndFinish(2)
        session.exit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, binding.sphereViewerOverlay.visibility)
        assertEquals(View.GONE, binding.sphereCaptureOverlay.visibility)
    }

    private fun snapPhoto(raw: Bitmap, timestampNanos: Long = 1L) {
        val idleDeadline = System.currentTimeMillis() + 3000
        while (controller.isStitchBusy() && System.currentTimeMillis() < idleDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        session.handleRecordButton(isPhotoMode = true) {}
        assertTrue(session.captureNextFrame)
        session.captureNextFrame = false
        controller.captureFrame(raw, timestampNanos)
    }

    private fun captureFramesAndFinish(count: Int) {
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        repeat(count) {
            snapPhoto(raw, it.toLong())
            awaitFrames(it + 1)
        }
        session.finishCapture()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 30_000
        while (
            session.controller?.isFinalized() != true &&
            System.currentTimeMillis() < deadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "DONE should finalize the session (state=${session.controller?.state})",
            session.controller?.isFinalized() == true
        )
    }

    private fun awaitFrames(count: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (ops.frames < count && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(ops.frames >= count)
    }
}
