package ab.inque.visiontechai

/**
 * Single source of truth for native refine/export settings used by live capture
 * ([SphereStitchWorker]) and device E2E replay ([SpherePlanarReplayE2ETest]).
 */
object SphereProductionStitchPipeline {

    fun configureForCaptureRefine(operations: SphereStitchOperations) {
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(SphereRefinePolicy.captureGlobalRefinePasses())
    }

    fun configureForPreFinish(operations: SphereStitchOperations) {
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(0)
    }

    fun configureForFinish(operations: SphereStitchOperations) {
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(SphereRefinePolicy.finishGlobalRefinePassesCap())
    }

    fun runCaptureRefinement(
        operations: SphereStitchOperations,
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ): Boolean {
        configureForCaptureRefine(operations)
        return SphereCaptureRefinement.run(
            operations = operations,
            isCancelled = isCancelled,
            onPass = onPass
        )
    }

    /**
     * DONE export path: drain remaining pre-finish slices, resplat at export resolution,
     * light seams — matches [SphereStitchWorker.runFinishGeneration] foreground work.
     */
    fun runFinishExport(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        startProgress: SpherePoseRefinement.RefineProgress = SpherePoseRefinement.RefineProgress(),
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        configureForFinish(operations)
        val preFinishConfig = SphereRefinePolicy.preFinishRefinementPasses()
        if (!startProgress.isCompleteFor(preFinishConfig)) {
            SphereFinishRefinement.run(
                operations = operations,
                startProgress = startProgress,
                isCancelled = isCancelled,
                onPass = onPass
            )
        }
        operations.prepareExportEquirect(exportW, exportH)
        operations.refineSeams(light = !SphereRefinePolicy.useFullSeamsOnExport())
    }

    /**
     * IMU sweep-fast: reset to IMU seeds, global graph, nearest-view export (no per-frame drift).
     */
    fun runSweepFastFinishExport(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        imuDegradeDeg: Float,
    ) {
        operations.setFastFinishRefine(true)
        operations.setGlobalRefinePasses(0)
        operations.resetPosesToImu()
        if (imuDegradeDeg > 0f) {
            configureForCaptureRefine(operations)
            operations.refineGlobalPose()
        }
        operations.setFastFinishRefine(true)
        operations.prepareExportEquirect(exportW, exportH)
    }
}
