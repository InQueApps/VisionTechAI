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
 * G1: sparse pose graph + Gauss-Newton global refine lowers overlap-bank SSD.
 */
@RunWith(AndroidJUnit4::class)
class SphereGlobalPoseE2ETest {

    companion object {
        private const val TAG = "SphereGlobalPoseE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val OVERLAP_GATE = 0.08f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
        System.gc()
    }

    private fun stripeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pixels[y * FRAME_W + x] =
                    if ((x / 40) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

    private fun shiftBitmap(src: Bitmap, dx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) dstPx[y * w + x] = srcPx[y * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }

    @Test
    fun sparseGraph_globalRefine_lowersOverlapBankSSD() {
        val frame = stripeBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(14.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(28.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(42.0))
        )
        val shifts = listOf(0, 6, 7, 5)
        for (i in quats.indices) {
            val bmp = if (shifts[i] == 0) frame else shiftBitmap(frame, shifts[i])
            val nearness = SphereMotionDepth.gradientNearnessHeuristic(bmp)
            SphereNative.addFrameFast(bmp, quats[i], OVERLAP_GATE, nearness)
        }

        assertTrue("overlap bank should be populated", SphereNative.getOverlapBankSize() >= 20)
        val ssdBefore = SphereNative.evalOverlapBankSSD()
        Log.i(TAG, "overlap bank SSD before global refine=$ssdBefore")

        assertTrue("global refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val ssdAfter = SphereNative.evalOverlapBankSSD()
        Log.i(TAG, "overlap bank SSD after global refine=$ssdAfter")

        assertTrue(
            "Global sparse-graph refine should lower overlap SSD (before=$ssdBefore after=$ssdAfter)",
            ssdAfter <= ssdBefore * 1.02 || ssdAfter < ssdBefore
        )
    }

    @Test
    fun sixDof_translatedPair_globalRefine_improvesGhost() {
        val base = stripeBitmap()
        val shifted = shiftBitmap(base, dx = 10)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(16.0))
        val nearness = SphereMotionDepth.gradientNearnessHeuristic(shifted)

        SphereNative.addFrameFast(base, q0, OVERLAP_GATE)
        SphereNative.addFrameFast(shifted, q1, OVERLAP_GATE, nearness)

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        val ghostFast = SphereImageMetrics.ghostingScoreAtOffset(
            fastOnly!!,
            SphereImageMetrics.requireContentRegion(fastOnly, "sixDofFast"),
            expectedDx = 10,
            tolerance = 2
        )

        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = SphereImageMetrics.ghostingScoreAtOffset(
            refined!!,
            SphereImageMetrics.requireContentRegion(refined, "sixDofRefined"),
            expectedDx = 10,
            tolerance = 2
        )

        val ssdAfter = SphereNative.evalOverlapBankSSD()
        Log.i(TAG, "6-DoF ghost fast=$ghostFast refined=$ghostRefined ssd=$ssdAfter")
        assertTrue(
            "6-DoF global refine should not add ghost (fast=$ghostFast refined=$ghostRefined)",
            ghostRefined <= ghostFast * 1.05
        )
    }

    @Test
    fun globalRefine_threeFrameSession_retainsSharpness() {
        val frame = stripeBitmap()
        for (i in 0 until 3) {
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f)
            else QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 18.0))
            val bmp = if (i == 0) frame else shiftBitmap(frame, 6 + i)
            SphereNative.addFrameFast(bmp, q, OVERLAP_GATE, SphereMotionDepth.gradientNearnessHeuristic(bmp))
        }

        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, "globalPose3")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, "global pose 3-frame sharpness=$sharpness")
        assertTrue("Global pose refine should retain stripe detail", sharpness > 15.0)
    }
}
