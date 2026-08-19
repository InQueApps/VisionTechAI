package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device E2E for [SphereExportQualityGate] on synthetic stripe bitmaps.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SphereExportQualityGateE2ETest {

    @Test
    fun identicalStripePreviewAndExport_passesGate() {
        assumeTrue(SphereNative.isAvailable())
        val bmp = stripeBitmap(256, 128)
        val result = SphereExportQualityGate.evaluate(bmp, bmp)
        assertTrue("identical export should pass: ${result.summary}", result.passed)
    }

    @Test
    fun orthogonalStripes_failQualityGate() {
        assumeTrue(SphereNative.isAvailable())
        val preview = stripeBitmap(256, 128, vertical = true)
        val export = stripeBitmap(256, 128, vertical = true)
        val px = IntArray(256 * 128)
        preview.getPixels(px, 0, 256, 0, 0, 256, 128)
        for (i in px.indices) {
            px[i] = px[i] xor 0x00FFFFFF
        }
        export.setPixels(px, 0, 256, 0, 0, 256, 128)
        val result = SphereExportQualityGate.evaluate(preview, export)
        assertFalse(
            "inverted stripes should fail gate: ${result.summary}",
            result.passed
        )
    }

    private fun stripeBitmap(w: Int, h: Int, vertical: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val stripe = if (vertical) (x / 16) % 2 == 0 else (y / 16) % 2 == 0
                px[y * w + x] = if (stripe) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
