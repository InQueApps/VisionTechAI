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
import kotlin.math.sqrt

/**
 * S12: overlap exposure normalization + multiband seam polish.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SphereExposureSeamE2ETest {

    companion object {
        private const val TAG = "SphereExposureSeamE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val OVERLAP_GATE = 0.08f
        /** Max luminance std-dev in overlap after exposure match (mid-gray + dim frame). */
        private const val MAX_OVERLAP_LUMA_STD = 22.0
        private const val MIN_SEAM_SHARPNESS_RETENTION = 0.78
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

    private fun solidBitmap(r: Int, g: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(r, g, b))
        return bmp
    }

    private fun checkerboard(cell: Int = 16): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pixels[y * FRAME_W + x] =
                    if (((x / cell) + (y / cell)) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

    private fun overlapQuat(yawDeg: Double = 16.0): FloatArray =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(yawDeg))

    private fun luminanceStdDev(bmp: Bitmap, region: SphereImageMetrics.Region): Double {
        val xEnd = (region.x0 + region.w).coerceAtMost(bmp.width)
        val yEnd = (region.y0 + region.h).coerceAtMost(bmp.height)
        val values = ArrayList<Double>()
        for (y in region.y0 until yEnd) {
            for (x in region.x0 until xEnd) {
                val p = bmp.getPixel(x, y)
                val l = SphereImageMetrics.luminance(p)
                if (l < 8f) continue
                values.add(l.toDouble())
            }
        }
        if (values.size < 10) return 0.0
        val mean = values.sum() / values.size
        val varSum = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / values.size)
    }

    private fun overlapBandRegion(content: SphereImageMetrics.Region): SphereImageMetrics.Region =
        SphereImageMetrics.Region(
            content.x0 + content.w * 2 / 5,
            content.y0 + content.h * 2 / 5,
            content.w / 5,
            content.h / 5
        )

    /** E1: median overlap exposure match keeps dim second frame close to reference gray. */
    @Test
    fun exposureNormalization_tightensOverlapLuminance() {
        val reference = solidBitmap(140, 140, 140)
        val dimmer = solidBitmap(70, 70, 70)
        val identity = floatArrayOf(0f, 0f, 0f, 1f)

        SphereNative.addFrameFast(reference, identity, OVERLAP_GATE)
        assertTrue(
            SphereNative.addFrameFast(dimmer, overlapQuat(), OVERLAP_GATE) == 1
        )

        val stitched = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(stitched)
        val content = SphereImageMetrics.requireContentRegion(stitched!!, "exposureOverlap")
        val overlapBand = overlapBandRegion(content)
        val std = luminanceStdDev(stitched, overlapBand)
        Log.i(TAG, "E1 overlap-band luma stdDev=$std (max=$MAX_OVERLAP_LUMA_STD)")
        assertTrue(
            "Exposure normalization should tighten overlap luminance (std=$std)",
            std <= MAX_OVERLAP_LUMA_STD
        )
    }

    /** E2: multiband seam pass retains sharpness (no full-image mud). */
    @Test
    fun multibandSeams_retainsSharpness() {
        val frame = checkerboard()
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(frame, q, OVERLAP_GATE)
        SphereNative.addFrameFast(frame, overlapQuat(), OVERLAP_GATE)

        val before = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(before)
        val region = SphereImageMetrics.requireContentRegion(before!!, "preSeam")
        val sharpBefore = SphereImageMetrics.laplacianVariance(before, region)

        assertTrue(SphereNative.refineSeams())
        val after = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(after)
        val sharpAfter = SphereImageMetrics.laplacianVariance(after!!, region)
        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "E2 seam sharpness retention=$retention")
        assertTrue(
            "Multiband seams must retain ≥$MIN_SEAM_SHARPNESS_RETENTION sharpness (was $retention)",
            retention >= MIN_SEAM_SHARPNESS_RETENTION
        )
    }

    /** E3: storing fast-corrected quat must not break R1 IMU-parity refine gate. */
    @Test
    fun fastCorrectedStored_r1RefineStillPasses() {
        val base = checkerboard()
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val err = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(2.5))
        val qOverlap = QuaternionMath.normalize(
            QuaternionMath.multiply(overlapQuat(), err)
        )

        SphereNative.addFrameFast(base, identity, OVERLAP_GATE)
        SphereNative.addFrameFast(base, qOverlap, OVERLAP_GATE)

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        assertTrue("R1 refine must run", SphereNative.refinePending(applyGlobalPose = false))
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)

        val region = SphereImageMetrics.requireContentRegion(fastOnly!!, "fastOnly")
        val correlation = SphereImageMetrics.equirectOverlapCorrelation(fastOnly, refined!!, region)
        Log.i(TAG, "E3 fast↔IMU-parity correlation=$correlation")
        assertTrue(
            "R1 correlation must be ≥ ${SphereRefinePolicy.minFastRefineCorrelation()} (was $correlation)",
            correlation >= SphereRefinePolicy.minFastRefineCorrelation()
        )
    }

    /** E4: stored exposure gains survive L2 global rebuild (dim frame). */
    @Test
    fun storedExposure_survivesGlobalRebuild() {
        val reference = solidBitmap(140, 140, 140)
        val dimmer = solidBitmap(70, 70, 70)
        val identity = floatArrayOf(0f, 0f, 0f, 1f)

        SphereNative.addFrameFast(reference, identity, OVERLAP_GATE)
        SphereNative.addFrameFast(dimmer, overlapQuat(), OVERLAP_GATE)

        val fastOnly = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fastOnly)
        assertTrue("Global rebuild must run", SphereNative.refinePending(applyGlobalPose = true))
        val rebuilt = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(rebuilt)

        val content = SphereImageMetrics.requireContentRegion(fastOnly!!, "fastOnly")
        val overlapBand = overlapBandRegion(content)
        val stdFast = luminanceStdDev(fastOnly, overlapBand)
        val stdRebuilt = luminanceStdDev(rebuilt!!, overlapBand)
        Log.i(TAG, "E4 overlap luma std fast=$stdFast rebuilt=$stdRebuilt")
        assertTrue(
            "Global rebuild must preserve overlap exposure (rebuilt std=$stdRebuilt, fast=$stdFast)",
            stdRebuilt <= stdFast * 1.15 + 3.0
        )
    }
}
