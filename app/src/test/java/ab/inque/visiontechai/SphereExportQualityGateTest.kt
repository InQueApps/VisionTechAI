package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SphereExportQualityGateTest {

    private fun texturedBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if ((x / 16) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    @Test
    fun identicalPreviewAndExport_passesGate() {
        val bmp = texturedBitmap(256, 128)
        val result = SphereExportQualityGate.evaluate(bmp, bmp)
        assertTrue(result.passed)
        assertTrue(result.ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta())
    }

    @Test
    fun mismatchedExport_failsAtLeastOneGate() {
        val preview = texturedBitmap(256, 128)
        val export = texturedBitmap(256, 128)
        val px = IntArray(256 * 128)
        preview.getPixels(px, 0, 256, 0, 0, 256, 128)
        for (i in px.indices) {
            px[i] = px[i] xor 0x00FFFFFF
        }
        export.setPixels(px, 0, 256, 0, 0, 256, 128)
        val result = SphereExportQualityGate.evaluate(preview, export)
        assertFalse(
            "inverted export should fail quality gate (corr=${result.overlapBandCorrelation}, ${result.summary})",
            result.passed
        )
    }
}
