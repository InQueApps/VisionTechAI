package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * I0/I1 regression suite (SPHERE_INTRINSIC_OVERLAP_PLAN.md §8).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SphereIntrinsicOverlapE2ETest {

    companion object {
        private const val TAG = "SphereIntrinsicOverlapE2E"
        private const val EQ_W = 2048
        private const val EQ_H = 1024
        private const val FRAME_W = 640
        private const val FRAME_H = 480
        private const val OVERLAP_GATE = 0.08f
        private const val TRUE_FOV_H = 65f
        private const val TRUE_FOV_V = 50f
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.release()
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

    private fun shiftBitmap(src: Bitmap, dx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) dstPx[y * w + x] = srcPx[y * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun overlapQuat(yawDeg: Double): FloatArray =
        QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(yawDeg))

    private fun addThreeOverlappingFrames(
        fovH: Float = TRUE_FOV_H,
        fovV: Float = TRUE_FOV_V,
        distortion: LensDistortion = LensDistortion.NONE
    ) {
        SphereNative.init(EQ_W, EQ_H, fovH, fovV, distortion)
        val base = verticalStripeBitmap()
        val q0 = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(base, q0, OVERLAP_GATE)
        SphereNative.addFrameFast(shiftBitmap(base, dx = 8), overlapQuat(16.0), OVERLAP_GATE)
        SphereNative.addFrameFast(shiftBitmap(base, dx = 16), overlapQuat(32.0), OVERLAP_GATE)
    }

    /** I0: overlap bank accumulates after the second accepted frame. */
    @Test
    fun overlapBank_appendsOnSecondFrame() {
        SphereNative.init(EQ_W, EQ_H, TRUE_FOV_H, TRUE_FOV_V)
        val base = verticalStripeBitmap()
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        SphereNative.addFrameFast(base, q, OVERLAP_GATE)
        assertTrue("bank empty after first frame", SphereNative.getOverlapBankSize() == 0)

        SphereNative.addFrameFast(shiftBitmap(base, dx = 8), overlapQuat(16.0), OVERLAP_GATE)
        val bankAfterTwo = SphereNative.getOverlapBankSize()
        Log.i(TAG, "overlap bank after 2 frames=$bankAfterTwo")
        assertTrue("bank should grow after overlapping second frame", bankAfterTwo > 0)
    }

    /** Frozen intrinsics: focal scale stays 1.0; global pose refine still improves SSD. */
    @Test
    fun globalRefine_improvesRaySSD_withWrongFovSeed() {
        val wrongFovH = TRUE_FOV_H * 1.02f
        addThreeOverlappingFrames(fovH = wrongFovH, fovV = TRUE_FOV_V)

        val bankSize = SphereNative.getOverlapBankSize()
        assertTrue("need overlap bank", bankSize > 20)

        val ssdBefore = SphereNative.evalOverlapBankSSD()
        assertTrue("global pose refine should run", SphereNative.refinePending(applyGlobalPose = true))
        val ssdAfterGlobal = SphereNative.evalOverlapBankSSD()
        assertFalse("intrinsic optimization disabled", SphereNative.refineIntrinsicsPending())
        assertEquals(1f, SphereNative.getSessionFocalScale(), 0.001f)
        Log.i(TAG, "ssd $ssdBefore -> global=$ssdAfterGlobal scale=1.0")

        assertTrue("global refine should improve overlap SSD", ssdAfterGlobal < ssdBefore * 0.85f)
    }

    /** Correct FOV: focal scale remains 1.0 after global refine. */
    @Test
    fun globalRefine_doesNotChangeFocalScale() {
        addThreeOverlappingFrames()

        val ssdBefore = SphereNative.evalOverlapBankSSD()
        SphereNative.refinePending(applyGlobalPose = true)
        val ssdAfterGlobal = SphereNative.evalOverlapBankSSD()
        assertFalse(SphereNative.refineIntrinsicsPending())
        val scale = SphereNative.getSessionFocalScale()
        val ssdAfter = SphereNative.evalOverlapBankSSD()
        Log.i(TAG, "scale=$scale ssd $ssdBefore -> global=$ssdAfterGlobal -> $ssdAfter")

        assertEquals(1f, scale, 0.001f)
        assertTrue("SSD should not regress", ssdAfter <= ssdAfterGlobal * 1.05f)
    }

    /** Frozen k1: session distortion stays at init value. */
    @Test
    fun globalRefine_doesNotOptimizeK1() {
        val k1Before = 0.04f
        addThreeOverlappingFrames(distortion = LensDistortion(k1 = k1Before))

        val ssdBefore = SphereNative.evalOverlapBankSSD()
        assertTrue("global pose refine should run", SphereNative.refinePending(applyGlobalPose = true))
        val ssdAfterGlobal = SphereNative.evalOverlapBankSSD()
        assertFalse(SphereNative.refineIntrinsicsPending())
        val k1 = SphereNative.getSessionDistortionK1()
        val ssdAfter = SphereNative.evalOverlapBankSSD()
        Log.i(TAG, "k1=$k1 ssd $ssdBefore -> global=$ssdAfterGlobal -> $ssdAfter")

        assertTrue("global refine should improve overlap SSD", ssdAfterGlobal < ssdBefore * 0.85f)
        assertEquals(k1Before, k1, 0.002f)
        assertTrue("SSD stable after noop intrinsic", ssdAfter <= ssdAfterGlobal * 1.02f)
    }

    /** Persisted k1 from store is used on the next native init. */
    @Test
    fun nextSession_usesPersistedK1() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LensDistortionStore(ctx.applicationContext)
        store.reset()

        val k1Seed = 0.04f
        addThreeOverlappingFrames(distortion = LensDistortion(k1 = k1Seed))
        SphereNative.refinePending(applyGlobalPose = true)
        assertFalse(SphereNative.refineIntrinsicsPending())
        store.set(LensDistortion(k1 = k1Seed))
        SphereNative.release()

        val loaded = store.get()
        SphereNative.init(EQ_W, EQ_H, TRUE_FOV_H, TRUE_FOV_V, loaded)
        assertTrue(
            "next session should load persisted k1",
            kotlin.math.abs(SphereNative.getSessionDistortionK1() - loaded.k1) < 0.002f
        )
    }
}
