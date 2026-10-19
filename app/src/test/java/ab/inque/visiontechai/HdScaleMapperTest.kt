package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdScaleMapperTest {

    @Test
    fun sliderToScale_centerIsUnity() {
        assertEquals(1.0f, HdScaleMapper.sliderToScale(50), 0.0001f)
    }

    @Test
    fun sliderToScale_minIsPointZeroOne() {
        assertEquals(0.01f, HdScaleMapper.sliderToScale(0), 0.0001f)
    }

    @Test
    fun sliderToScale_maxIsFour() {
        assertEquals(4.0f, HdScaleMapper.sliderToScale(100), 0.0001f)
    }

    @Test
    fun sliderToScale_downscaleIsBetweenMinAndOne() {
        val scale = HdScaleMapper.sliderToScale(25)
        assertTrue(scale > 0.01f && scale < 1.0f)
    }

    @Test
    fun sliderToScale_upscaleIsBetweenOneAndMax() {
        val scale = HdScaleMapper.sliderToScale(75)
        assertTrue(scale > 1.0f && scale < 4.0f)
    }
}
