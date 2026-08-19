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
import kotlin.math.abs

/**
 * G3-fast + G5: live fast-path per-cell warp with depth on translated overlap.
 */
@RunWith(AndroidJUnit4::class)
class SphereFastMotionE2ETest {

    companion object {
        private const val TAG = "SphereFastMotionE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val SHIFT_DX = 10
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

    private fun checkerboardBitmap(): Bitmap {
        val w = FRAME_W
        val h = FRAME_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val cell = 12
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] =
                    if (((x / cell) + (y / cell)) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
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

    private fun stitchShiftedPair(withDepth: Boolean): Pair<Bitmap?, Float> {
        val base = checkerboardBitmap()
        val shifted = shiftBitmap(base, SHIFT_DX)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(16.0))
        SphereNative.addFrameFast(base, q0, OVERLAP_GATE)
        val nearness = if (withDepth) SphereMotionDepth.gradientNearnessHeuristic(shifted) else null
        SphereNative.addFrameFast(shifted, q1, OVERLAP_GATE, nearness)
        val bmp = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        val info = SphereNative.getLastFrameInfo()
        val warpTx = info?.warpTxPx ?: 0f
        return bmp to warpTx
    }

    @Test
    fun fastPath_withDepth_perCellWarp_improvesSharpness() {
        SphereNative.reset()
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val (noDepth, _) = stitchShiftedPair(withDepth = false)
        assertNotNull(noDepth)
        val regionNoDepth = SphereImageMetrics.requireContentRegion(noDepth!!, "noDepth")
        val sharpNoDepth = SphereImageMetrics.laplacianVariance(noDepth, regionNoDepth)

        SphereNative.reset()
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val (withDepth, warpTx) = stitchShiftedPair(withDepth = true)
        assertNotNull(withDepth)
        val regionDepth = SphereImageMetrics.requireContentRegion(withDepth!!, "withDepth")
        val sharpDepth = SphereImageMetrics.laplacianVariance(withDepth, regionDepth)

        Log.i(TAG, "fast sharpness noDepth=$sharpNoDepth withDepth=$sharpDepth warpTx=$warpTx")
        assertTrue(
            "Depth fast-path warp should estimate non-zero tx (was $warpTx)",
            abs(warpTx) >= 2f
        )
        assertTrue(
            "Depth per-cell fast warp should retain detail (with=$sharpDepth no=$sharpNoDepth)",
            sharpDepth >= sharpNoDepth * 0.85
        )
    }

    @Test
    fun fastPath_warpRecoversKnownShift() {
        val base = checkerboardBitmap()
        val shifted = shiftBitmap(base, SHIFT_DX)
        val nearness = SphereMotionDepth.gradientNearnessHeuristic(shifted)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(16.0))

        SphereNative.addFrameFast(base, q0, OVERLAP_GATE)
        SphereNative.addFrameFast(shifted, q1, OVERLAP_GATE, nearness)

        val info = SphereNative.getLastFrameInfo()
        assertNotNull(info)
        val tx = info!!.warpTxPx
        Log.i(TAG, "recovered warp tx=$tx expected~$SHIFT_DX")
        assertTrue(
            "Fast warp tx should be within 6px of known shift (tx=$tx shift=$SHIFT_DX)",
            abs(tx - SHIFT_DX) <= 6f
        )
    }
}
