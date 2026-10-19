package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereRefinePolicyTest {

    @Test
    fun periodicRefine_everyFiveFramesWhenIdle() {
        assertTrue(SphereRefinePolicy.shouldRunPeriodicRefine(5))
        assertTrue(SphereRefinePolicy.shouldRunPeriodicRefine(6))
        assertFalse(SphereRefinePolicy.shouldRunPeriodicRefine(4))
    }

    @Test
    fun periodicRefine_resetsCounterToZero() {
        assertEquals(0, SphereRefinePolicy.acceptedCountAfterPeriodicRefine())
    }

    @Test
    fun rebuildOnlyWhenUnrefinedFramesExist() {
        assertFalse(SphereRefinePolicy.shouldRebuildOnRefine(anyUnrefinedFrame = false))
        assertTrue(SphereRefinePolicy.shouldRebuildOnRefine(anyUnrefinedFrame = true))
    }

    @Test
    fun ghostRegressionThreshold_isTwoPercent() {
        assertEquals(0.02, SphereRefinePolicy.maxGhostRegression(), 1e-9)
    }

    @Test
    fun fastRefineCorrelationGate_isHigh() {
        assertEquals(0.88, SphereRefinePolicy.minFastRefineCorrelation(), 1e-9)
    }

    @Test
    fun finishPreviewCorrelationGate_isNinetyFivePercent() {
        assertEquals(0.95, SphereRefinePolicy.minFinishPreviewCorrelation(), 1e-9)
    }

    @Test
    fun multiRefineBoundaryCorrelationGate() {
        assertEquals(0.90, SphereRefinePolicy.minMultiRefineBoundaryCorrelation(), 1e-9)
    }

    @Test
    fun globalRefineGhostDeltaGate_isLooserThanFastPath() {
        assertTrue(
            SphereRefinePolicy.maxGlobalRefineGhostDelta() >
                SphereRefinePolicy.maxGhostRegression()
        )
    }

    @Test
    fun refineGhostImprovementRatio_isSeventyPercent() {
        assertEquals(0.70, SphereRefinePolicy.minRefineGhostImprovementRatio(), 1e-9)
    }

    @Test
    fun refineSsdImprovementGate_isFivePercent() {
        assertEquals(0.05, SphereRefinePolicy.minRefineSsdImprovementRatio(), 1e-9)
    }

    @Test
    fun finishVsPreviewCorrelationGate() {
        assertEquals(0.48, SphereRefinePolicy.minFinishVsPreviewCorrelation(), 1e-9)
    }

    @Test
    fun finishExportGhostDeltaGate_isFivePercent() {
        assertEquals(0.05, SphereRefinePolicy.maxFinishExportGhostDelta(), 1e-9)
    }

    @Test
    fun finishOverlapBandCorrelationGate() {
        assertEquals(0.51, SphereRefinePolicy.minFinishOverlapBandCorrelation(), 1e-9)
    }

    @Test
    fun preferFastFinishExport_isDisabledForQualityPath() {
        assertFalse(SphereRefinePolicy.preferFastFinishExport())
    }

    @Test
    fun finishPosePassCounts() {
        assertEquals(0, SphereRefinePolicy.finishChainedPosePasses())
        assertEquals(1, SphereRefinePolicy.finishGlobalPosePasses())
        assertEquals(0, SphereRefinePolicy.finishConvergePasses())
        assertFalse(SphereRefinePolicy.runBackgroundGlobalPose())
    }

    @Test
    fun useRecencyCompositeOnGlobalFinish_isDisabledForL1Parity() {
        assertFalse(SphereRefinePolicy.useRecencyCompositeOnGlobalFinish())
    }

    @Test
    fun refreshViewerAfterBackgroundFinish_isEnabled() {
        assertTrue(SphereRefinePolicy.refreshViewerAfterBackgroundFinish())
    }

    @Test
    fun saveQualityGate_isEnforcedWithOverride() {
        assertTrue(SphereRefinePolicy.enforceSaveQualityGate())
        assertTrue(SphereRefinePolicy.allowSaveDespiteQualityFail())
    }

    @Test
    fun liveSplatDim_is1024() {
        assertEquals(1024, SphereRefinePolicy.liveSplatDim())
    }

    @Test
    fun stitchTelemetryBudgets() {
        assertEquals(50L, SphereRefinePolicy.maxAddFrameP95Ms())
        assertEquals(0.10f, SphereRefinePolicy.maxFrameRejectRate(), 1e-6f)
    }

    @Test
    fun workflowSharpnessRetentionGate() {
        assertEquals(0.80, SphereRefinePolicy.minWorkflowSharpnessRetention(), 1e-9)
    }

    @Test
    fun preFinishPolicy_frontLoadsEarly() {
        assertEquals(0.15f, SphereRefinePolicy.preFinishMinSolidAngle(), 1e-6f)
        assertTrue(SphereRefinePolicy.preFinishDelayMs() < SphereRefinePolicy.captureRefineDelayMs())
        assertEquals(0, SphereRefinePolicy.preFinishRefinementPasses().chainedPasses)
        assertEquals(1, SphereRefinePolicy.donePreviewRefinementPasses().globalPasses)
        assertFalse(SphereRefinePolicy.donePreviewRefinementPasses().fullAlignOnGlobal)
        assertFalse(SphereRefinePolicy.useLightFinishWhenCaptureRefined())
        assertEquals(0, SphereRefinePolicy.captureGlobalRefinePasses())
        assertEquals(0, SphereRefinePolicy.finishGlobalRefinePassesCap())
        assertTrue(SphereRefinePolicy.useCenterSquareStitch())
        assertTrue(SphereRefinePolicy.applyCenterSquareToPlanarReplay())
        assertTrue(SphereRefinePolicy.useFullSeamsOnExport())
    }
}
