package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereHoleOverlayTest {

    @Test
    fun compositePreview_withMask_returnsBitmap() {
        val preview = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        preview.eraseColor(Color.BLUE)
        val mask = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        mask.eraseColor(0x88FF0000.toInt())
        val out = SphereHoleOverlay.compositePreview(preview, mask)
        assertNotNull(out)
    }
}
