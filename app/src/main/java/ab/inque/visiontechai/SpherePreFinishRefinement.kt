package ab.inque.visiontechai

/**
 * Idle pre-DONE finish passes: one heavy slice at a time on a slow background thread
 * while the user reviews coverage (solid angle ≥ threshold, no new frames).
 */
object SpherePreFinishRefinement {

  fun targetConfig(): SpherePoseRefinement.PassConfig =
    SphereRefinePolicy.preFinishRefinementPasses()

  fun runOneSlice(
    operations: SphereStitchOperations,
    progress: SpherePoseRefinement.RefineProgress,
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Pair<Boolean, SpherePoseRefinement.RefineProgress> {
    val result = SpherePoseRefinement.runOneSlice(
      operations = operations,
      config = targetConfig(),
      progress = progress,
      isCancelled = isCancelled
    )
    if (result.passLabel != null) {
      onPass("preFinish ${result.passLabel}")
    }
    return result.changed to result.progress
  }
}
