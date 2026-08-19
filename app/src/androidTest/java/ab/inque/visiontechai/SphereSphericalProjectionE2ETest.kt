package ab.inque.visiontechai

import ab.inque.robotics.geometry.EquirectProjectionMath
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
 * S14: inverse spherical projection — overlapping frames must land on the same
 * equirect region after refine (not smeared by forward splat).
 */
@RunWith(AndroidJUnit4::class)
class SphereSphericalProjectionE2ETest {

    companion object {
        private const val TAG = "SphereSphericalE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val MAX_OVERLAP_EQ_PX = 12
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

    private fun dotBitmap(w: Int = FRAME_W, h: Int = FRAME_H, dotX: Int = w / 2, dotY: Int = h / 2): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (abs(x - dotX) < 8 && abs(y - dotY) < 8) {
                    Color.RED
                } else {
                    Color.DKGRAY
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Longitude wrap-aware horizontal distance on equirect. */
    private fun equirectDx(ax: Int, bx: Int, eqW: Int): Int {
        val d = abs(ax - bx)
        return minOf(d, eqW - d)
    }

    private fun findBrightCentroidNear(
        bmp: Bitmap,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Pair<Int, Int>? {
        val region = SphereImageMetrics.Region(
            (centerX - radius).coerceAtLeast(0),
            (centerY - radius).coerceAtLeast(0),
            (radius * 2).coerceAtMost(bmp.width),
            (radius * 2).coerceAtMost(bmp.height)
        )
        return findBrightCentroid(bmp, region)
    }

    private fun findBrightCentroid(bmp: Bitmap, region: SphereImageMetrics.Region): Pair<Int, Int>? {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        val xEnd = (region.x0 + region.w).coerceAtMost(bmp.width)
        val yEnd = (region.y0 + region.h).coerceAtMost(bmp.height)
        for (y in region.y0 until yEnd) {
            for (x in region.x0 until xEnd) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                if (r > 200) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        if (count < 5) return null
        return (sumX / count).toInt() to (sumY / count).toInt()
    }

    @Test
    fun twoFrameOverlap_refine_alignsRedDot() {
        // Off-center dot so yaw rotation moves it on the equirect (center is degenerate for Z-rotation).
        val dotX = FRAME_W * 3 / 4
        val dotY = FRAME_H / 2
        val frame = dotBitmap(dotX = dotX, dotY = dotY)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val qOverlap = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(18.0))

        SphereNative.addFrameFast(frame, q0, 0.08f)
        SphereNative.addFrameFast(frame, qOverlap, 0.08f)

        assertTrue("Refine must run", SphereNative.refinePending())
        val equirect = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(equirect)
        val expected = EquirectProjectionMath.projectFramePixel(
            dotX, dotY, FRAME_W, FRAME_H, 65f, 50f, qOverlap, EQ_W, EQ_H
        )
        val searchRadius = 48
        val centroid = findBrightCentroidNear(equirect!!, expected.x, expected.y, searchRadius)
        assertNotNull("Red dot should be visible near spherical projection after refine", centroid)

        val dx = equirectDx(centroid!!.first, expected.x, EQ_W)
        val dy = abs(centroid.second - expected.y)
        Log.i(TAG, "centroid=$centroid expected=(${expected.x},${expected.y}) d=($dx,$dy)")
        assertTrue(
            "Refined dot should land near spherical projection (dx=$dx dy=$dy)",
            dx <= MAX_OVERLAP_EQ_PX && dy <= MAX_OVERLAP_EQ_PX
        )
    }

    @Test
    fun refine_improvesOverlap_vsFastOnly() {
        val dotX = FRAME_W * 3 / 4
        val dotY = FRAME_H / 2
        val frame = dotBitmap(dotX = dotX, dotY = dotY)
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        val qTrue = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(18.0))
        val qNoisy = QuaternionMath.multiply(
            qTrue,
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(3.0))
        )

        SphereNative.addFrameFast(frame, q0, 0.08f)
        SphereNative.addFrameFast(frame, qNoisy, 0.08f)

        val fast = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(fast)
        val expected = EquirectProjectionMath.projectFramePixel(
            dotX, dotY, FRAME_W, FRAME_H, 65f, 50f, qTrue, EQ_W, EQ_H
        )
        val searchRadius = 48
        val fastCentroid = findBrightCentroidNear(fast!!, expected.x, expected.y, searchRadius)
        val fastErr = if (fastCentroid != null) {
            equirectDx(fastCentroid.first, expected.x, EQ_W) + abs(fastCentroid.second - expected.y)
        } else {
            Int.MAX_VALUE
        }

        assertTrue("Refine must run", SphereNative.refinePending())
        val refined = SphereNative.getEquirectBitmap(EQ_W, EQ_H)
        assertNotNull(refined)
        val refCentroid = findBrightCentroidNear(refined!!, expected.x, expected.y, searchRadius)
        assertNotNull(refCentroid)
        val refErr = equirectDx(refCentroid!!.first, expected.x, EQ_W) +
            abs(refCentroid.second - expected.y)
        Log.i(TAG, "fastErr=$fastErr refErr=$refErr expected=(${expected.x},${expected.y})")

        assertTrue(
            "Spherical refine should align closer than fast-only (fast=$fastErr ref=$refErr)",
            refErr < fastErr || refErr <= MAX_OVERLAP_EQ_PX * 2
        )
    }
}
