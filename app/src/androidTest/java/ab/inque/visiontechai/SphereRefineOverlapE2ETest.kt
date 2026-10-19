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
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Refine overlap regression suite (R1–R7 from SPHERE_REFINE_OVERLAP_PLAN.md).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SphereRefineOverlapE2ETest {

    companion object {
        private const val TAG = "SphereRefineOverlapE2E"
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

    private fun stitchTwoShiftedFrames(): Bitmap? {
        val base = verticalStripeBitmap()
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(base, q, OVERLAP_GATE)
        SphereNative.addFrameFast(shiftBitmap(base, dx = SHIFT_DX), overlapQuat(), OVERLAP_GATE)
        return SphereNative.getEquirectBitmap(EQ_W, EQ_H)
    }

    private fun ghostAtShift(bmp: Bitmap, label: String, dx: Int = SHIFT_DX): Double {
        val region = SphereImageMetrics.requireContentRegion(bmp, label)
        val score = SphereImageMetrics.ghostingScoreAtOffset(bmp, region, dx, tolerance = 2)
        Log.i(TAG, "$label ghost@$dx=$score")
        return score
    }

    private fun sharpness(bmp: Bitmap, label: String): Double {
        val region = SphereImageMetrics.requireContentRegion(bmp, label)
        val s = SphereImageMetrics.laplacianVariance(bmp, region)
        Log.i(TAG, "$label sharpness=$s")
        return s
    }

    /** R1: refine must not worsen ghost vs fast-only; structure preserved via correlation. */
    @Test
    fun fastRefine_noRegression() {
        val fastOnly = stitchTwoShiftedFrames()
        assertNotNull(fastOnly)
        val ghostFast = ghostAtShift(fastOnly!!, "fastOnly")
        val sharpFast = sharpness(fastOnly, "fastOnly")

        assertTrue("Refine must run", SphereNative.refinePending())
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = ghostAtShift(refined!!, "refined")
        val sharpRefined = sharpness(refined, "refined")

        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpFast, sharpRefined)
        val region = SphereImageMetrics.requireContentRegion(fastOnly, "fastOnly")
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(fastOnly, refined, region)
        Log.i(TAG, "R1 ghostFast=$ghostFast ghostRefined=$ghostRefined delta=$ghostDelta retention=$retention correlation=$correlation")

        assertTrue(
            "Refine ghost delta must be ≤ ${SphereRefinePolicy.maxGhostRegression()} (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGhostRegression()
        )
        assertTrue(
            "Fast↔refine correlation must be ≥ ${SphereRefinePolicy.minFastRefineCorrelation()} (was $correlation)",
            correlation >= SphereRefinePolicy.minFastRefineCorrelation()
        )
    }

    /** R2: IMU-parity refine should match or improve shifted overlap (same path as R1). */
    @Test
    fun refine_improvesShiftedOverlap() {
        val fastOnly = stitchTwoShiftedFrames()
        assertNotNull(fastOnly)
        val ghostFast = ghostAtShift(fastOnly!!, "beforeRefine")

        assertTrue("Refine must run", SphereNative.refinePending())
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = ghostAtShift(refined!!, "afterRefine")

        val improvedRatio = ghostRefined / ghostFast.coerceAtLeast(1e-6)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        Log.i(TAG, "R2 ghostFast=$ghostFast ghostRefined=$ghostRefined ratio=$improvedRatio delta=$ghostDelta")
        assertTrue(
            "Refine must not add ghost (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGhostRegression()
        )
        val improved = ghostRefined <= ghostFast * SphereRefinePolicy.minRefineGhostImprovementRatio()
        val bounded = ghostRefined <= SphereRefinePolicy.maxAbsoluteGhostAfterRefine()
        val matched = ghostRefined <= ghostFast
        assertTrue(
            "Refine should improve, bound, or match ghost (refined=$ghostRefined fast=$ghostFast)",
            improved || bounded || matched
        )
    }

    /** R3: noisy IMU session — refine must not add ghost. */
    @Test
    fun imuNoise_refineNotWorse() {
        val frame = verticalStripeBitmap()
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(frame, q0, OVERLAP_GATE)

        listOf(
            shiftBitmap(frame, 6) to quatWithYawError(overlapQuat(), 2.5),
            shiftBitmap(frame, 7) to quatWithYawError(overlapQuat(), -3.5),
            shiftBitmap(frame, 5) to quatWithYawError(overlapQuat(18.0), 5.0)
        ).forEach { (bmp, q) -> SphereNative.addFrameFast(bmp, q, OVERLAP_GATE) }

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        val ghostFast = ghostAtShift(fastOnly!!, "imuFast", dx = 6)

        assertTrue("Refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = ghostAtShift(refined!!, "imuRefined", dx = 6)

        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        Log.i(TAG, "R3 ghostDelta=$ghostDelta fast=$ghostFast refined=$ghostRefined")
        assertTrue(
            "IMU-noise refine must not add ghost (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGhostRegression()
        )
    }

    /** R7: global pose refine runs; ghost delta stays within global finish gate (A4 parity). */
    @Test
    fun globalPose_refineImprovesOverlap() {
        val base = verticalStripeBitmap()
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(base, q, OVERLAP_GATE)
        SphereNative.addFrameFast(shiftBitmap(base, dx = SHIFT_DX), overlapQuat(), OVERLAP_GATE)

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        val ghostFast = ghostAtShift(fastOnly!!, "fastBeforeGlobal")

        val fastInfo = SphereNative.getLastFrameInfo()
        assertNotNull(fastInfo)
        Log.i(TAG, "R7 fast warp=(${fastInfo!!.warpTxPx},${fastInfo.warpTyPx})")

        assertTrue("Global refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = ghostAtShift(refined!!, "fastAfterGlobal")

        val refineInfo = SphereNative.getLastFrameInfo()
        assertNotNull(refineInfo)
        Log.i(TAG, "R7 post-refine warp=(${refineInfo!!.warpTxPx},${refineInfo.warpTyPx}) align=${refineInfo.alignmentOffsetDeg}")

        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        assertTrue(
            "Global refine ghost delta must be ≤ ${SphereRefinePolicy.maxGlobalRefineGhostDelta()} (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGlobalRefineGhostDelta()
        )
    }

    /** R4: second refine after more frames must not break overlap at the window boundary. */
    @Test
    fun multiRefine_windowRealigns() {
        val frame = verticalStripeBitmap()
        val gate = OVERLAP_GATE
        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), gate)
        for (i in 1 until 4) {
            val q = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 5.0))
            SphereNative.addFrameFast(frame, q, gate)
        }
        assertTrue("First refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val afterFirst = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(afterFirst)

        for (i in 4 until 8) {
            val q = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 5.0))
            SphereNative.addFrameFast(frame, q, gate)
        }
        assertTrue("Second refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val afterSecond = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(afterSecond)

        val ghostFirst = ghostAtShift(afterFirst!!, "afterFirstRefine", dx = SHIFT_DX)
        val ghostSecond = ghostAtShift(afterSecond!!, "afterSecondRefine", dx = SHIFT_DX)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFirst, ghostSecond)
        Log.i(
            TAG,
            "R4 ghostFirst=$ghostFirst ghostSecond=$ghostSecond delta=$ghostDelta"
        )
        assertTrue(
            "Second refine must not add ghost (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxGlobalRefineGhostDelta()
        )
        assertTrue(
            "Second refine ghost must stay near first-refine baseline (second=$ghostSecond first=$ghostFirst)",
            ghostSecond <= ghostFirst + SphereRefinePolicy.maxGlobalRefineGhostDelta()
        )
    }

    /** R6: finish seam pass after global refine must preserve the refined equirect. */
    @Test
    fun finish_matchesRefinedPreview() {
        val frame = verticalStripeBitmap()
        val gate = OVERLAP_GATE
        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), gate)
        for (i in 1 until 6) {
            val q = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 4.5))
            SphereNative.addFrameFast(frame, q, gate)
        }
        assertTrue("Global refine must run", SphereNative.refinePending(applyGlobalPose = true))
        val beforeSeams = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(beforeSeams)
        assertTrue("Finish seam pass must run", SphereNative.refineSeams())
        val afterSeams = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(afterSeams)
        val region = SphereImageMetrics.requireContentRegion(beforeSeams!!, "beforeSeams")
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(
            beforeSeams, afterSeams!!, region
        )
        Log.i(TAG, "R6 pre↔post-seam correlation=$correlation")
        assertTrue(
            "Finish seam pass must preserve refined preview ≥ ${SphereRefinePolicy.minFinishPreviewCorrelation()} (was $correlation)",
            correlation >= SphereRefinePolicy.minFinishPreviewCorrelation()
        )
    }

    /** R5: periodic mid-capture refine (worker path) must run without R1 regression. */
    @Test
    fun r5_periodicRefine_midCapture_noRegression() {
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = SphereNativeOperations,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 50L,
            periodicRefineEvery = 6
        )
        val frame = verticalStripeBitmap()
        var fastSnapshot: Bitmap? = null
        var refinedCount = 0
        val quatBase = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(6) { i ->
            val bmp = if (i == 0) frame else shiftBitmap(frame, SHIFT_DX + i)
            val q = if (i == 0) {
                quatBase
            } else {
                QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 12.0))
            }
            val latch = CountDownLatch(1)
            worker.onResult = { result ->
                if (result.refined) {
                    refinedCount++
                } else {
                    if (i == 5) {
                        fastSnapshot = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
                    }
                    latch.countDown()
                }
            }
            worker.submit(bmp, q, photoMode = true)
            assertTrue("frame $i timed out", latch.await(15, TimeUnit.SECONDS))
        }
        val refineDeadline = System.currentTimeMillis() + 5000
        while (refinedCount == 0 && System.currentTimeMillis() < refineDeadline) {
            Thread.sleep(100)
        }
        assertTrue("R5: periodic refine must run after 6 frames (count=$refinedCount)", refinedCount > 0)
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val fast = fastSnapshot
        if (fast != null) {
            val region = SphereImageMetrics.requireContentRegion(fast, "fastBeforePeriodic")
            val ghostFast = SphereImageMetrics.ghostingScore(fast, region)
            val ghostRefined = SphereImageMetrics.ghostingScore(refined!!, region)
            val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
            val correlation = SphereImageMetrics.equirectOverlapCorrelation(fast, refined, region)
            Log.i(TAG, "R5 ghostDelta=$ghostDelta correlation=$correlation")
            assertTrue(
                "R5 ghost delta ≤ ${SphereRefinePolicy.maxGhostRegression()} (delta=$ghostDelta)",
                ghostDelta <= SphereRefinePolicy.maxGhostRegression()
            )
            assertTrue(
                "R5 correlation ≥ ${SphereRefinePolicy.minFastRefineCorrelation()} (was $correlation)",
                correlation >= SphereRefinePolicy.minFastRefineCorrelation()
            )
        }
        worker.shutdown()
        executor.shutdown()
        refineExecutor.shutdown()
    }
}
