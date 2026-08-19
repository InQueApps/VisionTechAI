package ab.inque.robotics.test

/**
 * JNI entry to run Google Test suite for robotics C++ core (device/emulator).
 */
object RoboticsCppTestNative {

    private var libraryLoaded = false

    init {
        try {
            System.loadLibrary("robotics_cpp_tests")
            libraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    /** @return number of failed tests (0 = all passed). */
    external fun runAll(): Int
}
