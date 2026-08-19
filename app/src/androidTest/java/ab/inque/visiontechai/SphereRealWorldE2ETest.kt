package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.SphereMotionDepth


import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-world-adjacent E2E: textured/parallax/AE fixtures, worker finish at production
 * resolution, tight ghost + overlap-band gates (not stripe synth).
 *
 * Run per-method on ARM device (2048×1024+ OOMs in full class runs).
 */
@RunWith(AndroidJUnit4::class)
class SphereRealWorldE2ETest {

    companion object {
        private const val TAG = "SphereRealWorldE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val PROD_EQ_W = 4096
        private const val PROD_EQ_H = 2048
        private const val OVERLAP_GATE = 0.10f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
        System.gc()
    }

    private fun stitchFramesViaWorker(
        eqW: Int,
        eqH: Int,
        frames: List<Pair<Bitmap, FloatArray>>,
        refineDelayMs: Long = 60_000L
    ): Pair<SphereFinishResult, Bitmap?> {
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineDelayMs = refineDelayMs
        )
        ops.init(eqW, eqH, 65f, 50f)
        frames.forEachIndexed { i, (bmp, q) ->
            val latch = CountDownLatch(1)
            worker.onResult = { if (!it.refined) latch.countDown() }
            worker.submit(bmp, q, photoMode = true)
            assertTrue("frame $i timed out", latch.await(12, TimeUnit.SECONDS))
        }
        val preFinish = ops.getEquirectBitmap(eqW, eqH)
        val finishLatch = CountDownLatch(1)
        var finish: SphereFinishResult? = null
        worker.runFinishGeneration(eqW, eqH) { result ->
            finish = result
            finishLatch.countDown()
        }
        assertTrue("finish timed out", finishLatch.await(60, TimeUnit.SECONDS))
        worker.shutdown()
        executor.shutdown()
        ops.release()
        return finish!! to preFinish
    }

    private fun yawQuat(deg: Double): FloatArray =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(deg))

    /** RW1: worker finish at 2048×1024 with tight export ghost gate. */
    @Test
    fun finishWorker_texturedSession_tightGhostGate() {
        val frames = mutableListOf<Pair<Bitmap, FloatArray>>()
        val base = SphereTestFixtures.indoorTexture(seed = 11)
        frames.add(base to floatArrayOf(0f, 0f, 0f, 1f))
        for (i in 1 until 6) {
            val bmp = SphereTestFixtures.shiftBitmap(base, 4 + i)
            frames.add(bmp to yawQuat(i * 11.0))
        }

        val (finish, preFinish) = stitchFramesViaWorker(EQ_W, EQ_H, frames)
        assertNotNull(finish.equirect)
        assertNotNull(preFinish)

        val content = SphereImageMetrics.requireContentRegion(preFinish!!, "preFinish")
        val band = SphereImageMetrics.overlapBandRegion(content)
        val ghostBefore = SphereImageMetrics.ghostingScore(preFinish, band)
        val ghostAfter = SphereImageMetrics.ghostingScore(finish.equirect!!, band)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostBefore, ghostAfter)
        val bandCorr = SphereImageMetrics.equirectOverlapCorrelation(
            preFinish, finish.equirect!!, band
        )
        Log.i(
            TAG,
            "RW1 ghost before=$ghostBefore after=$ghostAfter delta=$ghostDelta bandCorr=$bandCorr"
        )

        assertTrue(
            "Finish export ghost delta must be ≤ ${SphereRefinePolicy.maxFinishExportGhostDelta()} (delta=$ghostDelta)",
            ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta()
        )
        val stats = SphereNative.getLastRefineStats()
        val ssdImproved = stats != null && stats.ran && stats.applyGlobalPose &&
            stats.ssdAfter <= stats.ssdBefore
        val minBandCorr = if (ssdImproved) 0.70 else SphereRefinePolicy.minFinishOverlapBandCorrelation()
        assertTrue(
            "Overlap band correlation must be ≥ $minBandCorr (was $bandCorr, ssdImproved=$ssdImproved)",
            bandCorr >= minBandCorr
        )
    }

    /** RW2: production resolution finish (skip on low-memory devices). */
    @LargeTest
    @Test
    fun finishWorker_productionResolution_exportQuality() {
        val maxHeap = Runtime.getRuntime().maxMemory()
        assumeTrue("Need ≥256MB heap for 4096×2048 finish", maxHeap >= 256L * 1024 * 1024)

        val frames = mutableListOf<Pair<Bitmap, FloatArray>>()
        val base = SphereTestFixtures.indoorTexture(seed = 22)
        frames.add(base to floatArrayOf(0f, 0f, 0f, 1f))
        for (i in 1 until 5) {
            frames.add(
                SphereTestFixtures.shiftBitmap(base, 5 + i) to yawQuat(i * 14.0)
            )
        }

        val (finish, preFinish) = stitchFramesViaWorker(PROD_EQ_W, PROD_EQ_H, frames)
        assertNotNull(finish.equirect)
        assertNotNull(preFinish)

        val region = SphereImageMetrics.requireContentRegion(preFinish!!, "prodPreFinish")
        val band = SphereImageMetrics.overlapBandRegion(region)
        val ghostBefore = SphereImageMetrics.ghostingScore(preFinish, band)
        val ghostAfter = SphereImageMetrics.ghostingScore(finish.equirect!!, band)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostBefore, ghostAfter)
        Log.i(TAG, "RW2 prod ghost delta=$ghostDelta ${finish.equirect!!.width}x${finish.equirect!!.height}")

        assertTrue(
            "Production finish ghost delta must be ≤ ${SphereRefinePolicy.maxFinishExportGhostDelta()}",
            ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta()
        )
    }

    /** RW3: parallax scene — broad ghost after global finish (unknown shift). */
    @Test
    fun globalFinish_parallax_broadGhostBounded() {
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val gate = OVERLAP_GATE
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val q1 = yawQuat(16.0)
        SphereNative.addFrameFast(
            SphereTestFixtures.parallaxScene(fgShiftPx = 0),
            q0,
            gate
        )
        val shifted = SphereTestFixtures.parallaxScene(fgShiftPx = 12)
        val nearness = SphereMotionDepth.gradientNearnessHeuristic(shifted)
        SphereNative.addFrameFast(shifted, q1, gate, nearness)

        val fast = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fast)
        val region = SphereImageMetrics.requireContentRegion(fast!!, "parallaxFast")
        val ghostFast = SphereImageMetrics.ghostingScore(fast, region)

        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val ghostRefined = SphereImageMetrics.ghostingScore(refined!!, region)
        val ghostDelta = SphereImageMetrics.fastRefineGhostDelta(ghostFast, ghostRefined)
        Log.i(TAG, "RW3 parallax ghost fast=$ghostFast refined=$ghostRefined delta=$ghostDelta")

        assertTrue(
            "Parallax global finish ghost must be ≤ ${SphereRefinePolicy.maxParallaxGhostAfterGlobal()} (was $ghostRefined)",
            ghostRefined <= SphereRefinePolicy.maxParallaxGhostAfterGlobal()
        )
    }

    /** RW4: auto-exposure ramp — overlap band luminance + saturation stable after finish. */
    @Test
    fun globalFinish_aeRamp_overlapStable() {
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val gate = OVERLAP_GATE
        val frameCount = 5
        for (i in 0 until frameCount) {
            val bmp = SphereTestFixtures.aeRampFrame(frameIndex = i, frameCount = frameCount)
            val q = if (i == 0) floatArrayOf(0f, 0f, 0f, 1f) else yawQuat(i * 13.0)
            SphereNative.addFrameFast(bmp, q, gate)
        }

        val fast = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fast)
        val region = SphereImageMetrics.requireContentRegion(fast!!, "aeFast")
        val band = SphereImageMetrics.overlapBandRegion(region)
        val satBefore = SphereImageMetrics.meanSaturation(fast, band)

        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val lumaStd = SphereImageMetrics.luminanceStdDev(refined!!, band)
        val satAfter = SphereImageMetrics.meanSaturation(refined, band)
        val satRetention = SphereImageMetrics.sharpnessRetentionRatio(satBefore, satAfter)
        Log.i(TAG, "RW4 AE lumaStd=$lumaStd satRetention=$satRetention")

        assertTrue(
            "AE ramp overlap luma std must be ≤ ${SphereRefinePolicy.maxAeRampOverlapLumaStd()} (was $lumaStd)",
            lumaStd <= SphereRefinePolicy.maxAeRampOverlapLumaStd()
        )
        assertTrue(
            "AE ramp saturation retention must be ≥ ${SphereRefinePolicy.minAeRampSaturationRetention()}",
            satRetention >= SphereRefinePolicy.minAeRampSaturationRetention()
        )
    }

    /**
     * RW5: deterministic textured session — metrics + optional pinned hash.
     * Set [GOLDEN_INDOOR_SHA256] after first verified ARM run if hash is stable.
     */
    @Test
    fun goldenIndoorSession_metricsAndHash() {
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val gate = OVERLAP_GATE
        val base = SphereTestFixtures.indoorTexture(seed = 99)
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            yawQuat(12.0),
            yawQuat(24.0),
            yawQuat(36.0)
        )
        quats.forEachIndexed { i, q ->
            val bmp = if (i == 0) base else SphereTestFixtures.shiftBitmap(base, 5 + i)
            SphereNative.addFrameFast(bmp, q, gate)
        }
        assertTrue(SphereNative.refinePending(applyGlobalPose = true))
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)

        val region = SphereImageMetrics.requireContentRegion(equirect!!, "golden")
        val sharpness = SphereImageMetrics.laplacianVariance(equirect, region)
        val hash = SphereTestFixtures.downscaledSha256(equirect)
        Log.i(TAG, "RW5 golden hash=$hash sharpness=$sharpness")

        assertTrue("Golden session must retain detail (lap=$sharpness)", sharpness > 12.0)
        assertTrue(hash.length == 64)
        // Pin hash after verified ARM run: Log.i(TAG, "RW5 golden hash=$hash")
    }
}
