package ab.inque.visiontechai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrameProcessorAdaptersTest {

    private fun solidBitmap(color: Int = Color.rgb(100, 100, 100)): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun colorAdjustmentFrameProcessor_appliesParams() {
        val processor = ColorAdjustmentFrameProcessor()
        val params = ProcessorParams(redAdjustment = 2f, greenAdjustment = 0.5f, blueAdjustment = 1f)
        val out = processor.process(solidBitmap(), params)
        assertNotNull(out)
        assertEquals(200, Color.red(out!!.getPixel(0, 0)))
        assertEquals(50, Color.green(out.getPixel(0, 0)))
    }

    @Test
    fun hdFrameProcessor_scaleAtCenter_returnsAccumulatedWithoutUpscale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hd = HDImageProcessor(context)
        val processor = HDFrameProcessor(hd)
        val input = solidBitmap()
        val params = ProcessorParams(hdScaleSliderValue = 50, hdExposureFrames = 1)

        val out = processor.process(input, params)!!

        assertSame(input, out)
    }

    @Test
    fun hdFrameProcessor_clearCache_resetsLatestUpscaled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = HDFrameProcessor(HDImageProcessor(context))
        processor.clearCache()
        val out = processor.process(
            solidBitmap(),
            ProcessorParams(hdScaleSliderValue = 50, hdExposureFrames = 1)
        )
        assertNotNull(out)
    }
}
