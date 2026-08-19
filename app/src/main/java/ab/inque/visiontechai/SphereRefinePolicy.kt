package ab.inque.visiontechai

/**
 * When background refine should run (idle debounce, periodic mid-capture, finish).
 */
object SphereRefinePolicy {

    /**
     * When true, periodic capture refine runs every [SphereStitchWorker.PERIODIC_REFINE_FRAMES]
     * accepted frames (on stitch idle, without waiting for long pause).
     */
    /**
     * Mid-capture periodic refine disabled for tap latency — pose recovery runs on
     * pause debounce ([captureRefineDelayMs]) and pre-finish/DONE instead.
     */
    fun shouldRunPeriodicRefine(
        acceptedSinceRefine: Int,
        every: Int = SphereStitchWorker.PERIODIC_REFINE_FRAMES
    ): Boolean = false

    fun acceptedCountAfterPeriodicRefine(): Int = 0

    /** Minimum stored frames before capture pose refine runs. */
    fun captureRefineMinFrames(): Int = 2

    fun captureRefineDelayMs(): Long = 350L

    /** Shorter debounce when [shouldRunPeriodicRefine] during live capture. */
    fun capturePeriodicDelayMs(): Long = 120L

    /** Mid-capture periodic refine: pose graph only — no full rebuild (keeps JNI lock short). */
    fun capturePeriodicRefinementPasses(): SpherePoseRefinement.PassConfig =
        captureRefinementPasses()

    /**
     * Mid-capture L2: chained + global with full align at capture resolution (1024²).
     */
    fun captureRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 1,
            globalPasses = 1,
            convergePasses = 0,
            fullAlignOnGlobal = true
        )

    /**
     * Full finish schedule for idle pre-DONE drain on [preFinishExecutor].
     * Heavy pose + align work runs here, not on DONE.
     */
    fun preFinishRefinementPasses(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 2,
            globalPasses = 2,
            convergePasses = 1,
            fullAlignOnGlobal = true
        )

    /** DONE fallback when pre-finish did not complete before tap (same schedule as pre-finish). */
    fun finishRefinementPassesMinimal(): SpherePoseRefinement.PassConfig =
        preFinishRefinementPasses()

    fun finishRefinementPassesExportOnly(): SpherePoseRefinement.PassConfig =
        SpherePoseRefinement.PassConfig(
            chainedPasses = 0,
            globalPasses = 0,
            convergePasses = 0,
            fullAlignOnGlobal = false
        )

    fun finishRefinementPassesFull(): SpherePoseRefinement.PassConfig =
        preFinishRefinementPasses()

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

    /** When false, background finish polish does not replace the sharp DONE export in the viewer. */
    fun refreshViewerAfterBackgroundFinish(): Boolean = true

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

    /** Neighbor-only chained pose passes in pre-finish schedule. */
    fun finishChainedPosePasses(): Int = 2

    /** Global passes in pre-finish schedule. */
    fun finishGlobalPosePasses(): Int = 2

    /** Converge pass in pre-finish schedule. */
    fun finishConvergePasses(): Int = 1

    /**
     * Global graph outer passes during mid-capture L2.
     * 0 = production default (full 6-DoF graph, 4–6 outer passes).
     */
    fun captureGlobalRefinePasses(): Int = 0

    /** Global graph outer passes on DONE. 0 = production default (full 6-DoF). */
    fun finishGlobalRefinePassesCap(): Int = 0

    /**
     * When false, global pose runs only in foreground finish (viewer export quality).
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

    /** Solid-angle coverage before pre-finish drain (low bar — front-load early). */
    fun preFinishMinSolidAngle(): Float = 0.15f

    /** Idle delay after last frame before pre-finish drain. */
    fun preFinishDelayMs(): Long = 200L

    /** Not used when draining all slices in one idle window. */
    fun preFinishInterSliceDelayMs(): Long = 50L
}
