package ab.inque.visiontechai

/**
 * Single source of truth for native refine/export settings used by live capture
 * ([SphereStitchWorker]) and device E2E replay ([SpherePlanarReplayE2ETest]).
 */
object SphereProductionStitchPipeline {

    /** Commit deferred live-capture frames before refine or export. */
    fun ensureCaptureWorkCommitted(operations: SphereStitchOperations) {
        operations.flushPendingFrameStores()
    }

    fun configureForCaptureRefine(operations: SphereStitchOperations) {
        operations.setPoseGraphOnlyGlobal(true)
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(SphereRefinePolicy.captureGlobalRefinePasses())
    }

    fun configureForPreFinish(operations: SphereStitchOperations) {
        operations.setPoseGraphOnlyGlobal(false)
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(0)
    }

    fun configureForDonePreview(operations: SphereStitchOperations) {
        operations.setPoseGraphOnlyGlobal(true)
        operations.setFastFinishRefine(false)
        operations.setGlobalRefinePasses(0)
    }

    /**
     * Post-DONE stage 2 / SAVE polish: explicit full-quality native mode before targeted JNI passes.
     * Do not inherit [configureForDonePreview] (poseGraphOnly=true) across the stage boundary.
     */
    fun configureForTargetedFinalization(operations: SphereStitchOperations) {
        operations.setPoseGraphOnlyGlobal(SphereRefinePolicy.targetedFinalizationPoseGraphOnly())
        operations.setFastFinishRefine(SphereRefinePolicy.targetedFinalizationFastFinish())
        operations.setGlobalRefinePasses(SphereRefinePolicy.targetedFinalizationGlobalRefinePasses())
    }

    /** Legacy alias; SAVE polish delegates to [configureForTargetedFinalization]. */
    fun configureForSavePolish(operations: SphereStitchOperations) =
        configureForTargetedFinalization(operations)

    fun configureForFinish(operations: SphereStitchOperations) =
        configureForTargetedFinalization(operations)

    /** Fast stitch / sweep-fast: rotation-only global graph, nearest-view export follows. */
    fun configureForFastFinishRefine(operations: SphereStitchOperations) {
        operations.setPoseGraphOnlyGlobal(false)
        operations.setFastFinishRefine(true)
        operations.setGlobalRefinePasses(SphereRefinePolicy.fastFinishGlobalRefinePasses())
    }

    fun runCaptureRefinement(
        operations: SphereStitchOperations,
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ): Boolean {
        ensureCaptureWorkCommitted(operations)
        configureForCaptureRefine(operations)
        return SphereCaptureRefinement.run(
            operations = operations,
            isCancelled = isCancelled,
            onPass = onPass
        )
    }

    /**
     * DONE viewer stage 1: cached feature SO(3) graph + capture-resolution preview resplat.
     */
    fun runDonePreviewRefine(
        operations: SphereStitchOperations,
        startProgress: SpherePoseRefinement.RefineProgress = SpherePoseRefinement.RefineProgress(),
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        ensureCaptureWorkCommitted(operations)
        configureForDonePreview(operations)
        val config = SphereRefinePolicy.donePreviewRefinementPasses()
        if (!startProgress.isCompleteFor(config)) {
            SpherePoseRefinement.runAdaptive(
                operations = operations,
                config = config,
                startProgress = startProgress,
                isCancelled = isCancelled,
                onPass = onPass
            )
        }
        if (isCancelled()) return
        operations.preparePreviewResplat(
            SphereRefinePolicy.donePreviewWidth(),
            SphereRefinePolicy.donePreviewHeight()
        )
        onPass("previewResplat")
    }

    /**
     * Post-DONE stage 2: targeted polish → graph reconcile → conditional warp →
     * one 4096 resplat + seams. Replaces brute-force multi-pass global schedule.
     */
    fun runPostDoneFinalization(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        runTargetedFinalization(operations, exportW, exportH, isCancelled, onPass)
    }

    /**
     * graph → targeted photometric → graph reconcile → conditional warp → render → seams.
     */
    fun runTargetedFinalization(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        ensureCaptureWorkCommitted(operations)
        configureForTargetedFinalization(operations)
        val graphPrimed = operations.refineFeatureGraphReconcile()
        onPass(if (graphPrimed) "featureGraph" else "featureGraph skip")
        if (isCancelled()) return

        val polished = operations.refineTargetedPhotometric()
        onPass("targetedPhotometric:$polished")
        if (isCancelled()) return

        val reconciled = operations.refineFeatureGraphReconcile()
        onPass(if (reconciled) "graphReconcile" else "graphReconcile skip")
        if (isCancelled()) return

        val warped = operations.refineConditionalParallax()
        onPass("conditionalParallax:$warped")
        if (isCancelled()) return

        if (!operations.prepareExportEquirect(exportW, exportH)) {
            onPass("prepareExport failed")
            return
        }
        onPass("prepareExport")
        if (SphereRefinePolicy.runExportSeamRefine()) {
            val lightSeams = !SphereRefinePolicy.useFullSeamsOnExport()
            operations.refineSeams(light = lightSeams)
            onPass(if (lightSeams) "seams light" else "seams full")
        }
    }

    /**
     * Legacy SAVE path: brute-force global + align slices. Prefer [runTargetedFinalization].
     */
    fun runSavePolish(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        runTargetedFinalization(operations, exportW, exportH, isCancelled, onPass)
    }

    /**
     * DONE export path (legacy): same as [runSavePolish] when preview was not split.
     */
    fun runFinishExport(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
        startProgress: SpherePoseRefinement.RefineProgress = SpherePoseRefinement.RefineProgress(),
        isCancelled: () -> Boolean = { false },
        onPass: (String) -> Unit = {}
    ) {
        runDonePreviewRefine(
            operations = operations,
            startProgress = startProgress,
            isCancelled = isCancelled,
            onPass = onPass
        )
        if (isCancelled()) return
        if (exportW != SphereCaptureController.CAPTURE_EQ_W ||
            exportH != SphereCaptureController.CAPTURE_EQ_H
        ) {
            runTargetedFinalization(operations, exportW, exportH, isCancelled, onPass)
        }
    }

    /**
     * Live fast stitch: reset to IMU seeds, global graph, nearest-view export (no seams).
     */
    fun runFastStitchFinishExport(
        operations: SphereStitchOperations,
        exportW: Int,
        exportH: Int,
    ) {
        ensureCaptureWorkCommitted(operations)
        operations.resetPosesToImu()
        configureForFastFinishRefine(operations)
        operations.refineGlobalPose()
        operations.prepareExportEquirect(exportW, exportH)
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
        ensureCaptureWorkCommitted(operations)
        operations.resetPosesToImu()
        if (imuDegradeDeg > 0f) {
            configureForFastFinishRefine(operations)
            operations.refineGlobalPose()
        } else {
            configureForFastFinishRefine(operations)
        }
        operations.prepareExportEquirect(exportW, exportH)
    }
}
