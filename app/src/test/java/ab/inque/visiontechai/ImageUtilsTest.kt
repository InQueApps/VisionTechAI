package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageUtilsTest {

    private fun solidBitmap(color: Int, width: Int = 4, height: Int = 4): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    @Test
    fun calculateHistograms_solidRed_countsAllPixelsInRedBin() {
        val bitmap = solidBitmap(Color.RED, 8, 8)
        val (r, g, b) = ImageUtils.calculateHistograms(bitmap)
        assertEquals(64, r[255])
        assertEquals(64, g[0])
        assertEquals(64, b[0])
    }

    @Test
    fun applyColorAdjustment_scalesChannels() {
        val bitmap = solidBitmap(Color.rgb(100, 100, 100))
        val params = ProcessorParams(redAdjustment = 2.0f, greenAdjustment = 0.5f, blueAdjustment = 1.0f)
        val out = ImageUtils.applyColorAdjustment(bitmap, params)
        val pixel = out.getPixel(0, 0)
        assertEquals(200, Color.red(pixel))
        assertEquals(50, Color.green(pixel))
        assertEquals(100, Color.blue(pixel))
    }

    @Test
    fun flipBitmapHorizontally_preservesDimensions() {
        val bitmap = solidBitmap(Color.RED, 16, 12)
        val flipped = ImageUtils.flipBitmapHorizontally(bitmap)
        assertEquals(16, flipped.width)
        assertEquals(12, flipped.height)
    }

    @Test
    fun histogramEqualizationRGB_zeroIntensity_preservesPixels() {
        val bitmap = solidBitmap(Color.rgb(40, 80, 120))
        val out = ImageUtils.histogramEqualizationRGB(bitmap, intensity = 0f)
        assertEquals(Color.rgb(40, 80, 120), out.getPixel(0, 0))
    }

    @Test
    fun bitmapToI420_writesExpectedBufferSize() {
        val bitmap = solidBitmap(Color.WHITE, 4, 4)
        val buffer = ByteArray(bitmap.width * bitmap.height * 3 / 2)
        ImageUtils.bitmapToI420(bitmap, buffer)
        assertTrue(buffer.isNotEmpty())
        assertTrue(buffer[0].toInt() and 0xFF > 200)
    }
}
