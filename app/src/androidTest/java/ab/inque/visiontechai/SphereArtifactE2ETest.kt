package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Tests that detect black/dark line artifacts in the sphere output.
 *
 * The sphere engine splats camera frames into an equirectangular buffer.
 * Artifacts appear as isolated dark pixels or dark lines surrounded by
 * content pixels — caused by gaps in the splat pattern, bad bilinear
 * sampling of zero-weight pixels, or stride-based gaps.
 *
 * These tests exercise both the fast path (addFrameFast) and the full
 * alignment path (addFrame), checking equirect output, downscaled output,
 * and rectilinear views for unexpected dark artifacts.
 */
@RunWith(AndroidJUnit4::class)
class SphereArtifactE2ETest {

    companion object {
        private const val TAG = "SphereArtifactE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024

        private const val DARK_FALLBACK = 0x1A1A2E
        private const val DARK_THRESHOLD = 30

        /**
         * Maximum allowed percentage of "dark artifact" pixels in the stitched
         * region. A dark artifact is a pixel whose luminance is far below its
         * neighbors, indicating a gap that wasn't properly filled.
         */
        private const val MAX_ARTIFACT_PERCENT = 0.5f
    }

    @Before
    fun setup() {
        assumeTrue("Native lib required", SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
    }

    @After
    fun teardown() {
        SphereNative.release()
    }

    /**
     * Creates a gradient bitmap that has realistic variation (not solid color)
     * so artifacts are clearly visible against the content.
     */
    private fun gradientBitmap(w: Int = 640, h: Int = 480, baseHue: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val hue = (baseHue + (x.toFloat() / w) * 60f) % 360f
                val sat = 0.7f + 0.3f * (y.toFloat() / h)
                val value = 0.6f + 0.4f * (x.toFloat() / w)
                pixels[y * w + x] = Color.HSVToColor(floatArrayOf(hue, sat, value))
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Scans a bitmap for dark artifact pixels.
     * A pixel is a "dark artifact" if:
     * 1. It lies within a stitched region (not in the uncovered background)
     * 2. Its luminance is far below the average of its non-dark neighbors
     *
     * Returns the percentage of content pixels that are artifacts.
     */
    private fun measureArtifactRate(bmp: Bitmap, label: String): Float {
        val w = bmp.width
        val h = bmp.height
        var contentPixels = 0
        var artifactPixels = 0

        for (y in 1 until h - 1) {
            for (x in 0 until w) {
                val p = bmp.getPixel(x, y)
                if (!isContentPixel(p)) continue

                contentPixels++

                val lum = luminance(p)

                // Check if this pixel is abnormally dark compared to neighbors
                var neighborSum = 0f
                var neighborCount = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (x + dx + w) % w
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        val np = bmp.getPixel(nx, ny)
                        if (isContentPixel(np)) {
                            neighborSum += luminance(np)
                            neighborCount++
                        }
                    }
                }

                if (neighborCount < 3) continue

                val neighborAvg = neighborSum / neighborCount
                // A pixel is an artifact if it's very dark and its neighbors are
                // significantly brighter (indicates a gap/hole, not gradual shading)
                if (lum < DARK_THRESHOLD && neighborAvg > lum + 60) {
                    artifactPixels++
                }
            }
        }

        val rate = if (contentPixels > 0) {
            (artifactPixels.toFloat() / contentPixels) * 100f
        } else 0f

        Log.i(TAG, "$label: $artifactPixels artifacts / $contentPixels content = %.3f%%".format(rate))
        return rate
    }

