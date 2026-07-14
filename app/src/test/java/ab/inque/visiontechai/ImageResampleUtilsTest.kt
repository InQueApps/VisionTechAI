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
class ImageResampleUtilsTest {

    private fun solidBitmap(w: Int, h: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun bicubicUpsample_changesDimensions() {
        val src = solidBitmap(8, 8)
        val out = ImageResampleUtils.bicubicUpsample(src, 16, 16)
        assertEquals(16, out.width)
        assertEquals(16, out.height)
    }

    @Test
    fun bicubicDownsample_changesDimensions() {
        val src = solidBitmap(16, 16)
        val out = ImageResampleUtils.bicubicDownsample(src, 8, 8)
        assertEquals(8, out.width)
        assertEquals(8, out.height)
    }

    @Test
    fun unsharpMask_preservesDimensions() {
        val src = solidBitmap(12, 8)
        val out = ImageResampleUtils.unsharpMask(src, radius = 1, amount = 0.5f)
        assertEquals(12, out.width)
        assertEquals(8, out.height)
    }

    @Test
    fun bicubicUpsample_solidColor_staysNearOriginal() {
        val color = Color.rgb(80, 120, 200)
        val src = solidBitmap(4, 4, color)
        val out = ImageResampleUtils.bicubicUpsample(src, 8, 8)
        val pixel = out.getPixel(4, 4)
        assertTrue(kotlin.math.abs(Color.red(color) - Color.red(pixel)) <= 15)
        assertTrue(kotlin.math.abs(Color.green(color) - Color.green(pixel)) <= 15)
        assertTrue(kotlin.math.abs(Color.blue(color) - Color.blue(pixel)) <= 15)
    }
}
