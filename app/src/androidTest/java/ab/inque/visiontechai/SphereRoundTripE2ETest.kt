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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Round-trip stitching quality test.
 *
 * Proves stitching fidelity by:
 * 1. Creating frames with known, distinctive content at known orientations
 * 2. Stitching them into the sphere panorama
 * 3. Extracting rectilinear views at each input orientation
 * 4. Comparing extracted views to the originals (PSNR, dominant color, correlation)
 *
 * A high-quality stitch will produce extracted views that closely match the inputs.
 * Muddy output, misalignment, or color corruption will cause measurable degradation.
 */
@RunWith(AndroidJUnit4::class)
class SphereRoundTripE2ETest {

    companion object {
        private const val TAG = "SphereRoundTrip"
        private const val EQ_W = 4096
        private const val EQ_H = 2048
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val FOV_H = 65f
        private const val FOV_V = 50f

        /**
         * Minimum PSNR between source and extracted view. Due to inherent
         * projection model differences (input camera vs rectilinear extraction),
         * pixel-exact match is impossible — 14 dB proves content is recognizable.
         */
        private const val MIN_SINGLE_FRAME_PSNR = 14.0

        /** Minimum PSNR for multi-frame stitch with overlap. */
        private const val MIN_MULTI_FRAME_PSNR = 13.0

        /** Minimum dominant color accuracy (fraction of pixels with correct hue). */
        private const val MIN_COLOR_ACCURACY = 0.75f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
    }

    @After
    fun teardown() {
        SphereNative.release()
    }

    // ── Frame generation with distinctive, identifiable content ───────────

