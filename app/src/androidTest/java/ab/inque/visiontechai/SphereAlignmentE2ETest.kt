package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Stitching alignment E2E: fast-path rotational SSD, reject gate, IMU-parity preview refine,
 * and global pose on finish.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SphereAlignmentE2ETest {

    companion object {
        private const val TAG = "SphereAlignmentE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val SHIFT_DX = 8
        private const val OVERLAP_GATE = 0.08f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.release()
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
        System.gc()
    }

    private fun verticalStripeBitmap(stripeWidth: Int = 40): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pixels[y * FRAME_W + x] =
                    if ((x / stripeWidth) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

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

    private fun overlapQuat(yawDeg: Double = 16.0): FloatArray =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(yawDeg))

    private fun quatWithYawError(base: FloatArray, degError: Double): FloatArray {
        val err = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(degError))
        return QuaternionMath.normalize(QuaternionMath.multiply(base, err))
    }

    private fun ghostAtShift(bmp: Bitmap, label: String, dx: Int = SHIFT_DX): Double {
        val region = SphereImageMetrics.requireContentRegion(bmp, label)
        val score = SphereImageMetrics.ghostingScoreAtOffset(bmp, region, dx, tolerance = 2)
        Log.i(TAG, "$label ghost@$dx=$score")
        return score
    }

    /** A1: fast rotational SSD accepts and corrects moderate IMU yaw error. */
    @Test
    fun fastAlign_correctsModerateYawError() {
        val base = verticalStripeBitmap()
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val shifted = shiftBitmap(base, SHIFT_DX)
        val correctQ = overlapQuat()

        SphereNative.addFrameFast(base, identity, OVERLAP_GATE)
        val status = SphereNative.addFrameFast(
            shifted,
            quatWithYawError(correctQ, 3.0),
            OVERLAP_GATE
        )
        assertTrue("Frame with 3° yaw error should be accepted after fast align", status == 1)

        val info = SphereNative.getLastFrameInfo()
        assertNotNull(info)
        Log.i(
            TAG,
            "A1 alignOffset=${info!!.alignmentOffsetDeg}° residualSSD=${info.residualSSD} warp=(${info.warpTxPx},${info.warpTyPx})"
        )
        assertTrue(
            "Fast align should correct ≥1° of yaw error (was ${info.alignmentOffsetDeg}°)",
            info.alignmentOffsetDeg >= 1.0f
        )
        assertFalse("Corrected frame must not be rejected", info.wasRejected)
    }

    /** A2: extreme misalignment is rejected on the fast path. */
    @Test
    fun fastAlign_rejectsExtremeMisalignment() {
        val base = verticalStripeBitmap()
        SphereNative.addFrameFast(base, floatArrayOf(0f, 0f, 0f, 1f), OVERLAP_GATE)

        val status = SphereNative.addFrameFast(
            shiftBitmap(base, dx = 14),
            quatWithYawError(overlapQuat(), 12.0),
            OVERLAP_GATE
        )
        Log.i(TAG, "A2 reject status=$status info=${SphereNative.getLastFrameInfo()}")
        assertTrue("Extreme misalignment should be rejected (status=$status)", status == 0)
        val info = SphereNative.getLastFrameInfo()
        assertNotNull(info)
        assertTrue("Frame should be marked rejected", info!!.wasRejected)
    }

    /** A3: debounced preview refine (IMU parity) preserves live structure. */
    @Test
    fun previewRefine_imuParity_preservesStructure() {
        val base = verticalStripeBitmap()
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(base, identity, OVERLAP_GATE)
        SphereNative.addFrameFast(
            shiftBitmap(base, SHIFT_DX),
            quatWithYawError(overlapQuat(), 2.0),
            OVERLAP_GATE
        )
        SphereNative.addFrameFast(
            shiftBitmap(base, SHIFT_DX + 2),
            quatWithYawError(overlapQuat(18.0), -2.5),
            OVERLAP_GATE
        )

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)

        val ghostFast = ghostAtShift(fastOnly!!, "fastBeforePreview", dx = SHIFT_DX)

        assertTrue("IMU-parity refine must run", SphereNative.refinePending(applyGlobalPose = false))
        val preview = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(preview)
        val ghostAfter = ghostAtShift(preview!!, "fastAfterPreview", dx = SHIFT_DX)

        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostAfter)
        val region = SphereImageMetrics.requireContentRegion(fastOnly, "fastOnly")
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(fastOnly, preview, region)
        Log.i(TAG, "A3 ghostDelta=$ghostDelta correlation=$correlation")
        assertTrue(
            "IMU-parity refine must not add ghost (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGhostRegression()
        )
    }

    /** A4: global pose refine on finish improves shifted overlap registration. */
    @Test
    fun globalRefine_improvesShiftedOverlap() {
        val base = verticalStripeBitmap()
        SphereNative.addFrameFast(base, floatArrayOf(0f, 0f, 0f, 1f), OVERLAP_GATE)
        SphereNative.addFrameFast(shiftBitmap(base, SHIFT_DX), overlapQuat(), OVERLAP_GATE)

        val before = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(before)
        val ghostBefore = ghostAtShift(before!!, "beforeGlobal")

        assertTrue("Global refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val after = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(after)
        val ghostAfter = ghostAtShift(after!!, "afterGlobal")

        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostBefore, ghostAfter)
        Log.i(TAG, "A4 ghost before=$ghostBefore after=$ghostAfter delta=$ghostDelta bankSSD=${SphereNative.evalOverlapBankSSD()}")
        assertTrue(
            "Global refine ghost delta must be ≤ ${SphereRefinePolicy.maxGlobalRefineGhostDelta()} (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGlobalRefineGhostDelta()
        )
    }
}
