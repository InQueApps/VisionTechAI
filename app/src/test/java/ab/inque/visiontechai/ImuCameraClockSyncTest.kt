package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuCameraClockSyncTest {

    @Test
    fun cameraToSensor_beforeCalibration_returnsCameraTimestamp() {
        val sync = ImuCameraClockSync(calibrationSamples = 3)
        assertEquals(1_000L, sync.cameraToSensorTimestamp(1_000L))
    }

    @Test
    fun cameraToSensor_afterMedianCalibration_appliesOffset() {
        val sync = ImuCameraClockSync(calibrationSamples = 3)
        sync.recordSample(1_030L, 1_000L)
        sync.recordSample(1_040L, 1_010L)
        sync.recordSample(1_050L, 1_020L)
        assertTrue(sync.isCalibrated)
        assertEquals(1_020L, sync.cameraToSensorTimestamp(1_050L))
    }

    @Test
    fun reset_clearsCalibration() {
        val sync = ImuCameraClockSync(calibrationSamples = 1)
        sync.recordSample(100L, 80L)
        assertTrue(sync.isCalibrated)
        sync.reset()
        assertFalse(sync.isCalibrated)
    }
}
