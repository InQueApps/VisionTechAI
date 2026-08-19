package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.FrameAlignmentInfo


import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.controller.PipelineResultDispatcher
import ab.inque.visiontechai.ui.VisionOverlayHost
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PipelineResultDispatcherSphereTest {

    private class FakeOps : SphereStitchOperations {
        var captureCount = 0
        override fun init(
            eqW: Int,
            eqH: Int,
            fovH: Float,
            fovV: Float,
            distortion: LensDistortion
        ) = true
        override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean {
            captureCount++
            return true
        }
        override fun getCoverage(): Float = 0.6f
        override fun getSolidAngleCoverage(): Float = 0.45f
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo(): FrameAlignmentInfo? = null
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        override fun reset() { captureCount = 0 }
        override fun release() {}
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var overlayHost: VisionOverlayHost
    private lateinit var visionState: VisionState
    private lateinit var ops: FakeOps
    private lateinit var sphereController: SphereCaptureController
    private var captureNext = false

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_PrismAI)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        val parent = ConstraintLayout(context)
        parent.layoutParams = ConstraintLayout.LayoutParams(1080, 1920)
        overlayHost = VisionOverlayHost(parent, ConstraintLayout.LayoutParams.PARENT_ID)
        visionState = VisionState(mode = VisionMode.SPHERE)
        ops = FakeOps()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        sphereController = SphereCaptureController(
            OrientationTracker(ctx),
            SphereExtrinsicCalibration(ctx).apply { reset() },
            onCoverageUpdate = { _, _, _ -> },
            onPreviewReady = {},
            operations = ops,
            stitchWorker = worker
        )
        sphereController.startSession()
        sphereController.captureFrame(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        )
        awaitStitchIdle()
    }

    private fun awaitStitchIdle() {
        val deadline = System.currentTimeMillis() + 3000
        while (sphereController.lastCoverage == 0f && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun dispatch_sphereMode_updatesCoverageTextAndPreview() {
        val dispatcher = createDispatcher()
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        dispatcher.dispatch(pipelineResult(raw, ts = 1L))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            SphereCoverageFormatter.format(0.6f, 0.45f),
            binding.sphereCoverageText.text
        )
        assertNotNull(binding.spherePreviewImage.drawable)
        assertNotNull(binding.sphereCameraFeed.drawable)
    }

    @Test
    fun dispatch_sphereMode_showsCenterSquareOnCameraFeed() {
        val dispatcher = createDispatcher()
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        dispatcher.dispatch(pipelineResult(raw, ts = 2L))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val drawable = binding.sphereCameraFeed.drawable as? BitmapDrawable
        assertNotNull(drawable)
        assertEquals(12, drawable!!.bitmap.width)
        assertEquals(12, drawable.bitmap.height)
    }

    @Test
    fun dispatch_captureNextFrame_triggersSingleCapture() {
        val before = ops.captureCount
        captureNext = true
        val raw = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        createDispatcher().dispatch(pipelineResult(raw))
        assertFalse(captureNext)
        // Capture runs on the stitch worker's background executor; await it.
        val deadline = System.currentTimeMillis() + 3000
        while (ops.captureCount < before + 1 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(before + 1, ops.captureCount)
    }

    @Test
    fun dispatch_nonSphereMode_skipsSphereBranch() {
        visionState.mode = VisionMode.OBJECT_DETECTION
        val before = ops.captureCount
        val raw = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        createDispatcher().dispatch(pipelineResult(raw))
        assertEquals(before, ops.captureCount)
    }

    private fun createDispatcher(): PipelineResultDispatcher =
        PipelineResultDispatcher(
            visionState = visionState,
            overlayHost = overlayHost,
            binding = binding,
            sphereControllerProvider = { sphereController },
            sphereCaptureNextFrame = { captureNext },
            clearSphereCaptureNextFrame = { captureNext = false },
            isRecordingProvider = { false },
            processedVideoRecorderProvider = { null }
        )

    private fun pipelineResult(
        raw: Bitmap,
        width: Int = raw.width,
        height: Int = raw.height,
        ts: Long = 0L
    ) = PipelineResult(
        fps = null,
        histograms = null,
        processedBitmap = null,
        rawBitmap = raw,
        detections = null,
        imageWidth = width,
        imageHeight = height,
        frameTimestampNanos = ts
    )
}
