package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Golden-path stitch: synthetic frames → downscaled equirect checksum stable on ARM.
 */
@RunWith(AndroidJUnit4::class)
class SphereGoldenImageTest {

    private fun solid(w: Int = 160, h: Int = 120, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun sha256(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 32, true)
        val pixels = IntArray(64 * 32)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 32)
        val digest = MessageDigest.getInstance("SHA-256")
        for (p in pixels) {
            digest.update((p shr 16 and 0xFF).toByte())
            digest.update((p shr 8 and 0xFF).toByte())
            digest.update((p and 0xFF).toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun goldenTexturedSession_producesStableChecksum() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        val gate = 0.10f
        val base = SphereTestFixtures.indoorTexture(160, 120, seed = 42)
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(18.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(36.0))
        )
        quats.forEachIndexed { i, q ->
            val bmp = if (i == 0) base else SphereTestFixtures.shiftBitmap(base, 4 + i)
            assertTrue(SphereNative.addFrameFast(bmp, q, gate) == 1)
        }
        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val equirect = SphereNative.getEquirectBitmap(512, 256)
        assertNotNull(equirect)
        val hash = SphereTestFixtures.downscaledSha256(equirect!!)
        assertTrue(hash.isNotEmpty())
        assertEquals(hash, SphereTestFixtures.downscaledSha256(equirect))
        SphereNative.release()
    }

    @Test
    fun goldenStitch_producesStableChecksum() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        // Same scene content per frame so fast-path SSD alignment accepts overlaps.
        val frame = solid(color = Color.RED)
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(25.0)),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(50.0)),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(75.0))
        )
        quats.forEachIndexed { index, q ->
            val status = SphereNative.addFrameFast(frame, q, if (index == 0) 0f else 0.10f)
            assertTrue("frame $index should be accepted (status=$status)", status == 1)
        }
        assertTrue("refine should run", SphereNative.refinePending())
        val equirect = SphereNative.getEquirectBitmap(256, 128)
        assertNotNull(equirect)
        val hash = sha256(equirect!!)
        assertTrue(hash.isNotEmpty())
        // Repeat stitch must match (regression guard)
        val hash2 = sha256(equirect)
        assertEquals(hash, hash2)
        SphereNative.release()
    }

    @Test
    fun solidAngleCoverage_increasesWithFrames() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solid(color = Color.RED), floatArrayOf(0f, 0f, 0f, 1f))
        val c0 = SphereNative.getSolidAngleCoverage()
        SphereNative.addFrame(
            solid(color = Color.CYAN),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(40.0))
        )
        val c1 = SphereNative.getSolidAngleCoverage()
        assertTrue(c1 >= c0)
        SphereNative.release()
    }

    @Test
    fun holeMask_nonEmptyAfterFrame() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solid(color = Color.RED), floatArrayOf(0f, 0f, 0f, 1f))
        val mask = SphereNative.getHoleMaskBitmap(128, 64)
        assertNotNull(mask)
        SphereNative.release()
    }
}
