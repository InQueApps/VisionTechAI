package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI

/**
 * End-to-end tests for libvision_sphere JNI stitching.
 * Requires a physical ARM device or ARM64 emulator with native libs loaded.
 */
@RunWith(AndroidJUnit4::class)
class SphereStitchE2ETest {

    private fun solidBitmap(w: Int = 320, h: Int = 240, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun nativeLibrary_isAvailable() {
        assertTrue("vision_sphere must load on device APK", SphereNative.isAvailable())
    }

    @Test
    fun initAndReset_succeeds() {
        assumeTrue(SphereNative.isAvailable())
        assertTrue(SphereNative.init(512, 256, 65f, 50f))
        assertEquals(0f, SphereNative.getCoverage(), 0.001f)
        SphereNative.reset()
        SphereNative.release()
    }

    @Test
    fun addRotatedFrames_increasesCoverage() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        val frame = solidBitmap()

        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(SphereNative.addFrame(frame, q0))
        val coverage0 = SphereNative.getCoverage()

        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(25.0))
        assertTrue(SphereNative.addFrame(frame, q1))
        val coverage1 = SphereNative.getCoverage()

        val q2 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(50.0))
        assertTrue(SphereNative.addFrame(frame, q2))
        val coverage2 = SphereNative.getCoverage()

        assertTrue("Second frame should add coverage", coverage1 > coverage0)
        assertTrue("Third frame should add coverage", coverage2 > coverage1)

        val equirect = SphereNative.getEquirectBitmap(512, 256)
        assertNotNull(equirect)
        assertTrue(equirect!!.width == 512 && equirect.height == 256)

        SphereNative.release()
    }

    @Test
    fun getLastFrameInfo_populatedAfterAdd() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solidBitmap(160, 120), floatArrayOf(0f, 0f, 0f, 1f))
        SphereNative.addFrame(
            solidBitmap(160, 120, Color.BLUE),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0))
        )
        val info = SphereNative.getLastFrameInfo()
        assertNotNull(info)
        assertTrue(info!!.frameIndex >= 2)
        SphereNative.release()
    }

    @Test
    fun rectilinearView_returnsBitmap() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solidBitmap(160, 120), floatArrayOf(0f, 0f, 0f, 1f))
        val view = SphereNative.getRectilinearBitmap(320, 240, 0f, 0f, 90f)
        assertNotNull(view)
        SphereNative.release()
    }

    @Test
    fun solidAngleCoverage_increasesWithFrames() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        val c0 = SphereNative.getSolidAngleCoverage()
        SphereNative.addFrame(
            solidBitmap(color = Color.CYAN),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(40.0))
        )
        val c1 = SphereNative.getSolidAngleCoverage()
        assertTrue(c1 >= c0)
        SphereNative.release()
    }

    @Test
    fun holeMask_returnsBitmapAfterFrame() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        val mask = SphereNative.getHoleMaskBitmap(128, 64)
        assertNotNull(mask)
        assertEquals(128, mask!!.width)
        assertEquals(64, mask.height)
        SphereNative.release()
    }

    @Test
    fun refineSeams_succeedsAfterFrames() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        SphereNative.addFrame(
            solidBitmap(color = Color.BLUE),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(30.0))
        )
        assertTrue(SphereNative.refineSeams())
        val bmp = SphereNative.getEquirectBitmap(512, 256)
        assertNotNull(bmp)
        SphereNative.release()
    }
}
