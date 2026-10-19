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
 * Detects muddy / soft stitched output caused by over-blending, low-res refine
 * rebuilds, aggressive seam smoothing, or excessive edge feathering.
 */
@RunWith(AndroidJUnit4::class)
class SphereMuddinessE2ETest {

    companion object {
        private const val TAG = "SphereMuddinessE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480

        private const val MIN_SINGLE_FRAME_SHARPNESS = 0.35
        private const val MIN_REFINE_SHARPNESS_RETENTION = 0.70
        private const val MIN_SEAM_SHARPNESS_RETENTION = 0.80
        private const val MIN_ABSOLUTE_SHARPNESS = 80.0
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
        cell: Int = 12,
        colorA: Int = Color.WHITE,
        colorB: Int = Color.BLACK
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val cellX = x / cell
                val cellY = y / cell
                pixels[y * w + x] = if ((cellX + cellY) % 2 == 0) colorA else colorB
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun measureEquirectSharpness(label: String): Double {
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull("$label: equirect is null", equirect)
        val region = SphereImageMetrics.requireContentRegion(equirect!!, label)
        val lap = SphereImageMetrics.laplacianVariance(equirect, region)
        Log.i(TAG, SphereImageMetrics.formatQuality(label, equirect, region))
        assertTrue("$label: no measurable sharpness in stitched region", lap > 1.0)
        return lap
    }

    @Test
    fun singleFrame_fastPath_retainsSharpness() {
        val frame = checkerboardBitmap()
        val sourceSharpness = SphereImageMetrics.laplacianVariance(
            frame, SphereImageMetrics.Region(FRAME_W / 4, FRAME_H / 4, FRAME_W / 2, FRAME_H / 2)
        )
        Log.i(TAG, "source checkerboard lapVar=$sourceSharpness")

        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0f)
        val outputSharpness = measureEquirectSharpness("singleFrame_fastPath")

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sourceSharpness, outputSharpness)
        Log.i(TAG, "singleFrame sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "Single-frame output too muddy (retention=${"%.3f".format(retention)}, min=$MIN_SINGLE_FRAME_SHARPNESS)",
            retention >= MIN_SINGLE_FRAME_SHARPNESS
        )
    }

    @Test
    fun refinePending_doesNotMuddyPanorama() {
        val frame = checkerboardBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(18.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(36.0)),
        )
        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val sharpBefore = measureEquirectSharpness("beforeRefine")
        assertTrue("Refine must run with dirty frames", SphereNative.refinePending())
        val sharpAfter = measureEquirectSharpness("afterRefine")

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "refinePending sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "refinePending made output muddy (retention=${"%.3f".format(retention)}, min=$MIN_REFINE_SHARPNESS_RETENTION)",
            retention >= MIN_REFINE_SHARPNESS_RETENTION
        )
    }

    @Test
    fun refineSeamsLight_doesNotOverBlur() {
        val frame = checkerboardBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )
        for (q in quats) {
            SphereNative.addFrame(frame, q)
        }

        val sharpBefore = measureEquirectSharpness("beforeSeamLight")
        SphereNative.refineSeams(light = true)
        val sharpAfter = measureEquirectSharpness("afterSeamLight")

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "refineSeamsLight sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "refineSeamsLight over-blurred output (retention=${"%.3f".format(retention)}, min=$MIN_SEAM_SHARPNESS_RETENTION)",
            retention >= MIN_SEAM_SHARPNESS_RETENTION
        )
    }

    @Test
    fun refineSeams_doesNotOverBlur() {
        val frame = checkerboardBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )
        for (q in quats) {
            SphereNative.addFrame(frame, q)
        }

        val sharpBefore = measureEquirectSharpness("beforeSeam")
        SphereNative.refineSeams()
        val sharpAfter = measureEquirectSharpness("afterSeam")

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpBefore, sharpAfter)
        Log.i(TAG, "refineSeams sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "refineSeams over-blurred output (retention=${"%.3f".format(retention)}, min=$MIN_SEAM_SHARPNESS_RETENTION)",
            retention >= MIN_SEAM_SHARPNESS_RETENTION
        )
    }

    @Test
    fun finishPipeline_retainsAcceptableSharpness() {
        val frame = checkerboardBitmap()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )
        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val sharpPre = measureEquirectSharpness("preFinish")
        SphereNative.refinePending()
        val sharpPost = measureEquirectSharpness("postFinish")

        val retention = SphereImageMetrics.sharpnessRetentionRatio(sharpPre, sharpPost)
        Log.i(TAG, "finish pipeline sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "Finish pipeline too muddy (retention=${"%.3f".format(retention)}, min=$MIN_REFINE_SHARPNESS_RETENTION)",
            retention >= MIN_REFINE_SHARPNESS_RETENTION
        )
        assertTrue(
            "Finished panorama absolute sharpness too low (lap=$sharpPost, min=$MIN_ABSOLUTE_SHARPNESS)",
            sharpPost >= MIN_ABSOLUTE_SHARPNESS
        )
    }

    @Test
    fun overlappingFrames_doNotExcessivelyBlur() {
        val frame = checkerboardBitmap()

        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0f)
        val singleSharp = measureEquirectSharpness("singleOverlap")

        SphereNative.reset()
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0f)
        val qOverlap = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(15.0))
        SphereNative.addFrameFast(frame, qOverlap, 0.10f)

        val overlapSharp = measureEquirectSharpness("twoFrameOverlap")
        val retention = SphereImageMetrics.sharpnessRetentionRatio(singleSharp, overlapSharp)
        Log.i(TAG, "overlap sharpness retention=${"%.3f".format(retention)}")
        assertTrue(
            "Overlapping frames excessively blurred (retention=${"%.3f".format(retention)})",
            retention >= 0.55
        )
    }

    @Test
    fun stitchedOutput_preservesContrastAndSaturation() {
        val frame = checkerboardBitmap(
            colorA = Color.rgb(255, 0, 0),
            colorB = Color.rgb(0, 0, 255)
        )
        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0f)

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val region = SphereImageMetrics.requireContentRegion(equirect, "contrastSat")
        val contrast = SphereImageMetrics.meanLocalContrast(equirect, region)
        val saturation = SphereImageMetrics.meanSaturation(equirect, region)
        Log.i(TAG, "contrast=$contrast saturation=$saturation")

        assertTrue("Stitched contrast too low (muddy): $contrast", contrast >= 40.0)
        assertTrue("Stitched saturation collapsed: $saturation", saturation >= 0.45)
    }
}