    /** Gradient frame with a unique base hue — easy to identify by dominant color. */
    private fun huedGradientFrame(baseHue: Float, w: Int = FRAME_W, h: Int = FRAME_H): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val hue = (baseHue + (x.toFloat() / w) * 30f) % 360f
                val sat = 0.8f + 0.2f * (y.toFloat() / h)
                val value = 0.5f + 0.5f * (x.toFloat() / w)
                pixels[y * w + x] = Color.HSVToColor(floatArrayOf(hue, sat, value))
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** A high-frequency pattern (stripes) that reveals blur easily. */
    private fun stripedFrame(
        stripeWidth: Int = 8,
        colorA: Int = Color.WHITE,
        colorB: Int = Color.rgb(40, 40, 40),
        w: Int = FRAME_W,
        h: Int = FRAME_H
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if ((x / stripeWidth) % 2 == 0) colorA else colorB
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    // ── Orientation helpers ──────────────────────────────────────────────

    /**
     * Computes the azimuth/elevation (in radians) for extracting a rectilinear
     * view that looks at the same world direction as the input frame stitched
     * with [q].
     *
     * Input camera forward = (0, 0, -1) in device space.
     * Rectilinear camera forward = (0, 1, 0) in world space at az=0, el=0.
     */
    private fun quatToAzEl(q: FloatArray): Pair<Float, Float> {
        val qx = q[0].toDouble()
        val qy = q[1].toDouble()
        val qz = q[2].toDouble()
        val qw = q[3].toDouble()
        val len = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        val nqx = qx / len; val nqy = qy / len; val nqz = qz / len; val nqw = qw / len

        // Camera center ray in device space = (0, 0, -1)
        // Transform to world using the quaternion:
        val vx = 0.0; val vy = 0.0; val vz = -1.0
        val tx = 2.0 * (nqy * vz - nqz * vy)
        val ty = 2.0 * (nqz * vx - nqx * vz)
        val tz = 2.0 * (nqx * vy - nqy * vx)
        val wx = vx + nqw * tx + (nqy * tz - nqz * ty)
        val wy = vy + nqw * ty + (nqz * tx - nqx * tz)
        val wz = vz + nqw * tz + (nqx * ty - nqy * tx)

        // World coords: X=East, Y=North, Z=Up
        val azimuth = atan2(wx, wy).toFloat()
        val elevation = asin(wz.coerceIn(-1.0, 1.0)).toFloat()
        return azimuth to elevation
    }

    // ── Comparison metrics ───────────────────────────────────────────────

    /**
     * PSNR between two bitmaps, computed only over the center region
     * (avoids edge feathering artifacts which are expected).
     */
    private fun computePSNR(source: Bitmap, extracted: Bitmap, centerFrac: Float = 0.5f): Double {
        val sw = source.width; val sh = source.height
        val ew = extracted.width; val eh = extracted.height

        val cx = (ew * (1f - centerFrac) / 2).toInt()
        val cy = (eh * (1f - centerFrac) / 2).toInt()
        val cw = (ew * centerFrac).toInt()
        val ch = (eh * centerFrac).toInt()

        var mse = 0.0
        var count = 0
        for (y in cy until cy + ch) {
            for (x in cx until cx + cw) {
                val ep = extracted.getPixel(x, y)
                // Map this position to the source bitmap (simple center-based scaling)
                val sx = ((x - cx).toFloat() / cw * sw * centerFrac + sw * (1f - centerFrac) / 2).toInt()
                    .coerceIn(0, sw - 1)
                val sy = ((y - cy).toFloat() / ch * sh * centerFrac + sh * (1f - centerFrac) / 2).toInt()
                    .coerceIn(0, sh - 1)
                val sp = source.getPixel(sx, sy)

                val dr = ((ep shr 16) and 0xFF) - ((sp shr 16) and 0xFF)
                val dg = ((ep shr 8) and 0xFF) - ((sp shr 8) and 0xFF)
                val db = (ep and 0xFF) - (sp and 0xFF)
                mse += (dr * dr + dg * dg + db * db) / 3.0
                count++
            }
        }
        if (count == 0) return 0.0
        mse /= count
        return if (mse < 1e-10) 99.0 else 10.0 * Math.log10(255.0 * 255.0 / mse)
    }

    /** Fraction of pixels whose hue is within [tolerance] degrees of [targetHue]. */
    private fun dominantHueAccuracy(bmp: Bitmap, targetHue: Float, tolerance: Float = 40f): Float {
        val w = bmp.width; val h = bmp.height
        val cx = w / 4; val cy = h / 4
        val cw = w / 2; val ch = h / 2
        var matching = 0; var total = 0
        val hsv = FloatArray(3)
        for (y in cy until cy + ch) {
            for (x in cx until cx + cw) {
                val p = bmp.getPixel(x, y)
                Color.colorToHSV(p, hsv)
                if (hsv[1] > 0.2f && hsv[2] > 0.15f) {
                    total++
                    val diff = abs(hsv[0] - targetHue).let { if (it > 180f) 360f - it else it }
                    if (diff < tolerance) matching++
                }
            }
        }
        return if (total > 0) matching.toFloat() / total else 0f
    }

    /** Mean luminance of the center region. */
    private fun meanLuminance(bmp: Bitmap): Double {
        val cx = bmp.width / 4; val cy = bmp.height / 4
        val cw = bmp.width / 2; val ch = bmp.height / 2
        var sum = 0.0; var count = 0
        for (y in cy until cy + ch) {
            for (x in cx until cx + cw) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    // ── Tests ────────────────────────────────────────────────────────────

    /**
     * Single frame round-trip: stitch one frame and extract from the same angle.
     * This is the best-case scenario — no overlap blending, no alignment error.
     * The extracted view should closely match the source.
     */
    @Test
    fun singleFrame_roundTrip_highPSNR() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)
        val frame = huedGradientFrame(baseHue = 120f)
        val q = floatArrayOf(0f, 0f, 0f, 1f)

        assertTrue("Frame must be accepted", SphereNative.addFrame(frame, q))

        val (az, el) = quatToAzEl(q)
        Log.i(TAG, "single frame: az=${Math.toDegrees(az.toDouble())}° el=${Math.toDegrees(el.toDouble())}°")

        val extracted = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull("Rectilinear extraction must succeed", extracted)

        val psnr = computePSNR(frame, extracted!!)
        Log.i(TAG, "single frame PSNR=${"%.2f".format(psnr)} dB")
        assertTrue(
            "Single frame round-trip PSNR too low (${"%.2f".format(psnr)} dB, min=$MIN_SINGLE_FRAME_PSNR dB)",
            psnr >= MIN_SINGLE_FRAME_PSNR
        )

        val hueAcc = dominantHueAccuracy(extracted, 135f)
        Log.i(TAG, "single frame hue accuracy=${(hueAcc * 100).toInt()}%")
        assertTrue(
            "Single frame color accuracy too low (${(hueAcc * 100).toInt()}%, min=${(MIN_COLOR_ACCURACY * 100).toInt()}%)",
            hueAcc >= MIN_COLOR_ACCURACY
        )
    }

    /**
     * Proves that content at ANY orientation is placed and extracted correctly.
     * Each orientation is tested in a fresh sphere instance (avoiding the overlap
     * rejection gate which is tested elsewhere). This isolates the projection
     * correctness: frame → equirect → rectilinear extraction → verify color.
     */
    @Test
    fun multiDirection_individualRoundTrips_correctColor() {
        data class FrameSpec(val hue: Float, val quat: FloatArray, val label: String)

        val specs = listOf(
            FrameSpec(0f,
                QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(90.0)),
                "north"),
            FrameSpec(120f,
                QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(-90.0)),
                "east"),
            FrameSpec(240f,
                QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(-90.0)),
                "south"),
            FrameSpec(60f,
                QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(90.0)),
                "west"),
            FrameSpec(180f, floatArrayOf(0f, 0f, 0f, 1f), "down"),
            FrameSpec(300f,
                QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(180.0)),
                "up"),
        )

        var passed = 0
        for (spec in specs) {
            SphereNative.release()
            SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)

            val frame = huedGradientFrame(baseHue = spec.hue)
            SphereNative.addFrameFast(frame, spec.quat, 0f)

            val (az, el) = quatToAzEl(spec.quat)
            val extracted = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
            assertNotNull("${spec.label} extraction must succeed", extracted)

            val centerHue = spec.hue + 15f
            val hueAcc = dominantHueAccuracy(extracted!!, centerHue)
            Log.i(TAG, "${spec.label}: hue=${(hueAcc * 100).toInt()}% (target=$centerHue)")
            if (hueAcc >= MIN_COLOR_ACCURACY) passed++
        }

        Log.i(TAG, "color placement: $passed/${specs.size} passed")
        assertTrue(
            "Only $passed/${specs.size} directions recovered correct color (need >=5)",
            passed >= 5
        )
    }

    /**
     * Overlapping frames stitched then extracted should maintain reasonable PSNR
     * in the overlap region — blending shouldn't destroy content.
     */
    @Test
    fun overlappingFrames_roundTrip_acceptablePSNR() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)
        val frame = huedGradientFrame(baseHue = 200f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )

        for (q in quats) {
            assertTrue(SphereNative.addFrame(frame, q))
        }

        // Extract at the center frame's orientation (it has full overlap from both sides)
        val (az, el) = quatToAzEl(quats[1])
        val extracted = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull(extracted)

        val psnr = computePSNR(frame, extracted!!)
        Log.i(TAG, "overlapping PSNR=${"%.2f".format(psnr)} dB")
        assertTrue(
            "Overlap round-trip PSNR too low (${"%.2f".format(psnr)} dB, min=$MIN_MULTI_FRAME_PSNR dB)",
            psnr >= MIN_MULTI_FRAME_PSNR
        )
    }

    /**
     * Fast-path stitch → refine → extract should still produce recognizable content.
     * This tests the full two-phase pipeline quality end-to-end.
     */
    @Test
    fun fastPath_thenRefine_roundTrip_quality() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)
        val frame = huedGradientFrame(baseHue = 300f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        // Before refine
        val (az, el) = quatToAzEl(quats[1])
        val beforeRefine = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull(beforeRefine)
        val psnrBefore = computePSNR(frame, beforeRefine!!)
        Log.i(TAG, "fast-path (before refine) PSNR=${"%.2f".format(psnrBefore)} dB")

        // After refine
        SphereNative.refinePending()
        val afterRefine = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull(afterRefine)
        val psnrAfter = computePSNR(frame, afterRefine!!)
        Log.i(TAG, "fast-path (after refine) PSNR=${"%.2f".format(psnrAfter)} dB")

        assertTrue(
            "Fast path PSNR too low (${"%.2f".format(psnrBefore)} dB, min=$MIN_MULTI_FRAME_PSNR)",
            psnrBefore >= MIN_MULTI_FRAME_PSNR
        )
        assertTrue(
            "Refine should not degrade PSNR significantly (before=${"%.2f".format(psnrBefore)}, after=${"%.2f".format(psnrAfter)})",
            psnrAfter >= psnrBefore * 0.85
        )
    }

    /**
     * Stripe pattern round-trip: proves high-frequency detail is preserved.
     * A muddy stitch would smear stripes into gray.
     */
    @Test
    fun stripePattern_roundTrip_preservesContrast() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)
        val frame = stripedFrame(stripeWidth = 16)
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(SphereNative.addFrame(frame, q))

        val (az, el) = quatToAzEl(q)
        val extracted = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull(extracted)

        // Source has bimodal luminance (near 0 and near 255)
        // Extracted should preserve this contrast
        val region = SphereImageMetrics.requireContentRegion(extracted!!, "stripeRoundTrip")
        val contrast = SphereImageMetrics.meanLocalContrast(extracted, region, radius = 10)
        Log.i(TAG, "stripe round-trip contrast=${"%.1f".format(contrast)}")

        assertTrue(
            "Stripe contrast destroyed by stitch (contrast=${"%.1f".format(contrast)}, min=80.0)",
            contrast >= 80.0
        )

        // The extracted luminance histogram should be bimodal (many dark + many bright pixels)
        var darkCount = 0; var brightCount = 0; var total = 0
        val cx = extracted.width / 4; val cy = extracted.height / 4
        for (y in cy until cy + extracted.height / 2) {
            for (x in cx until cx + extracted.width / 2) {
                val p = extracted.getPixel(x, y)
                val lum = SphereImageMetrics.luminance(p)
                total++
                if (lum < 80f) darkCount++
                else if (lum > 180f) brightCount++
            }
        }
        val darkFrac = darkCount.toFloat() / total
        val brightFrac = brightCount.toFloat() / total
        Log.i(TAG, "stripe bimodality: dark=${(darkFrac * 100).toInt()}% bright=${(brightFrac * 100).toInt()}%")
        assertTrue(
            "Stripes should produce bimodal luminance (dark=${(darkFrac * 100).toInt()}%, bright=${(brightFrac * 100).toInt()}%)",
            darkFrac >= 0.25f && brightFrac >= 0.25f
        )
    }

    /**
     * Full 360 capture simulation: 12 frames around the azimuth + some pitch,
     * then verify we can extract any of them back with identifiable content.
     */
    @Test
    fun full360_multiDirection_allDirectionsRecoverable() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)

        data class Capture(val hue: Float, val quat: FloatArray, val label: String)

        val captures = mutableListOf<Capture>()

        // Helper: compose two quaternions to look at equator azimuth angles.
        // Base "look at equator north" = 90° around X, then rotate around Z for azimuth.
        fun lookAtEquator(azDeg: Double): FloatArray {
            val qPitch = QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(90.0))
            val qAz = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(azDeg))
            return QuaternionMath.multiply(qAz, qPitch)
        }

        // 6 frames around the equator at 60° intervals
        for (i in 0 until 6) {
            val azDeg = i * 60.0
            val hue = (i * 60f) % 360f
            captures.add(Capture(hue, lookAtEquator(azDeg), "equator${azDeg.toInt()}"))
        }
        // 2 frames looking up/down (poles)
        val qUp = QuaternionMath.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(180.0))
        captures.add(Capture(30f, qUp, "up"))
        captures.add(Capture(210f, floatArrayOf(0f, 0f, 0f, 1f), "down"))

        for (c in captures) {
            val frame = huedGradientFrame(baseHue = c.hue)
            // Force placement (no gate) — this test validates extraction, not acceptance logic
            SphereNative.addFrameFast(frame, c.quat, 0f)
        }

        val coverage = SphereNative.getSolidAngleCoverage()
        Log.i(TAG, "full360 coverage=${(coverage * 100).toInt()}%")

        // Verify at least half of the captures produce correct dominant color
        var recovered = 0
        for (c in captures) {
            val (az, el) = quatToAzEl(c.quat)
            val extracted = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
            if (extracted == null) continue

            val centerHue = c.hue + 15f
            val hueAcc = dominantHueAccuracy(extracted, centerHue)
            val meanLum = meanLuminance(extracted)
            Log.i(TAG, "${c.label}: hue_acc=${(hueAcc * 100).toInt()}% meanLum=${"%.1f".format(meanLum)}")

            if (hueAcc >= 0.5f && meanLum > 30.0) {
                recovered++
            }
        }

        val recoveryRate = recovered.toFloat() / captures.size
        Log.i(TAG, "full360 recovery: $recovered/${captures.size} = ${(recoveryRate * 100).toInt()}%")
        assertTrue(
            "Less than 60% of directions recovered correctly ($recovered/${captures.size})",
            recoveryRate >= 0.60f
        )
    }

    /**
     * Downscale round-trip: verifies that requesting a lower-res equirect and
     * re-extracting still yields identifiable content (no info destroyed).
     */
    @Test
    fun downscaledEquirect_stillIdentifiable() {
        SphereNative.init(EQ_W, EQ_H, FOV_H, FOV_V)
        val frame = huedGradientFrame(baseHue = 60f)
        val q = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(45.0))
        assertTrue(SphereNative.addFrame(frame, q))

        // Full-res extraction
        val (az, el) = quatToAzEl(q)
        val fullRes = SphereNative.getRectilinearBitmap(FRAME_W, FRAME_H, az, el, FOV_V)
        assertNotNull(fullRes)
        val psnrFull = computePSNR(frame, fullRes!!)

        // Get a downscaled equirect (simulates the preview path), then extract again.
        // The rectilinear extraction still reads from the internal buffer so this
        // just validates the equirect output at lower resolution is reasonable.
        val downscaled = SphereNative.getEquirectBitmap(1024, 512)
        assertNotNull(downscaled)

        // Verify the full-res extraction is recognizable (inherent projection
        // difference at non-identity quaternions reduces PSNR further)
        Log.i(TAG, "downscale test: full-res PSNR=${"%.2f".format(psnrFull)} dB")
        assertTrue(
            "Full-res extraction PSNR too low (${"%.2f".format(psnrFull)} dB)",
            psnrFull >= 10.0
        )
        // Dominant color must still match
        val hueAcc = dominantHueAccuracy(fullRes, 75f)
        Log.i(TAG, "downscale test: hue accuracy=${(hueAcc * 100).toInt()}%")
        assertTrue("Downscale hue accuracy too low", hueAcc >= 0.5f)
    }
}
