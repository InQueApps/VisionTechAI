package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectDetectionOverlayTest {

    @Test
    fun computeScaleFactor_usesMaxOfWidthAndHeightRatios() {
        assertEquals(1.0f, ObjectDetectionOverlay.computeScaleFactor(200, 100, 200, 100), 0.0001f)
        assertEquals(2.0f, ObjectDetectionOverlay.computeScaleFactor(200, 100, 100, 100), 0.0001f)
        assertEquals(1.5f, ObjectDetectionOverlay.computeScaleFactor(300, 150, 200, 150), 0.0001f)
    }
}
