package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModeTest {

    @Test
    fun category_mapsModesToToolbarGroups() {
        assertEquals(VisionMode.Category.NV, VisionMode.NIGHT_VISION.category)
        assertEquals(VisionMode.Category.IMG, VisionMode.EDGE_DETECTION.category)
        assertEquals(VisionMode.Category.IMG, VisionMode.HD.category)
        assertEquals(VisionMode.Category.ROB, VisionMode.OBJECT_DETECTION.category)
        assertEquals(VisionMode.Category.TXT, VisionMode.TEXT_TRANSLATION.category)
        assertEquals(VisionMode.Category.SPHERE, VisionMode.SPHERE.category)
        assertEquals(VisionMode.Category.NONE, VisionMode.NONE.category)
    }

    @Test
    fun hasSlider_trueForAdjustableModes() {
        assertTrue(VisionMode.NIGHT_VISION.hasSlider)
        assertTrue(VisionMode.EDGE_DETECTION.hasSlider)
        assertTrue(VisionMode.HD.hasSlider)
        assertTrue(VisionMode.OPTICAL_FLOW.hasSlider)
        assertTrue(VisionMode.TEXT_TRANSLATION.hasSlider)
    }

    @Test
    fun hasSlider_falseForOverlayOnlyModes() {
        assertFalse(VisionMode.NONE.hasSlider)
        assertFalse(VisionMode.OBJECT_DETECTION.hasSlider)
        assertFalse(VisionMode.SPHERE.hasSlider)
    }
}
