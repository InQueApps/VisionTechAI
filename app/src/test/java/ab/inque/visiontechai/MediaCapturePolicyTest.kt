package ab.inque.visiontechai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapturePolicyTest {

    @Test
    fun shouldCaptureOverlayBitmap_trueForFilterModes() {
        assertTrue(MediaCapturePolicy.shouldCaptureOverlayBitmap(VisionMode.HD))
        assertTrue(MediaCapturePolicy.shouldCaptureOverlayBitmap(VisionMode.TEXT_TRANSLATION))
    }

    @Test
    fun shouldCaptureOverlayBitmap_falseForNoneObjectDetectionSphere() {
        assertFalse(MediaCapturePolicy.shouldCaptureOverlayBitmap(VisionMode.NONE))
        assertFalse(MediaCapturePolicy.shouldCaptureOverlayBitmap(VisionMode.OBJECT_DETECTION))
        assertFalse(MediaCapturePolicy.shouldCaptureOverlayBitmap(VisionMode.SPHERE))
    }

    @Test
    fun shouldRecordProcessedVideo_matchesOverlayBitmapPolicy() {
        assertFalse(MediaCapturePolicy.shouldRecordProcessedVideo(VisionMode.NONE))
        assertFalse(MediaCapturePolicy.shouldRecordProcessedVideo(VisionMode.OBJECT_DETECTION))
        assertFalse(MediaCapturePolicy.shouldRecordProcessedVideo(VisionMode.SPHERE))
        assertTrue(MediaCapturePolicy.shouldRecordProcessedVideo(VisionMode.EDGE_DETECTION))
        assertTrue(MediaCapturePolicy.shouldRecordProcessedVideo(VisionMode.HD))
    }
}
