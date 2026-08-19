package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SphereProductionStitchPipelineTest {

    private class RecordingOps : SphereStitchOperations {
        val steps = mutableListOf<String>()
        var fastFinish = true
        var globalPasses = -1

        override fun init(
            eqW: Int,
            eqH: Int,
            fovH: Float,
            fovV: Float,
            distortion: ab.inque.robotics.math.LensDistortion
        ) = true

        override fun addFrame(bitmap: android.graphics.Bitmap, quaternion: FloatArray) = true
        override fun refineStoredPoses(): Boolean {
            steps.add("refineStored")
            return true
        }

        override fun refineGlobalPose(): Boolean {
            steps.add("global")
            return true
        }

        override fun rebuildFullAlign(): Boolean {
            steps.add("rebuildFullAlign")
            return true
        }

        override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean {
            steps.add("prepareExport")
            return true
        }

        override fun refineSeams(light: Boolean): Boolean {
            steps.add(if (light) "seamLight" else "seamFull")
            return true
        }

        override fun setGlobalRefinePasses(passes: Int) {
            globalPasses = passes
            steps.add("globalPasses=$passes")
        }

        override fun setFastFinishRefine(enabled: Boolean) {
            fastFinish = enabled
            steps.add(if (enabled) "fastFinishOn" else "fastFinishOff")
        }

        override fun getCoverage(): Float = 1f
        override fun getSolidAngleCoverage(): Float = 1f
        override fun getHoleMaskBitmap(outW: Int, outH: Int) = null
        override fun getLastFrameInfo() = null
        override fun getEquirectBitmap(eqW: Int, eqH: Int) = null
        override fun reset() {}
        override fun release() {}
    }

    @Test
    fun runFinishExport_usesProductionNativeSettings() {
        val ops = RecordingOps()
        SphereProductionStitchPipeline.runFinishExport(ops, exportW = 1024, exportH = 512)
        assertFalse(ops.fastFinish)
        assertEquals(0, ops.globalPasses)
        assertEquals("fastFinishOff", ops.steps.first())
        assertEquals("globalPasses=0", ops.steps[1])
        assert(ops.steps.contains("prepareExport"))
        assert(ops.steps.contains("seamFull"))
        assert(ops.steps.count { it == "rebuildFullAlign" } >= 2)
    }
}
