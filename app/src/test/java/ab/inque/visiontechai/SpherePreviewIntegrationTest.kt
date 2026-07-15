package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end preview pipeline: capture → stitch worker → callback → ImageView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpherePreviewIntegrationTest {

    private class ChangingPreviewOps : SphereStitchOperations {
        var addCount = 0
        private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

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

        override fun getCoverage(): Float = addCount * 0.15f
        override fun getSolidAngleCoverage(): Float = addCount * 0.12f
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo(): FrameAlignmentInfo? =
            FrameAlignmentInfo(addCount, 50, 200, 1f, 100f, false)

        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(colors[(addCount - 1).coerceAtLeast(0) % colors.size])
            }

        override fun reset() {
            addCount = 0
        }

        override fun release() {}
    }

    @Test
    fun multiFrame_eachAcceptedFrame_changesPreviewColor() {
        val ops = ChangingPreviewOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val previews = mutableListOf<Bitmap>()
        val controller = SphereCaptureController(
            OrientationTracker(RuntimeEnvironment.getApplication()),
            SphereExtrinsicCalibration(RuntimeEnvironment.getApplication()).apply { reset() },
            onCoverageUpdate = { _, _, preview -> preview?.let { previews.add(it) } },
            onPreviewReady = {},
            operations = ops,
            stitchWorker = worker
        )
        controller.startSession()
        val bmp = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        repeat(3) {
            controller.captureFrame(bmp)
            awaitCoverage(controller, (it + 1) * 0.15f)
            awaitPreviewCount(previews, it + 1)
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, ops.addCount)
        assertEquals(3, previews.size)
        assertEquals(Color.RED, previews[0].getPixel(0, 0))
        assertEquals(Color.GREEN, previews[1].getPixel(0, 0))
        assertEquals(Color.BLUE, previews[2].getPixel(0, 0))
        worker.shutdown()
    }

    @Test
    fun previewBinder_updatesImageViewPixel() {
        val context = RuntimeEnvironment.getApplication()
        val imageView = ImageView(context)
        val bmp1 = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bmp2 = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        SpherePreviewBinder.apply(imageView, bmp1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Color.RED, shadowBitmap(imageView).getPixel(0, 0))
        SpherePreviewBinder.apply(imageView, bmp2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Color.GREEN, shadowBitmap(imageView).getPixel(0, 0))
        assertNotSame(bmp1, bmp2)
    }

    @Test
    fun rejectedFrame_keepsLastPreview() {
        val ops = ChangingPreviewOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        var lastPreview: Bitmap? = null
        val controller = SphereCaptureController(
            OrientationTracker(RuntimeEnvironment.getApplication()),
            SphereExtrinsicCalibration(RuntimeEnvironment.getApplication()).apply { reset() },
            onCoverageUpdate = { _, _, p -> if (p != null) lastPreview = p },
            onPreviewReady = {},
            operations = ops,
            stitchWorker = worker
        )
        controller.startSession()
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        controller.captureFrame(bmp)
        awaitCoverage(controller, 0.15f)
        val first = lastPreview
        assertNotNull(first)
        // Simulate reject: worker still returns preview but native accepted=false
        worker.onResult?.invoke(
            SphereStitchResult(
                accepted = false,
                coverage = 0.15f,
                solidAngleCoverage = 0.12f,
                frameInfo = null,
                previewBitmap = first,
                durationMs = 1,
                ranOnMainThread = false
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(first, controller.lastPreviewBitmap)
        worker.shutdown()
    }

    private fun awaitPreviewCount(previews: List<Bitmap>, count: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (previews.size < count && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(count, previews.size)
    }

    private fun awaitCoverage(controller: SphereCaptureController, minCoverage: Float) {
        val deadline = System.currentTimeMillis() + 3000
        while (controller.lastCoverage < minCoverage && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun shadowBitmap(imageView: ImageView): Bitmap {
        val drawable = imageView.drawable as? android.graphics.drawable.BitmapDrawable
            ?: error("ImageView has no BitmapDrawable")
        return drawable.bitmap
    }

    private fun assertTrue(v: Boolean) = org.junit.Assert.assertTrue(v)
}
