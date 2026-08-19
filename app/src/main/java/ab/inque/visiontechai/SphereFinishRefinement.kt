package ab.inque.visiontechai

/**
 * DONE / offline replay pose refinement. Runs the full pre-finish schedule for any
 * slices not already completed during idle capture.
 */
object SphereFinishRefinement {

  fun run(
    operations: SphereStitchOperations,
    @Suppress("UNUSED_PARAMETER") captureRefined: Boolean = false,
    startProgress: SpherePoseRefinement.RefineProgress = SpherePoseRefinement.RefineProgress(),
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ) {
    val config = SphereRefinePolicy.preFinishRefinementPasses()
    if (startProgress.isCompleteFor(config)) return
    SpherePoseRefinement.run(
      operations = operations,
      config = config,
      startProgress = startProgress,
      isCancelled = isCancelled,
      onPass = onPass
    )
  }
}
