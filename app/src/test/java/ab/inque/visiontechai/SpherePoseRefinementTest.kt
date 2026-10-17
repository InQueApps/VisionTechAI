package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.FrameAlignmentInfo
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpherePoseRefinementTest {

    private open class CountingOps : SphereStitchOperations {
        var chained = 0
        var global = 0
        var rebuild = 0
        override fun refineStoredPoses(): Boolean {
            chained++
            return true
        }
        override fun refineGlobalPose(): Boolean {
            global++
            return true
        }
        override fun rebuildFullAlign(): Boolean {
            rebuild++
            return true
        }
        override fun init(
            eqW: Int,
            eqH: Int,
            fovH: Float,
            fovV: Float,
            distortion: LensDistortion
        ): Boolean = true
        override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean = true
        override fun getCoverage(): Float = 1f
        override fun getSolidAngleCoverage(): Float = 1f
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo(): FrameAlignmentInfo? = null
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap? = null
        override fun reset() {
            chained = 0
            global = 0
            rebuild = 0
        }
        override fun release() {}
    }

    @Test
    fun runOneSlice_advancesThroughLegacyGlobalSchedule() {
        val ops = CountingOps()
        val config = SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 1,
            fullAlignOnGlobal = true
        )
        var progress = SpherePoseRefinement.RefineProgress()
        repeat(config.chainedPasses + config.globalPasses + config.convergePasses) {
            val result = SpherePoseRefinement.runOneSlice(ops, config, progress)
            progress = result.progress
        }
        assertTrue(progress.isCompleteFor(config))
        assertEquals(1, ops.chained)
        assertEquals(2, ops.global)
        assertEquals(2, ops.rebuild)
    }

    @Test
    fun convergeSlice_skipsWhenNoRefineWorkPending() {
        val ops = object : CountingOps() {
            override fun hasRefineWorkPending(applyGlobalPose: Boolean): Boolean = false
        }
        val config = SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 1,
            fullAlignOnGlobal = true
        )
        var progress = SpherePoseRefinement.RefineProgress(
            globalPassesDone = config.globalPasses
        )
        val result = SpherePoseRefinement.runOneSlice(ops, config, progress)
        assertTrue(result.progress.isCompleteFor(config))
        assertEquals(0, ops.chained)
        assertEquals(0, ops.global)
        assertEquals(0, ops.rebuild)
    }

    @Test
    fun run_resumesFromStartProgress() {
        val ops = CountingOps()
        val config = SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 1,
            fullAlignOnGlobal = true
        )
        val done = SpherePoseRefinement.RefineProgress(globalPassesDone = 1)
        SpherePoseRefinement.run(ops, config, startProgress = done)
        assertEquals(1, ops.chained)
        assertEquals(1, ops.global)
        assertEquals(1, ops.rebuild)
    }

    @Test
    fun captureConfig_runsPoseGraphOnlyWithoutFullAlign() {
        val ops = CountingOps()
        val config = SphereRefinePolicy.captureRefinementPasses()
        var progress = SpherePoseRefinement.RefineProgress()
        progress = SpherePoseRefinement.runOneSlice(ops, config, progress).progress
        assertEquals(0, ops.chained)
        assertEquals(1, ops.global)
        assertEquals(0, ops.rebuild)
    }
}