    /**
     * Scans for dark line runs where pixels are much darker than neighbors
     * (true gap artifacts), not merely dark gradient shading.
     */
    private fun measureDarkLineRuns(bmp: Bitmap, label: String): Int {
        val w = bmp.width
        val h = bmp.height
        var longRunCount = 0

        fun isArtifactPixel(x: Int, y: Int): Boolean {
            val p = bmp.getPixel(x, y)
            if (!isContentPixel(p)) return false
            val lum = luminance(p)
            if (lum >= DARK_THRESHOLD) return false
            var neighborSum = 0f
            var neighborCount = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = (x + dx + w) % w
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    val np = bmp.getPixel(nx, ny)
                    if (!isContentPixel(np)) continue
                    neighborSum += luminance(np)
                    neighborCount++
                }
            }
            if (neighborCount < 3) return false
            return neighborSum / neighborCount > lum + 60
        }

        for (y in 1 until h - 1) {
            var runLength = 0
            for (x in 0 until w) {
                if (isArtifactPixel(x, y)) {
                    runLength++
                    if (runLength >= 4) longRunCount++
                } else {
                    runLength = 0
                }
            }
        }

        for (x in 0 until w) {
            var runLength = 0
            for (y in 1 until h - 1) {
                if (isArtifactPixel(x, y)) {
                    runLength++
                    if (runLength >= 4) longRunCount++
                } else {
                    runLength = 0
                }
            }
        }

        Log.i(TAG, "$label: $longRunCount dark line runs (>=4 consecutive gap artifacts)")
        return longRunCount
    }

    private fun isContentPixel(pixel: Int): Boolean {
        if ((pixel ushr 24) == 0) return false
        val rgb = pixel and 0x00FFFFFF
        return rgb != DARK_FALLBACK
    }

    private fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    // ──────────────────────────────────────────────────────────────────────

    /**
     * Fast path (addFrameFast) with multiple frames should produce an equirect
     * output free of dark line artifacts in the stitched region.
     */
    @Test
    fun fastPath_equirect_noBlackLineArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 120f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(80.0)),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(-20.0)),
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val artifactRate = measureArtifactRate(equirect, "fastPath_equirect_fullRes")
        assertTrue(
            "Dark artifact rate %.3f%% exceeds maximum ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(equirect, "fastPath_equirect_fullRes")
        assertTrue(
            "Found $darkLines dark line runs (max 0 allowed in content region)",
            darkLines == 0
        )
    }

    /**
     * Fast path at a DOWNSCALED output resolution (the common preview path)
     * must also be artifact-free.
     */
    @Test
    fun fastPath_downscaledPreview_noBlackLineArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 200f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0)),
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val preview = SphereNative.getEquirectBitmap(1024, 512)!!
        val artifactRate = measureArtifactRate(preview, "fastPath_downscaled_1024x512")
        assertTrue(
            "Downscaled dark artifact rate %.3f%% exceeds maximum ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(preview, "fastPath_downscaled_1024x512")
        assertTrue(
            "Found $darkLines dark line runs in downscaled preview (max 0 allowed)",
            darkLines == 0
        )
    }

    /**
     * Rectilinear view extracted from fast-path content must not show dark lines.
     */
    @Test
    fun fastPath_rectilinearView_noBlackLineArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 60f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        // Look right into the center of where we stitched (azimuth ~20°)
        val recti = SphereNative.getRectilinearBitmap(640, 480, 20f, 0f, 90f)!!
        val artifactRate = measureArtifactRate(recti, "fastPath_rectilinear_20deg")
        assertTrue(
            "Rectilinear dark artifact rate %.3f%% exceeds maximum ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(recti, "fastPath_rectilinear_20deg")
        assertTrue(
            "Found $darkLines dark line runs in rectilinear view (max 0 allowed)",
            darkLines == 0
        )
    }

    /**
     * Full alignment path (addFrame) should also be artifact-free.
     * This path uses stride-1 splatting so should inherently be cleaner.
     */
    @Test
    fun fullAlign_equirect_noBlackLineArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 300f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0)),
        )

        for (q in quats) {
            SphereNative.addFrame(frame, q)
        }

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val artifactRate = measureArtifactRate(equirect, "fullAlign_equirect")
        assertTrue(
            "Full-align dark artifact rate %.3f%% exceeds max ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(equirect, "fullAlign_equirect")
        assertTrue(
            "Found $darkLines dark line runs in full-align equirect (max 0 allowed)",
            darkLines == 0
        )
    }

    /**
     * After background refinement (refinePending), the rebuilt panorama must
     * also be artifact-free.
     */
    @Test
    fun afterRefine_equirect_noBlackLineArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 180f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0)),
        )

        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        // Run background refinement
        SphereNative.refinePending()

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val artifactRate = measureArtifactRate(equirect, "afterRefine_equirect")
        assertTrue(
            "Post-refine dark artifact rate %.3f%% exceeds max ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(equirect, "afterRefine_equirect")
        assertTrue(
            "Found $darkLines dark line runs in post-refine equirect (max 0 allowed)",
            darkLines == 0
        )
    }

    /**
     * Seam refinement should not introduce new dark artifacts.
     */
    @Test
    fun afterSeamRefine_noNewArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 40f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )

        for (q in quats) {
            SphereNative.addFrame(frame, q)
        }

        val beforeSeam = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val rateBefore = measureArtifactRate(beforeSeam, "beforeSeam")

        SphereNative.refineSeams()

        val afterSeam = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val rateAfter = measureArtifactRate(afterSeam, "afterSeam")

        assertTrue(
            "Seam refine should not increase artifacts (before=%.3f%% after=%.3f%%)".format(rateBefore, rateAfter),
            rateAfter <= rateBefore + 0.1f
        )
    }

    /**
     * Edge case: a single frame should produce a clean output with no gaps
     * in its projected region.
     */
    @Test
    fun singleFrame_fastPath_noArtifactsInProjection() {
        val frame = gradientBitmap(640, 480, baseHue = 270f)
        SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0f)

        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)!!
        val artifactRate = measureArtifactRate(equirect, "singleFrame_fastPath")
        assertTrue(
            "Single frame artifact rate %.3f%% exceeds max ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
            artifactRate < MAX_ARTIFACT_PERCENT
        )

        val darkLines = measureDarkLineRuns(equirect, "singleFrame_fastPath")
        assertTrue(
            "Found $darkLines dark line runs from single fast-path frame (max 0)",
            darkLines == 0
        )
    }

    /**
     * Multiple resolutions: the artifact test should pass at various output sizes.
     */
    @Test
    fun multipleOutputResolutions_noArtifacts() {
        val frame = gradientBitmap(640, 480, baseHue = 90f)

        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(40.0)),
        )
        for (q in quats) {
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val resolutions = listOf(512 to 256, 1024 to 512, 2048 to 1024)
        for ((w, h) in resolutions) {
            val bmp = SphereNative.getEquirectBitmap(w, h)!!
            val artifactRate = measureArtifactRate(bmp, "resolution_${w}x${h}")
            assertTrue(
                "Artifact rate at ${w}x${h}: %.3f%% exceeds max ${MAX_ARTIFACT_PERCENT}%%".format(artifactRate),
                artifactRate < MAX_ARTIFACT_PERCENT
            )
            val darkLines = measureDarkLineRuns(bmp, "resolution_${w}x${h}")
            assertTrue(
                "Found $darkLines dark line runs at ${w}x${h} (max 0 allowed)",
                darkLines == 0
            )
        }
    }
}
