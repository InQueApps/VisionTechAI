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
 * Ghosting metric + refine quality for misaligned overlap (shift and IMU noise).
 */
@RunWith(AndroidJUnit4::class)
class SphereGhostingE2ETest {

    companion object {
        private const val TAG = "SphereGhostingE2ETest"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480

        private const val SHIFT_DX = 8
        private const val MIN_RELATIVE_GHOST_INCREASE = 1.08
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

    private fun measureOffsetGhost(bmp: Bitmap, label: String, offsetDx: Int = SHIFT_DX): Double {
        val region = SphereImageMetrics.requireContentRegion(bmp, label)
        val score = SphereImageMetrics.ghostingScoreAtOffset(bmp, region, offsetDx, tolerance = 2)
        Log.i(TAG, "$label offsetGhost@$offsetDx=$score")
        return score
    }

    private fun measureSharpness(bmp: Bitmap, label: String): Double {
        val region = SphereImageMetrics.requireContentRegion(bmp, label)
        val sharp = SphereImageMetrics.laplacianVariance(bmp, region)
        Log.i(TAG, "$label sharpness=$sharp")
        return sharp
    }

    @Test
    fun ghostMetric_shiftedOverlap_exceedsSingleFrame() {
        val base = verticalStripeBitmap()
        val q = floatArrayOf(0f, 0f, 0f, 1f)

        SphereNative.addFrameFast(base, q, 0.08f)
        val single = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(single)
        val ghostSingle = measureOffsetGhost(single!!, "singleFrame")

        SphereNative.addFrameFast(shiftBitmap(base, dx = SHIFT_DX), overlapQuat(), 0.08f)
        val overlapped = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(overlapped)
        val ghostShifted = measureOffsetGhost(overlapped!!, "shiftedOverlap")

        assertTrue(
            "Shifted overlap should increase offset ghost (single=$ghostSingle shifted=$ghostShifted)",
            ghostShifted >= ghostSingle * MIN_RELATIVE_GHOST_INCREASE
        )
    }

    @Test
    fun shiftedOverlap_refinePending_improvesSharpness() {
        val base = verticalStripeBitmap()
        val q = floatArrayOf(0f, 0f, 0f, 1f)

        SphereNative.addFrameFast(base, q, 0.08f)
        SphereNative.addFrameFast(shiftBitmap(base, dx = SHIFT_DX), overlapQuat(), 0.08f)

        val before = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(before)
        val sharpBefore = measureSharpness(before!!, "beforeRefine")
        val ghostBefore = measureOffsetGhost(before, "beforeRefine")

        assertTrue("Refine must run", SphereNative.refinePending())
        val after = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(after)
        val sharpAfter = measureSharpness(after!!, "afterRefine")
        val ghostAfter = measureOffsetGhost(after, "afterRefine")

        val sharpRetention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "refine sharpRetention=$sharpRetention ghostBefore=$ghostBefore ghostAfter=$ghostAfter")
        assertTrue(
            "Refine should retain/improve sharpness (retention=$sharpRetention)",
            sharpRetention >= MIN_REFINE_SHARPNESS_RETENTION
        )
    }

    @Test
    fun imuNoise_shiftedOverlap_refineImprovesQuality() {
        val frame = verticalStripeBitmap()
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(frame, q0, 0.08f)

        listOf(
            shiftBitmap(frame, 6) to quatWithYawError(overlapQuat(), 2.5),
            shiftBitmap(frame, 7) to quatWithYawError(overlapQuat(), -3.5),
            shiftBitmap(frame, 5) to quatWithYawError(overlapQuat(18.0), 5.0)
        ).forEach { (bmp, q) -> SphereNative.addFrameFast(bmp, q, 0.08f) }

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        val sharpFast = measureSharpness(fastOnly!!, "imuFast")
        val ghostFast = measureOffsetGhost(fastOnly, "imuFast", offsetDx = 6)

        assertTrue("Refine must run", SphereNative.refinePending())
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val sharpRefined = measureSharpness(refined!!, "imuRefined")
        val ghostRefined = measureOffsetGhost(refined, "imuRefined", offsetDx = 6)

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpFast, sharpRefined)
        Log.i(TAG, "IMU refine retention=$retention ghostFast=$ghostFast ghostRefined=$ghostRefined")
        assertTrue(
            "Refine should improve shifted+noisy session sharpness (retention=$retention)",
            retention >= MIN_REFINE_SHARPNESS_RETENTION
        )
    }

    @Test
    fun globalRefine_broadGhost_withinFinishExportGate() {
        val base = verticalStripeBitmap()
        SphereNative.addFrameFast(base, floatArrayOf(0f, 0f, 0f, 1f), 0.08f)
        SphereNative.addFrameFast(
            shiftBitmap(base, SHIFT_DX),
            overlapQuat(),
            0.08f
        )

        val fast = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fast)
        val region = SphereImageMetrics.requireContentRegion(fast!!, "ghostGlobalFast")
        val ghostFast = SphereImageMetrics.ghostingScore(fast, region)

        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = SphereImageMetrics.ghostingScore(refined!!, region)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        Log.i(TAG, "global ghost fast=$ghostFast refined=$ghostRefined delta=$ghostDelta")
        assertTrue(
            "Global refine ghost delta must be ≤ ${SphereRefinePolicy.maxFinishExportGhostDelta()} (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta()
        )
    }
}
