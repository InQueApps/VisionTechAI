package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereExtrinsicCalibrationTest {

    private lateinit var calibration: SphereExtrinsicCalibration

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        calibration = SphereExtrinsicCalibration(context)
        calibration.reset()
    }

    @Test
    fun default_isIdentity() {
        assertTrue(calibration.isIdentity)
    }

    @Test
    fun setOffset_persistsAndApplies() {
        val offset = QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0))
        calibration.setOffsetQuaternion(offset)
        assertFalse(calibration.isIdentity)
        val imu = floatArrayOf(0f, 0f, 0f, 1f)
        val out = calibration.applyToImu(imu)
        assertEquals(-1.0, QuaternionMath.rotateVector(out, 0.0, 0.0, -1.0)[0], 0.05)
    }

    @Test
    fun reset_restoresIdentity() {
        calibration.setOffsetQuaternion(
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(15.0))
        )
        calibration.reset()
        assertTrue(calibration.isIdentity)
    }
}
