package ab.inque.visiontechai

/**
 * When background refine should run (idle debounce, periodic mid-capture, finish).
 */
object SphereRefinePolicy {

    /**
     * When true, periodic capture refine runs every [periodicRefineEvery] accepted
     * frames while the stitch queue is idle (pose-only + preview resplat).
     */
    fun shouldRunPeriodicRefine(
        acceptedSinceRefine: Int,
        every: Int = periodicRefineEvery()
    ): Boolean = acceptedSinceRefine >= every

    fun acceptedCountAfterPeriodicRefine(): Int = 0

    fun periodicRefineEvery(): Int = 5

    /** Minimum stored frames before capture pose refine runs. */
    fun captureRefineMinFrames(): Int = 2

    fun captureRefineDelayMs(): Long = 300L

    /** When angular velocity is below [captureMotionRefineMaxDegPerSec], refine sooner. */
    fun captureMotionRefineStableMs(): Long = 200L

    /** Max angular speed (deg/s) to treat capture as paused for motion-aware refine. */
    fun captureMotionRefineMaxDegPerSec(): Double = 10.0

    /** Active capture preview equirect width (matches [SphereCaptureController.CAPTURE_EQ_W]). */
    fun capturePreviewWidth(): Int = 1024

    /** Active capture preview equirect height. */
    fun capturePreviewHeight(): Int = 512

    /** DONE refined viewer preview (async after tap). */
    fun donePreviewWidth(): Int = 2048

    fun donePreviewHeight(): Int = 1024

    /** Shorter debounce when [shouldRunPeriodicRefine] during live capture. */
    fun capturePeriodicDelayMs(): Long = 120L

    /** Mid-capture periodic refine: pose graph only — no full align / warp. */
    fun capturePeriodicRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    /**
     * Idle debounce: one cached feature-graph orientation solve, no full align / warp.
     */
    fun captureRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    /**
     * Idle pre-DONE: cache BRIEF features + relative rotation edges only (no panorama rebuild).
     */
    fun preFinishRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 0,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    /** DONE viewer: one fast orientation solve + native preview rebuild (no full align). */
    fun donePreviewRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    /** Post-DONE stage 2: targeted polish slices for progress UI (not [PassConfig] slices). */
    fun targetedFinalizationPassCount(): Int = 4

    /** Stage 2 uses full-quality native mode — not pose-graph-only preview. */
    fun targetedFinalizationPoseGraphOnly(): Boolean = false

    fun targetedFinalizationFastFinish(): Boolean = false

    /** Targeted path does not use [globalRefinePosesLocked] multi-pass schedule. */
    fun targetedFinalizationGlobalRefinePasses(): Int = 0

    /** Legacy [PassConfig] for adaptive slice runner; export-only (targeted path is separate). */
    fun savePolishRefinementPasses(): SpherePoseRefinement.PassConfig =
        finishRefinementPassesExportOnly()

    /** DONE fallback when pre-finish did not complete before tap (same as preview). */
    fun finishRefinementPassesMinimal(): SpherePoseRefinement.PassConfig =
        donePreviewRefinementPasses()

    fun finishRefinementPassesExportOnly(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 0,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    fun finishRefinementPassesFull(): SpherePoseRefinement.PassConfig =
        savePolishRefinementPasses()

    /** DONE when capture already ran global + warps: polish only. */
    fun finishRefinementPassesAfterCapture(): SpherePoseRefinement.PassConfig =
        finishRefinementPassesMinimal()

    /**
     * Fast device E2E for IMU-degrade recovery: one light global pass + full align, no capture refine.
     * Target wall time < 30 s on SD845-class hardware.
     */
    /** Partial-coverage fast IMU: one rotation-only global pass + full align (<30s). */
    fun testImuRecoveryPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 1,
            convergePasses = 0,
            fullAlignOnGlobal = true
        )

    /** When true, DONE skips full global if capture refine already ran (hurts live quality). */
    fun useLightFinishWhenCaptureRefined(): Boolean = false

