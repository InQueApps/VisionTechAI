package ab.inque.visiontechai



import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue

import org.junit.Test



class SphereProductionStitchPipelineTest {



    private class RecordingOps : SphereStitchOperations {

        val steps = mutableListOf<String>()

        var fastFinish = true

        var globalPasses = -1

        var poseGraphOnly = false



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

        override fun refineTargetedPhotometric(): Int {
            steps.add("targetedPhotometric")
            return 1
        }

        override fun refineFeatureGraphReconcile(): Boolean {
            steps.add("graphReconcile")
            return true
        }

        override fun refineConditionalParallax(): Int {
            steps.add("conditionalParallax")
            return 1
        }



        override fun rebuildExportNearestCenter(): Boolean {

            steps.add("nearestCenter")

            return true

        }



        override fun resetPosesToImu(): Boolean {

            steps.add("resetPosesToImu")

            return true

        }



        override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean {

            steps.add("prepareExport")

            return true

        }

        override fun preparePreviewResplat(outW: Int, outH: Int): Boolean {

            steps.add("previewResplat")

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



        override fun setPoseGraphOnlyGlobal(enabled: Boolean) {

            poseGraphOnly = enabled

            steps.add(if (enabled) "poseGraphOnlyOn" else "poseGraphOnlyOff")

        }



        override fun buildFeatureGraphCache(): Boolean {

            steps.add("graphCache")

            return true

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

    fun runDonePreviewRefine_usesPoseGraphOnlyGlobal() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runDonePreviewRefine(ops)

        assertFalse(ops.fastFinish)

        assertTrue(ops.poseGraphOnly)

        assertEquals("poseGraphOnlyOn", ops.steps.first())

        assert(ops.steps.contains("global"))

        assert(ops.steps.contains("previewResplat"))

        assertFalse(ops.steps.contains("rebuildFullAlign"))

        assertFalse(ops.steps.contains("prepareExport"))

    }



    @Test

    fun runSavePolish_runsTargetedFinalizationAndExport() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runSavePolish(ops, exportW = 4096, exportH = 2048)

        assert(ops.steps.contains("targetedPhotometric"))

        assert(ops.steps.contains("graphReconcile"))

        assert(ops.steps.contains("conditionalParallax"))

        assert(ops.steps.contains("prepareExport"))

        assert(ops.steps.contains("seamFull"))

        assertFalse(ops.steps.contains("global"))

        assertFalse(ops.steps.contains("rebuildFullAlign"))

        assertFalse(ops.poseGraphOnly)

        assert(ops.steps.contains("poseGraphOnlyOff"))

    }

    @Test
    fun configureForTargetedFinalization_usesFullQualityNativeMode() {
        val ops = RecordingOps()
        SphereProductionStitchPipeline.configureForTargetedFinalization(ops)
        assertFalse(ops.poseGraphOnly)
        assertFalse(ops.fastFinish)
        assertEquals(0, ops.globalPasses)
        assertEquals("poseGraphOnlyOff", ops.steps.first())
        assert(ops.steps.contains("fastFinishOff"))
        assert(ops.steps.contains("globalPasses=0"))
    }



    @Test

    fun runFinishExport_previewOnlyAtCaptureResolution() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runFinishExport(ops, exportW = 1024, exportH = 512)

        assert(ops.steps.contains("global"))

        assertFalse(ops.steps.contains("prepareExport"))

    }



    @Test

    fun runFastStitchFinishExport_resetsPosesGlobalNearestExport() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runFastStitchFinishExport(ops, 1024, 512)

        assert(ops.fastFinish)

        assertEquals(2, ops.globalPasses)

        assert(ops.steps.contains("resetPosesToImu"))

        assert(ops.steps.contains("fastFinishOn"))

        assert(ops.steps.contains("global"))

        assert(!ops.steps.contains("rebuildFullAlign"))

        assert(ops.steps.contains("prepareExport"))

        assert(!ops.steps.contains("seamLight"))

        assert(!ops.steps.contains("fastFinishOff"))

    }



    @Test

    fun runSweepFastFinishExport_truthUsesNearestCenter() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runSweepFastFinishExport(ops, 1024, 512, imuDegradeDeg = 0f)

        assert(ops.fastFinish)

        assertEquals(2, ops.globalPasses)

        assert(ops.steps.contains("resetPosesToImu"))

        assert(ops.steps.contains("prepareExport"))

        assert(!ops.steps.contains("global"))

    }



    @Test

    fun runSweepFastFinishExport_degradedImuResetsPosesThenGlobalGraph() {

        val ops = RecordingOps()

        SphereProductionStitchPipeline.runSweepFastFinishExport(ops, 1024, 512, imuDegradeDeg = 2f)

        assert(ops.fastFinish)

        assertEquals(2, ops.globalPasses)

        assert(ops.steps.contains("resetPosesToImu"))

        assert(ops.steps.contains("fastFinishOn"))

        assert(ops.steps.contains("global"))

        assert(!ops.steps.contains("rebuildFullAlign"))

        assert(ops.steps.contains("prepareExport"))

        assert(!ops.steps.contains("seamLight"))

        assert(!ops.steps.contains("fastFinishOff"))

    }

}


