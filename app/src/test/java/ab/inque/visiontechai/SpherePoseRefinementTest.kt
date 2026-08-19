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
    fun runOneSlice_advancesThroughFullFinishSchedule() {
        val ops = CountingOps()
        val config = SphereRefinePolicy.finishRefinementPassesFull()
        var progress = SpherePoseRefinement.RefineProgress()
        repeat(config.chainedPasses + config.globalPasses + config.convergePasses) {
            val result = SpherePoseRefinement.runOneSlice(ops, config, progress)
            progress = result.progress
        }
        assertTrue(progress.isCompleteFor(config))
        assertEquals(3, ops.chained)
        assertEquals(3, ops.global)
        assertEquals(3, ops.rebuild)
    }

    @Test
    fun convergeSlice_skipsWhenNoRefineWorkPending() {
        val ops = object : CountingOps() {
            override fun hasRefineWorkPending(applyGlobalPose: Boolean): Boolean = false
        }
        val config = SphereRefinePolicy.finishRefinementPassesFull()
        var progress = SpherePoseRefinement.RefineProgress(
            chainedPassesDone = config.chainedPasses,
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
        val config = SphereRefinePolicy.finishRefinementPassesFull()
        val halfway = SpherePoseRefinement.RefineProgress(
            chainedPassesDone = 2,
            globalPassesDone = 1
        )
        SpherePoseRefinement.run(ops, config, startProgress = halfway)
        assertEquals(1, ops.chained)
        assertEquals(2, ops.global)
        assertEquals(2, ops.rebuild)
    }

    @Test
    fun captureConfig_runsFullAlignOnGlobalPass() {
        val ops = CountingOps()
        val config = SphereRefinePolicy.captureRefinementPasses()
        var progress = SpherePoseRefinement.RefineProgress()
        repeat(2) {
            progress = SpherePoseRefinement.runOneSlice(ops, config, progress).progress
        }
        assertEquals(1, ops.chained)
        assertEquals(1, ops.global)
        assertEquals(1, ops.rebuild)
    }
}
