package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import ab.inque.visiontechai.ui.VisionOverlayHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VisionOverlayHostTest {

    private fun host(): VisionOverlayHost {
        val context = RuntimeEnvironment.getApplication()
        val parent = ConstraintLayout(context)
        parent.layoutParams = ConstraintLayout.LayoutParams(1080, 1920)
        return VisionOverlayHost(parent, ConstraintLayout.LayoutParams.PARENT_ID)
    }

    @Test
    fun usesFilterOverlay_delegatesToMediaCapturePolicy() {
        val overlay = host()
        assertTrue(overlay.usesFilterOverlay(VisionMode.HD))
        assertFalse(overlay.usesFilterOverlay(VisionMode.OBJECT_DETECTION))
        assertFalse(overlay.usesFilterOverlay(VisionMode.SPHERE))
    }

    @Test
    fun showFilterOverlay_showsFilterForBitmapModes() {
        val overlay = host()
        overlay.showFilterOverlay(VisionMode.EDGE_DETECTION)
        assertEquals(View.VISIBLE, overlay.filterOverlay.visibility)
        assertEquals(View.GONE, overlay.objectDetectionOverlay.visibility)
    }

    @Test
    fun showFilterOverlay_showsDetectionOverlayForObjectDetection() {
        val overlay = host()
        overlay.showFilterOverlay(VisionMode.OBJECT_DETECTION)
        assertEquals(View.GONE, overlay.filterOverlay.visibility)
        assertEquals(View.VISIBLE, overlay.objectDetectionOverlay.visibility)
    }

    @Test
    fun captureFilterBitmap_returnsNullWhenNoDrawable() {
        val overlay = host()
        assertEquals(null, overlay.captureFilterBitmap())
    }

    @Test
    fun setFilterBitmap_allowsCaptureCopy() {
        val overlay = host()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        overlay.setFilterBitmap(bitmap)
        val captured = overlay.captureFilterBitmap()
        assertTrue(captured != null)
        assertEquals(Color.GREEN, captured!!.getPixel(0, 0))
    }
}
