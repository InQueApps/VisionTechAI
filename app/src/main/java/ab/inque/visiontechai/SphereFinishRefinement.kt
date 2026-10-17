package ab.inque.visiontechai

/**
 * DONE / offline replay pose refinement. Runs preview-orientation passes not
 * already completed during idle capture.
 */
object SphereFinishRefinement {

  fun run(
    operations: SphereStitchOperations,
    @Suppress("UNUSED_PARAMETER") captureRefined: Boolean = false,
    startProgress: SpherePoseRefinement.RefineProgress = SpherePoseRefinement.RefineProgress(),
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ) {
    val config = SphereRefinePolicy.donePreviewRefinementPasses()
    if (startProgress.isCompleteFor(config)) return
    SpherePoseRefinement.runAdaptive(
      operations = operations,
      config = config,
      startProgress = startProgress,
      isCancelled = isCancelled,
      onPass = onPass
    )
  }
}