    /** After DONE stage-1 SO(3), skip expensive image-space global only when overlap SSD proves convergence. */
    fun shouldSkipStrongGlobalFinalization(stats: ab.inque.robotics.panorama.RefineStats?): Boolean {
        if (stats == null) return false
        if (!stats.applyGlobalPose) return false
        // Pose-graph-only refine leaves SSD at 0 — not evidence that full global is unnecessary.
        if (stats.ssdBefore <= 0f && stats.ssdAfter <= 0f) return false
        return shouldStopAfterGlobalPass(stats)
    }

    /** S17: L2b global finish uses liveRecency replace (native); stored exposure via forceStoredExposure. */
    fun useRecencyCompositeOnGlobalFinish(): Boolean = false

    fun shouldRebuildOnRefine(anyUnrefinedFrame: Boolean): Boolean = anyUnrefinedFrame

    fun maxGhostRegression(): Double = 0.02

    /** Global pose finish may shift composite vs live fast path (synth stripe tests). */
    fun maxGlobalRefineGhostDelta(): Double = 0.12

    /**
     * Worker finish export (L2b): tight gate for real-world / textured sessions.
     * Stricter than [maxGlobalRefineGhostDelta] which tolerates stripe synth artifacts.
     */
    fun maxFinishExportGhostDelta(): Double = 0.05

    fun minSharpnessRetention(): Double = 0.90

    /** Fast vs refine preview structural similarity (overlap band). */
    fun minFastRefineCorrelation(): Double = 0.88

    /** After fast rotational align, ghost must improve vs raw IMU by at least this ratio. */
    fun minFastAlignGhostImprovementRatio(): Double = 0.80

    /** Max residual SSD allowed after fast align correction (E2E sanity). */
    fun maxFastAlignResidualSSD(): Float = 15000f

    /** IMU-parity preview refine vs live (weighted replay, not R1 global gate). */
    fun minPreviewRefineCorrelation(): Double = 0.85

    /** Finish export equirect vs post-global-refine preview (R6). */
    fun minFinishPreviewCorrelation(): Double = 0.95

    /** Multi-refine session: overlap at frame window boundary (R4). */
    fun minMultiRefineBoundaryCorrelation(): Double = 0.90

    /** PG-R3: global refine must lower overlap-bank SSD by at least this fraction. */
    fun minRefineSsdImprovementRatio(): Double = 0.05

    /** Overlap-bank SSD below this → skip additional global passes. */
    fun goodGlobalRefineSsdThreshold(): Float = 250f

    /** Min fractional SSD drop to justify another global pass (production adaptive finish). */
    fun minGlobalPassSsdImprovementRatio(): Double = 0.01

    /** Finish export vs last fast preview before DONE (worker path; L2b global finish shifts composite). */
    fun minFinishVsPreviewCorrelation(): Double = 0.48

    /**
     * When true, DONE exports the fast splat buffer with light seams only (low quality).
     * False runs the same refineStoredPoses + rebuildFullAlign path as offline replay.
     */
    fun preferFastFinishExport(): Boolean = false

    /** Live capture: mirror probed offline; live probe adds frame-2 latency. */
    fun probeMirrorOnLiveCapture(): Boolean = false

    /**
     * Live capture only: stitch the center square of each frame (sharper, less edge
     * distortion than full 16:9 sensor). Planar E2E / offline replay unchanged.
     */
    fun useCenterSquareStitch(): Boolean = true

    /** Planar replay / roundtrip uses same center-square geometry as live capture (S50). */
    fun applyCenterSquareToPlanarReplay(): Boolean = useCenterSquareStitch()

    /** S12/S23: full multiband seam pass on gallery export (light seams on live preview only). */
    fun useFullSeamsOnExport(): Boolean = true

    /** Skip seam refinement on DONE preview (SAVE runs seams at gallery resolution). */
    fun runPreviewSeamRefine(): Boolean = false

    /**
     * When false, DONE export skips seam refinement to isolate resampling/compositing blur.
     * Re-enable after nearest-center + rotation-only baseline is sharp.
     */
    fun runExportSeamRefine(): Boolean = true

    /** When false, background finish polish does not replace the sharp DONE export in the viewer. */
    fun refreshViewerAfterBackgroundFinish(): Boolean = true

