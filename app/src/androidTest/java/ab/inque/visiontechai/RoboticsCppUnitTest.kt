package ab.inque.visiontechai

import ab.inque.robotics.test.RoboticsCppTestNative
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Google Test suite for [app/src/robotics/cpp](robotics C++ core) on device.
 */
@RunWith(AndroidJUnit4::class)
class RoboticsCppUnitTest {

    @Test
    fun roboticsCppCore_allTestsPass() {
        try {
            val failures = RoboticsCppTestNative.runAll()
            assertEquals("robotics C++ unit test failures", 0, failures)
        } catch (e: UnsatisfiedLinkError) {
            throw AssertionError(
                "robotics_cpp_tests native library not loaded (debug build required)", e
            )
        }
    }
}
