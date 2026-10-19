package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFovCalculatorTest {

    @Test
    fun fromSensor_typicalWide_matchesPinhole() {
        val (h, v) = CameraFovCalculator.fromSensor(5.76f, 4.29f, 4.38f)
        assertEquals(65.5f, h, 1.5f)
        assertEquals(51.0f, v, 1.5f)
    }

    @Test
    fun fromSensor_zeroFocal_returnsFallback() {
        val (h, v) = CameraFovCalculator.fromSensor(5f, 4f, 0f)
        assertEquals(65f, h, 0.1f)
        assertEquals(50f, v, 0.1f)
    }
}
