package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase G warp-field stitching: per-cell affine in overlap, extended to full frame,
 * applied during background refinePending rebuild.
 */
@RunWith(AndroidJUnit4::class)
class SphereWarpE2ETest {

    companion object {
        private const val TAG = "SphereWarpE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480

        private const val MIN_REFINE_SHARPNESS_RETENTION = 0.65
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
    }

    @After
    fun teardown() {
        SphereNative.release()
    }

    private fun checkerboardBitmap(
        w: Int = FRAME_W,
        h: Int = FRAME_H,
        cell: Int = 12
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cellX = x / cell
                val cellY = y / cell
                pixels[y * w + x] = if ((cellX + cellY) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Shift pixel content horizontally (simulates parallax / hand translation). */
    private fun shiftBitmap(src: Bitmap, dx: Int, dy: Int = 0): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = y - dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x - dx
                if (sx < 0 || sx >= w) continue
                dstPx[y * w + x] = srcPx[sy * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }

    @Test
    fun translatedOverlap_refinePending_rebuildsWithWarp() {
        val base = checkerboardBitmap()
        val shifted = shiftBitmap(base, dx = 8)

        val q = floatArrayOf(0f, 0f, 0f, 1f)
        val qOverlap = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(16.0))

        SphereNative.addFrameFast(base, q, 0f)
        SphereNative.addFrameFast(shifted, qOverlap, 0.10f)

        assertTrue("Refine must run with dirty frames", SphereNative.refinePending())
        assertTrue(
            "Second refine should be no-op once all frames are refined",
            !SphereNative.refinePending()
        )

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, "translatedRefine")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, "translated overlap sharpness after warp refine=$sharpness")
        assertTrue("Warp refine should retain checkerboard detail", sharpness > 20.0)
    }

    @Test
    fun warpRefine_retainsSharpness() {
        val frame = checkerboardBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(18.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(36.0))
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val before = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(before)
        val regionBefore = SphereImageMetrics.requireContentRegion(before!!, "beforeRefine")
        val sharpBefore = SphereImageMetrics.laplacianVariance(before, regionBefore)

        assertTrue(SphereNative.refinePending())

        val after = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(after)
        val regionAfter = SphereImageMetrics.requireContentRegion(after!!, "afterRefine")
        val sharpAfter = SphereImageMetrics.laplacianVariance(after, regionAfter)

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "warp refine sharpness retention=${"%.3f".format(retention)} " +
            "(before=$sharpBefore after=$sharpAfter)")
        assertTrue(
            "Warp refine made output muddy (retention=$retention)",
            retention >= MIN_REFINE_SHARPNESS_RETENTION
        )
    }

    @Test
    fun shiftedFrame_nonOverlapRegion_stillSplatAfterRefine() {
        val base = checkerboardBitmap()
        val shifted = shiftBitmap(base, dx = 10)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0))

        SphereNative.addFrameFast(base, q0, 0f)
        SphereNative.addFrameFast(shifted, q1, 0.10f)
        SphereNative.refinePending()

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, "shiftedRefine")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, "sharpness after warp refine=$sharpness")
        assertTrue("Refined panorama should retain measurable sharpness", sharpness > 20.0)
    }

    /**
     * Regression: frames must not be locked before first refine (REALIGN_WINDOW bug).
     * 45 fast-path frames without pause, then refine — output must stay sharp, no holes.
     */
    @Test
    fun longSession_refinePending_alignsAllFrames_notLockedEarly() {
        val frame = checkerboardBitmap()
        for (i in 0 until 45) {
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f)
            else QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 4.0))
            SphereNative.addFrameFast(frame, q, if (i == 0) 0f else 0.08f)
        }
        assertTrue(SphereNative.refinePending())
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, "longSession")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, "longSession(45 frames) sharpness=$sharpness")
        assertTrue("Long session refine should retain detail", sharpness > 50.0)
    }
}
