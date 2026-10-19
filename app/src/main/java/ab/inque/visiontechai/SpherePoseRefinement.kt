package ab.inque.visiontechai

/**
 * Shared pose refinement stages: chained neighbor rotation, global sparse graph
 * (rotation + translation), optional full visual re-align.
 */
object SpherePoseRefinement {

  data class PassConfig(
    val chainedPasses: Int,
    val globalPasses: Int,
    val convergePasses: Int,
    /** When false, global passes only soft-rebuild in native (preview-safe). */
    val fullAlignOnGlobal: Boolean
  )

  data class RefineProgress(
    val chainedPassesDone: Int = 0,
    val globalPassesDone: Int = 0,
    val convergePassesDone: Int = 0
  ) {
    fun isEmpty(): Boolean =
      chainedPassesDone == 0 && globalPassesDone == 0 && convergePassesDone == 0

    fun isCompleteFor(config: PassConfig): Boolean =
      chainedPassesDone >= config.chainedPasses &&
        globalPassesDone >= config.globalPasses &&
        convergePassesDone >= config.convergePasses

    fun totalSlicesFor(config: PassConfig): Int =
      config.chainedPasses + config.globalPasses + config.convergePasses

    fun slicesDoneFor(config: PassConfig): Int =
      chainedPassesDone.coerceAtMost(config.chainedPasses) +
        globalPassesDone.coerceAtMost(config.globalPasses) +
        convergePassesDone.coerceAtMost(config.convergePasses)
  }

  data class SliceResult(
    val changed: Boolean,
    val progress: RefineProgress,
    val passLabel: String?
  )

  /**
   * Run one incremental slice (one chained, global+optional rebuild, or converge pass).
   * Returns unchanged progress if already complete.
   */
  fun runOneSlice(
    operations: SphereStitchOperations,
    config: PassConfig,
    progress: RefineProgress,
    isCancelled: () -> Boolean = { false }
  ): SliceResult {
    if (progress.isCompleteFor(config) || isCancelled()) {
      return SliceResult(false, progress, null)
    }
    if (progress.chainedPassesDone < config.chainedPasses) {
      val next = progress.chainedPassesDone + 1
      val refined = operations.refineStoredPoses()
      return SliceResult(
        refined,
        progress.copy(chainedPassesDone = next),
        "chained $next"
      )
    }
    if (progress.globalPassesDone < config.globalPasses) {
      if (!operations.hasRefineWorkPending(applyGlobalPose = true)) {
        return SliceResult(
          changed = false,
          progress = progress.copy(globalPassesDone = config.globalPasses),
          passLabel = "global skip (no-op)"
        )
      }
      val next = progress.globalPassesDone + 1
      val global = operations.refineGlobalPose()
      val rebuilt = if (config.fullAlignOnGlobal && global) {
        operations.rebuildFullAlign()
      } else {
        false
      }
      return SliceResult(
        global || rebuilt,
        progress.copy(globalPassesDone = next),
        "global $next: global=$global rebuild=$rebuilt"
      )
    }
    if (progress.convergePassesDone < config.convergePasses) {
      if (!operations.hasRefineWorkPending(applyGlobalPose = true)) {
        return SliceResult(
          changed = false,
          progress = progress.copy(convergePassesDone = config.convergePasses),
          passLabel = "converge skip (no-op)"
        )
      }
      val next = progress.convergePassesDone + 1
      val refined = operations.refineStoredPoses()
      val global = operations.refineGlobalPose()
      val rebuilt = if (config.fullAlignOnGlobal && global) {
        operations.rebuildFullAlign()
      } else {
        false
      }
      return SliceResult(
        refined || global || rebuilt,
        progress.copy(convergePassesDone = next),
        "converge $next: refine=$refined global=$global rebuild=$rebuilt"
      )
    }
    return SliceResult(false, progress, null)
  }

  fun run(
    operations: SphereStitchOperations,
    config: PassConfig,
    startProgress: RefineProgress = RefineProgress(),
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Boolean {
    return runAdaptive(
      operations = operations,
      config = config,
      startProgress = startProgress,
      isCancelled = isCancelled,
      onPass = onPass
    )
  }

  /**
   * Run configured slices; after each global pass, stop early when SSD gates say
   * another pass is unlikely to help (SAVE polish may still run one extra pass).
   */
  fun runAdaptive(
    operations: SphereStitchOperations,
    config: PassConfig,
    startProgress: RefineProgress = RefineProgress(),
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Boolean {
    SphereProductionStitchPipeline.ensureCaptureWorkCommitted(operations)
    var changed = false
    var progress = startProgress
    var extraGlobalAllowed = true
    while (!progress.isCompleteFor(config) && !isCancelled()) {
      val result = runOneSlice(operations, config, progress, isCancelled)
      if (isCancelled()) break
      changed = changed || result.changed
      if (result.passLabel != null) onPass(result.passLabel)
      if (result.progress == progress) break
      progress = result.progress
      if (result.passLabel != null && result.passLabel.contains("global")) {
        val stats = operations.getLastRefineStats()
        if (SphereRefinePolicy.shouldStopAfterGlobalPass(stats)) {
          progress = progress.copy(
            globalPassesDone = config.globalPasses,
            convergePassesDone = config.convergePasses
          )
        } else if (extraGlobalAllowed && progress.globalPassesDone < config.globalPasses) {
          extraGlobalAllowed = false
        }
      }
    }
    return changed
  }
}
