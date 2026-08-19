package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereStitchFramePrepTest {

    @Test
    fun cropCenterSquare_crops16x9ToSquare() {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val cropped = SphereStitchFramePrep.cropCenterSquare(bmp)
        assertEquals(1080, cropped.width)
        assertEquals(1080, cropped.height)
        cropped.recycle()
        bmp.recycle()
    }

    @Test
    fun cropCenterSquare_keepsAlreadySquare() {
        val bmp = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        assertEquals(bmp, SphereStitchFramePrep.cropCenterSquare(bmp))
        bmp.recycle()
    }

    @Test
    fun centerSquare_crops16x9ToSquare() {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val result = SphereStitchFramePrep.centerSquare(bmp, fullFovH = 78f, fullFovV = 62f)
        assertEquals(1080, result.bitmap.width)
        assertEquals(1080, result.bitmap.height)
        assertTrue(result.cropped)
        assertTrue(result.fovH < 78f)
        assertEquals(62f, result.fovV, 0.5f)
        result.bitmap.recycle()
        bmp.recycle()
    }

    @Test
    fun centerSquare_keepsAlreadySquare() {
        val bmp = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val result = SphereStitchFramePrep.centerSquare(bmp, fullFovH = 70f, fullFovV = 70f)
        assertFalse(result.cropped)
        assertEquals(bmp, result.bitmap)
        bmp.recycle()
    }

    @Test
    fun fovForCenterSquare_matchesPinholeFraction() {
        val (h, v) = SphereStitchFramePrep.fovForCenterSquare(78f, 62f, 1920, 1080)
        assertTrue(h in 40f..55f)
        assertEquals(62f, v, 0.5f)
    }
}
