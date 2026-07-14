package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProcessorParamsTest {

    @Test
    fun defaults_matchExpectedBaseline() {
        val p = ProcessorParams()
        assertEquals(0.5f, p.nightVisionIntensity, 0.001f)
        assertEquals(50, p.hdScaleSliderValue)
        assertEquals(1, p.hdExposureFrames)
        assertEquals(4, p.segmentationNumClusters)
        assertEquals(1.0f, p.translationTextScale, 0.001f)
    }

    @Test
    fun copy_updatesSingleField() {
        val updated = ProcessorParams().copy(redAdjustment = 2.5f)
        assertEquals(2.5f, updated.redAdjustment, 0.001f)
        assertEquals(1.0f, ProcessorParams().greenAdjustment, 0.001f)
    }
}
