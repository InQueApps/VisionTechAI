package ab.inque.visiontechai

/**
 * Mid-capture pose refinement (debounced + periodic): chained rotation,
 * motion-warp translation/parallax, and global 6-DoF pose graph with full align.
 */
object SphereCaptureRefinement {

  fun run(
    operations: SphereStitchOperations,
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Boolean = SpherePoseRefinement.run(
    operations = operations,
    config = SphereRefinePolicy.captureRefinementPasses(),
    isCancelled = isCancelled,
    onPass = onPass
  )

  /** Periodic idle refine while scanning (pose-only, same as pause refine). */
  fun runPeriodic(
    operations: SphereStitchOperations,
    isCancelled: () -> Boolean = { false },
    onPass: (label: String) -> Unit = {}
  ): Boolean = SpherePoseRefinement.run(
    operations = operations,
    config = SphereRefinePolicy.capturePeriodicRefinementPasses(),
    isCancelled = isCancelled,
    onPass = onPass
  )
}