    /** Min interval between live viewer redraws during post-DONE refinement (avoids ANR). */
    fun viewerRefreshThrottleMs(): Long = 400L

    /** Overlap-band correlation: preview vs export after finish (stripe synth ~0.5 after global pose). */
    fun minFinishOverlapBandCorrelation(): Double = 0.51

    /** CameraX workflow: sharpness must not collapse after multi-frame capture. */
    fun minWorkflowSharpnessRetention(): Double = 0.80

    /** Parallax fixture: broad ghost score ceiling after global finish. */
    fun maxParallaxGhostAfterGlobal(): Double = 0.28

    /** AE ramp fixture: overlap luminance std after global finish + exposure norm. */
    fun maxAeRampOverlapLumaStd(): Double = 18.0

    fun minAeRampSaturationRetention(): Double = 0.90

    fun minRefineGhostImprovementRatio(): Double = 0.70

    fun maxAbsoluteGhostAfterRefine(): Double = 0.08

    fun maxWarpSeedDisagreementPx(): Float = 4f

    /** Neighbor-only chained pose passes in pre-finish schedule (legacy — now 0). */
    fun finishChainedPosePasses(): Int = 0

    /** Global passes on SAVE polish schedule. */
    fun finishGlobalPosePasses(): Int = 1

    /** Converge pass in finish schedule (legacy — now 0). */
    fun finishConvergePasses(): Int = 0

    /**
     * Global graph outer passes during mid-capture L2.
     * 0 = production default (full 6-DoF graph, 4–6 outer passes).
     */
    fun captureGlobalRefinePasses(): Int = 0

    /** Global graph outer passes on SAVE. 0 = production default (full 6-DoF). */
    fun finishGlobalRefinePassesCap(): Int = 0

    /**
     * Fast stitch / sweep-fast export: capped rotation-only global graph in native
     * ([sGlobalRefinePassesOverride] > 0). Target &lt;30s for 16 frames at 1024².
     */
    fun fastFinishGlobalRefinePasses(): Int = 2

    /**
     * Background polish keeps intrinsics + light seams only.
     * Pre-finish already drains 2 global + 1 converge before DONE.
     */
    fun runBackgroundGlobalPose(): Boolean = false

    /** F2: cap longest frame side for live-path splat (export refine uses stored frames). */
    fun liveSplatDim(): Int = 320

    /** Min interval between live preview downscales during continuous capture (photo = every frame). */
    fun previewThrottleMs(): Long = 100L

    /** S39: reject or prompt before gallery write when export fails quality gates. */
    fun enforceSaveQualityGate(): Boolean = true

    /** When true, user may override a failed quality gate via dialog. */
    fun allowSaveDespiteQualityFail(): Boolean = true

    /** S25: p95 addFrame budget for telemetry / perf E2E. */
    fun maxAddFrameP95Ms(): Long = 50L

    /** S25: max fraction of frames rejected for overlap / align. */
    fun maxFrameRejectRate(): Float = 0.10f

    /** Solid-angle coverage before pre-finish graph cache (low bar — front-load early). */
    fun preFinishMinSolidAngle(): Float = 0.15f

    /** Idle delay after last frame before pre-finish drain. */
    fun preFinishDelayMs(): Long = 200L

    /** Not used when draining all slices in one idle window. */
    fun preFinishInterSliceDelayMs(): Long = 50L

    /** True when another global pass is unlikely to help (SSD gate for SAVE polish). */
    fun shouldStopAfterGlobalPass(stats: ab.inque.robotics.panorama.RefineStats?): Boolean {
        if (stats == null || !stats.applyGlobalPose) return true
        if (stats.ssdBefore <= 0f && stats.ssdAfter <= 0f) return true
        if (stats.ssdAfter < goodGlobalRefineSsdThreshold()) return true
        if (stats.ssdBefore > 1e-6f) {
            val improvement = (stats.ssdBefore - stats.ssdAfter) / stats.ssdBefore
            if (improvement < minGlobalPassSsdImprovementRatio()) return true
        }
        return false
    }
}
