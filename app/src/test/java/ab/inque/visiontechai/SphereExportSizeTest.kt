package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereExportSizeTest {

    @Test
    fun square1080At50deg_matchesNativeAngularResolution() {
        val size = SphereExportSize.fromFrame(1080, 1080, 50f, 50f, maxWidth = 8192)
        // 1080 px / 50° * 360° = 7776 (already 16-aligned).
        assertEquals(7776, size.width)
        assertEquals(3888, size.height)
    }

    @Test
    fun missingFrame_fallsBackToDefaultGallerySize() {
        val size = SphereExportSize.fromFrame(0, 0, 65f, 50f)
        assertEquals(SphereCaptureController.EQ_W, size.width)
        assertEquals(SphereCaptureController.EQ_H, size.height)
    }

    @Test
    fun tinyFrames_doNotGoBelowMinWidth() {
        val size = SphereExportSize.fromFrame(8, 8, 65f, 50f, maxWidth = 8192)
        assertEquals(SphereExportSize.MIN_WIDTH, size.width)
        assertEquals(SphereExportSize.MIN_WIDTH / 2, size.height)
    }

    @Test
    fun hugeFrames_capAtDeviceMax() {
        val size = SphereExportSize.fromFrame(4000, 3000, 70f, 55f, maxWidth = 4096)
        assertEquals(4096, size.width)
        assertEquals(2048, size.height)
    }

    @Test
    fun deviceHeapCaps() {
        assertEquals(2048, SphereExportSize.maxWidthForDevice(200L * 1024 * 1024))
        assertEquals(4096, SphereExportSize.maxWidthForDevice(500L * 1024 * 1024))
        assertEquals(8192, SphereExportSize.maxWidthForDevice(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun viewerSize_capsDisplayWithoutChangingExport() {
        val export = SphereExportSize.fromFrame(1440, 1440, 62f, 62f, maxWidth = 8192)
        val viewer = SphereExportSize.viewerSize(export.width, export.height)
        assertTrue(export.width >= 7776)
        assertEquals(SphereExportSize.VIEWER_MAX_WIDTH, viewer.width)
        assertEquals(SphereExportSize.VIEWER_MAX_WIDTH / 2, viewer.height)
    }

    @Test
    fun outputIsAlwaysEvenTwoToOne() {
        val size = SphereExportSize.fromFrame(1440, 1440, 62f, 62f, maxWidth = 8192)
        assertEquals(0, size.width % 2)
        assertEquals(size.width / 2, size.height)
        assertTrue(size.width in SphereExportSize.MIN_WIDTH..8192)
    }
}
