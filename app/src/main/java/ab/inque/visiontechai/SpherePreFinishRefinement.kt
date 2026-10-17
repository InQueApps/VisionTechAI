package ab.inque.visiontechai

/**
 * Idle pre-DONE: cache feature matches + relative rotation edges (no panorama rebuild).
 */
object SpherePreFinishRefinement {

  fun targetConfig(): SpherePoseRefinement.PassConfig =
    SphereRefinePolicy.preFinishRefinementPasses()

  /** Build BRIEF features and rotation edges when capture is idle before DONE. */
  fun runGraphCacheSlice(operations: SphereStitchOperations): Boolean {
    SphereProductionStitchPipeline.ensureCaptureWorkCommitted(operations)
    return operations.buildFeatureGraphCache()
  }

  fun runOneSlice(
    operations: SphereStitchOperations,
    progress: SpherePoseRefinement.RefineProgress,
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Pair<Boolean, SpherePoseRefinement.RefineProgress> {
    if (runGraphCacheSlice(operations)) {
      onPass("graph cache")
    }
    return false to progress
  }
}
