package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Test

class SphereFovHelperTest {

    @Test
    fun effectiveFov_portrait_swapsAxes() {
        val (h, v) = SphereFovHelper.effectiveFov(isPortrait = true, fovH = 70f, fovV = 55f)
        assertEquals(55f, h, 0.01f)
        assertEquals(70f, v, 0.01f)
    }

    @Test
    fun effectiveFov_landscape_keepsAxes() {
        val (h, v) = SphereFovHelper.effectiveFov(isPortrait = false, fovH = 70f, fovV = 55f)
        assertEquals(70f, h, 0.01f)
        assertEquals(55f, v, 0.01f)
    }
}
