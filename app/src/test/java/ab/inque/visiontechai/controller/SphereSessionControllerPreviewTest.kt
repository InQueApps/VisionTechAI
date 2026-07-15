package ab.inque.visiontechai.controller

import ab.inque.visiontechai.LensDistortion
import ab.inque.visiontechai.OrientationTracker
import ab.inque.visiontechai.R
import ab.inque.visiontechai.SphereCaptureController
import ab.inque.visiontechai.SphereExtrinsicCalibration
import ab.inque.visiontechai.SphereStitchOperations
import ab.inque.visiontechai.SphereStitchWorker
import ab.inque.visiontechai.databinding.ActivityMainBinding
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereSessionControllerPreviewTest {

    private class PreviewOps : SphereStitchOperations {
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
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (frames == 1) Color.RED else Color.GREEN)
            }
        override fun reset() { frames = 0 }
        override fun release() {}
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var ops: PreviewOps
    private lateinit var controller: SphereCaptureController
    private lateinit var session: SphereSessionController

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_PrismAI)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        ops = PreviewOps()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        controller = SphereCaptureController(
            OrientationTracker(ctx),
            SphereExtrinsicCalibration(ctx).apply { reset() },
            onCoverageUpdate = { area, solid, preview ->
                binding.sphereCoverageText.text = "Coverage: ${(area * 100).toInt()}%"
                ab.inque.visiontechai.SpherePreviewBinder.apply(binding.spherePreviewImage, preview)
            },
            onPreviewReady = {},
            operations = ops,
            stitchWorker = worker
        )
        session = SphereSessionController(
            activity = org.robolectric.Robolectric.buildActivity(
                androidx.appcompat.app.AppCompatActivity::class.java
            ).get(),
            binding = binding,
            orientationTracker = OrientationTracker(ctx),
            setVisionMode = {},
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
    fun photoCapture_withLastRawFrame_updatesPreviewImageView() {
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        session.onRawFrame(raw, 1L)
        session.handleRecordButton(isPhotoMode = true) {}
        awaitFrames(1)
        awaitPreviewPixel(Color.RED)
    }

    @Test
    fun secondFrame_changesPreviewPixel() {
        val raw = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        session.onRawFrame(raw, 1L)
        session.handleRecordButton(isPhotoMode = true) {}
        awaitFrames(1)
        awaitPreviewPixel(Color.RED)
        session.onRawFrame(raw, 1L)
        session.handleRecordButton(isPhotoMode = true) {}
        awaitFrames(2)
        awaitPreviewPixel(Color.GREEN)
    }

    private fun awaitPreviewPixel(color: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val drawable = binding.spherePreviewImage.drawable as? BitmapDrawable
            if (drawable != null && drawable.bitmap.getPixel(0, 0) == color) return
            Thread.sleep(5)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val drawable = binding.spherePreviewImage.drawable as? BitmapDrawable
        assertEquals(color, drawable?.bitmap?.getPixel(0, 0))
    }

    private fun awaitFrames(count: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (ops.frames < count && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(count, ops.frames)
    }
}
