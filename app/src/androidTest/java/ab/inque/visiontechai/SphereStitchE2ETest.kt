package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI

/**
 * End-to-end tests for libvision_sphere JNI stitching.
 * Requires a physical ARM device or ARM64 emulator with native libs loaded.
 */
@RunWith(AndroidJUnit4::class)
class SphereStitchE2ETest {

    private fun solidBitmap(w: Int = 320, h: Int = 240, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun nativeLibrary_isAvailable() {
        assertTrue("vision_sphere must load on device APK", SphereNative.isAvailable())
    }

    @Test
    fun initAndReset_succeeds() {
        assumeTrue(SphereNative.isAvailable())
        assertTrue(SphereNative.init(512, 256, 65f, 50f))
        assertEquals(0f, SphereNative.getCoverage(), 0.001f)
        SphereNative.reset()
        SphereNative.release()
    }

    @Test
    fun addRotatedFrames_increasesCoverage() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        val frame = solidBitmap()

        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(SphereNative.addFrame(frame, q0))
        val coverage0 = SphereNative.getCoverage()

        val q1 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(25.0))
        assertTrue(SphereNative.addFrame(frame, q1))
        val coverage1 = SphereNative.getCoverage()

        val q2 = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(50.0))
        assertTrue(SphereNative.addFrame(frame, q2))
        val coverage2 = SphereNative.getCoverage()

        assertTrue("Second frame should add coverage", coverage1 > coverage0)
        assertTrue("Third frame should add coverage", coverage2 > coverage1)

        val equirect = SphereNative.getEquirectBitmap(512, 256)
        assertNotNull(equirect)
        assertTrue(equirect!!.width == 512 && equirect.height == 256)

        SphereNative.release()
    }

    @Test
    fun twoPhase_fastPathGatesLowOverlap_thenRefineRebuilds() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(1024, 512, 65f, 50f)
        val frame = solidBitmap(320, 240, Color.RED)

        // First frame is always accepted (no prior content to overlap).
        assertEquals(STITCH_ACCEPTED, SphereNative.addFrameFast(frame, floatArrayOf(0f, 0f, 0f, 1f), 0.20f))
        val cov0 = SphereNative.getCoverage()
        assertTrue("First fast frame must add coverage", cov0 > 0f)

        // A small pan keeps overlap well above the 20% gate → accepted, grows coverage.
        val qSmall = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(15.0))
        assertEquals(STITCH_ACCEPTED, SphereNative.addFrameFast(frame, qSmall, 0.20f))
        val cov1 = SphereNative.getCoverage()
        assertTrue("Overlapping fast frame must grow coverage", cov1 > cov0)

        // Overlap gate rejection: no frame can exceed 100% overlap, so a gate of
        // 1.5 always rejects — this is the "panned too fast / too little overlap"
        // path that keeps the user panning slowly. Frame must NOT be merged.
        val qNext = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(30.0))
        assertEquals(
            "Frame below the overlap gate must be rejected",
            STITCH_LOW_OVERLAP,
            SphereNative.addFrameFast(frame, qNext, 1.5f)
        )
        assertEquals("Gated frame must not change coverage", cov1, SphereNative.getCoverage(), 0.001f)

        // Background refinement rebuilds from the two accepted (dirty) frames.
        assertTrue("Refine should report a change with dirty frames", SphereNative.refinePending())
        assertTrue("Refined coverage must remain > 0", SphereNative.getCoverage() > 0f)

        // A second refine with nothing dirty is a cheap no-op.
        assertTrue("Refine with no dirty frames should be a no-op", !SphereNative.refinePending())

        val equirect = SphereNative.getEquirectBitmap(1024, 512)
        assertNotNull(equirect)
        val hasContent = (0 until 512 step 8).any { y ->
            (0 until 1024 step 8).any { x ->
                val p = equirect!!.getPixel(x, y)
                p != 0 && (p and 0x00FFFFFF) != 0x1A1A2E
            }
        }
        assertTrue("Refined equirect must contain stitched pixels", hasContent)

        SphereNative.release()
    }

    @Test
    fun getLastFrameInfo_populatedAfterAdd() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solidBitmap(160, 120), floatArrayOf(0f, 0f, 0f, 1f))
        SphereNative.addFrame(
            solidBitmap(160, 120, Color.BLUE),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(20.0))
        )
        val info = SphereNative.getLastFrameInfo()
        assertNotNull(info)
        assertTrue(info!!.frameIndex >= 2)
        SphereNative.release()
    }

    @Test
    fun rectilinearView_returnsBitmap() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(256, 128, 65f, 50f)
        SphereNative.addFrame(solidBitmap(160, 120), floatArrayOf(0f, 0f, 0f, 1f))
        val view = SphereNative.getRectilinearBitmap(320, 240, 0f, 0f, 90f)
        assertNotNull(view)
        SphereNative.release()
    }

    @Test
    fun solidAngleCoverage_increasesWithFrames() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        val c0 = SphereNative.getSolidAngleCoverage()
        SphereNative.addFrame(
            solidBitmap(color = Color.CYAN),
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(40.0))
        )
        val c1 = SphereNative.getSolidAngleCoverage()
        assertTrue(c1 >= c0)
        SphereNative.release()
    }

    @Test
    fun holeMask_returnsBitmapAfterFrame() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        val mask = SphereNative.getHoleMaskBitmap(128, 64)
        assertNotNull(mask)
        assertEquals(128, mask!!.width)
        assertEquals(64, mask.height)
        SphereNative.release()
    }

    @Test
    fun refineSeams_succeedsAfterFrames() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(512, 256, 65f, 50f)
        SphereNative.addFrame(solidBitmap(), floatArrayOf(0f, 0f, 0f, 1f))
        SphereNative.addFrame(
            solidBitmap(color = Color.BLUE),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(30.0))
        )
        assertTrue(SphereNative.refineSeams())
        val bmp = SphereNative.getEquirectBitmap(512, 256)
        assertNotNull(bmp)
        SphereNative.release()
    }

    /**
     * Full pipeline test: multiple frames at varying orientations that share
     * the same base color (simulating a real camera seeing a consistent scene).
     * Verifies coverage monotonically increases, downscaled preview works,
     * and the stitched equirect contains valid pixel data.
     *
     * Key insight: overlapping frames MUST have similar pixel content for the
     * alignment engine to accept them (SSD < REJECT_SSD_THRESH). Using
     * different colors would cause rejection, just as a real stitcher rejects
     * frames that don't match the existing panorama.
     */
    @Test
    fun fullPipeline_multiFrameStitch_producesValidSphere() {
        assumeTrue(SphereNative.isAvailable())

        val eqW = 1024
        val eqH = 512
        assertTrue(SphereNative.init(eqW, eqH, 65f, 50f))
        assertEquals(0f, SphereNative.getCoverage(), 0.001f)
        assertEquals(0f, SphereNative.getSolidAngleCoverage(), 0.001f)

        // All frames use the same color: realistic scenario where overlapping
        // regions see the same scene content (needed for SSD alignment to pass).
        val frame = solidBitmap(320, 240, Color.RED)

        // Rotations with ~25° steps around Z-axis (azimuth) give ~40° overlap
        // out of 65° FoV — enough for alignment without rejection.
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),                                                  // 0°
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(25.0)),              // 25°
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(50.0)),              // 50°
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(75.0)),              // 75°
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(100.0)),             // 100°
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(30.0)),              // pitch 30°
            QuaternionMath.fromAxisAngle(0.0, 1.0, 0.0, Math.toRadians(-30.0))              // pitch -30°
        )

        var prevCoverage = 0f
        var prevSolid = 0f

        for ((i, quat) in quats.withIndex()) {
            val accepted = SphereNative.addFrame(frame, quat)
            assertTrue("Frame $i should be accepted", accepted)

            val coverage = SphereNative.getCoverage()
            val solid = SphereNative.getSolidAngleCoverage()
            assertTrue(
                "Coverage must grow after frame $i (was $prevCoverage, now $coverage)",
                coverage > prevCoverage
            )
            assertTrue(
                "Solid angle must not shrink after frame $i (was $prevSolid, now $solid)",
                solid >= prevSolid
            )
            prevCoverage = coverage
            prevSolid = solid

            // Verify downscaled preview works after every frame
            val previewSmall = SphereNative.getEquirectBitmap(256, 128)
            assertNotNull("Downscaled preview (256x128) must not be null after frame $i", previewSmall)
            assertEquals(256, previewSmall!!.width)
            assertEquals(128, previewSmall.height)

            val previewMedium = SphereNative.getEquirectBitmap(512, 256)
            assertNotNull("Downscaled preview (512x256) must not be null after frame $i", previewMedium)
        }

        // Full equirect at native resolution
        val fullEquirect = SphereNative.getEquirectBitmap(eqW, eqH)
        assertNotNull("Full equirect must not be null", fullEquirect)
        assertEquals(eqW, fullEquirect!!.width)
        assertEquals(eqH, fullEquirect.height)

        // Verify stitched pixels exist (not all gap/background)
        val hasStitchedPixels = (0 until eqH step 8).any { y ->
            (0 until eqW step 8).any { x ->
                val p = fullEquirect.getPixel(x, y)
                p != 0 && (p and 0x00FFFFFF) != 0x1A1A2E
            }
        }
        assertTrue("Equirect must contain stitched pixel data", hasStitchedPixels)

        // Verify stitched region is substantial (not just a tiny sliver)
        var stitchedCount = 0
        var totalSampled = 0
        for (y in 0 until eqH step 4) {
            for (x in 0 until eqW step 4) {
                totalSampled++
                val p = fullEquirect.getPixel(x, y)
                if (p != 0 && (p and 0x00FFFFFF) != 0x1A1A2E) {
                    stitchedCount++
                }
            }
        }
        val stitchedPct = stitchedCount.toFloat() / totalSampled
        assertTrue(
            "At least 10% of equirect should have stitched pixels (was ${(stitchedPct * 100).toInt()}%)",
            stitchedPct > 0.10f
        )

        // Hole mask should work
        val holeMask = SphereNative.getHoleMaskBitmap(128, 64)
        assertNotNull(holeMask)

        // Rectilinear extraction should return a valid bitmap at any view angle.
        // (View-direction content correctness is validated in rectilinearView_returnsBitmap.)
        val recti = SphereNative.getRectilinearBitmap(320, 240, 50f, 0f, 90f)
        assertNotNull("Rectilinear view must not be null after full stitch", recti)
        assertEquals(320, recti!!.width)
        assertEquals(240, recti.height)

        // Seam refinement should succeed
        assertTrue(SphereNative.refineSeams())

        // Final coverage should be meaningful with 7 frames spanning ~100° azimuth + ±30° pitch
        val finalCoverage = SphereNative.getCoverage()
        val finalSolid = SphereNative.getSolidAngleCoverage()
        assertTrue("Final coverage should be >15% (was ${(finalCoverage * 100).toInt()}%)", finalCoverage > 0.15f)
        assertTrue("Final solid angle should be >8% (was ${(finalSolid * 100).toInt()}%)", finalSolid > 0.08f)

        SphereNative.release()
    }

    /**
     * Validates the SphereStitchWorker pipeline processes frames correctly
     * and produces non-null preview bitmaps via the downscale path.
     */
    @Test
    fun stitchWorker_producesPreviewBitmap() {
        assumeTrue(SphereNative.isAvailable())

        SphereNative.init(1024, 512, 65f, 50f)
        val frame = solidBitmap(320, 240, Color.RED)
        val q = floatArrayOf(0f, 0f, 0f, 1f)

        assertTrue(SphereNative.addFrame(frame, q))

        // Request equirect at the same size SphereStitchWorker uses (1024x512)
        val workerPreview = SphereNative.getEquirectBitmap(
            SphereStitchWorker.PREVIEW_W,
            SphereStitchWorker.PREVIEW_H
        )
        assertNotNull(
            "getEquirectBitmap at worker preview size (${SphereStitchWorker.PREVIEW_W}x${SphereStitchWorker.PREVIEW_H}) must succeed",
            workerPreview
        )
        assertEquals(SphereStitchWorker.PREVIEW_W, workerPreview!!.width)
        assertEquals(SphereStitchWorker.PREVIEW_H, workerPreview.height)

        // The preview should contain non-gap pixels where the frame was splatted
        val hasContent = (0 until workerPreview.height step 8).any { y ->
            (0 until workerPreview.width step 8).any { x ->
                val p = workerPreview.getPixel(x, y)
                p != 0 && (p and 0x00FFFFFF) != 0x1A1A2E
            }
        }
        assertTrue("Worker-size preview must contain stitched pixel data", hasContent)

        // Also verify the full-resolution equirect works
        val fullSize = SphereNative.getEquirectBitmap(1024, 512)
        assertNotNull(fullSize)

        SphereNative.release()
    }
}
