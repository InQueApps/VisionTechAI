package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.RefineStats
import ab.inque.robotics.panorama.FrameAlignmentInfo
import ab.inque.robotics.panorama.SphereNative


import android.graphics.Bitmap

/** Result of a fast-path stitch attempt. */
const val STITCH_ACCEPTED = 1
const val STITCH_REJECTED = 0
const val STITCH_LOW_OVERLAP = 2

/**
 * Testable seam around [SphereNative] for background stitching.
 */
interface SphereStitchOperations {
    fun init(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion = LensDistortion.NONE
    ): Boolean
    fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean

    /**
     * Quick stitch: splat immediately using the raw IMU quaternion (no alignment),
     * gated by minimum edge overlap. Returns [STITCH_ACCEPTED], [STITCH_REJECTED],
     * or [STITCH_LOW_OVERLAP]. Default delegates to [addFrame] for test fakes.
     */
    fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int =
        addFrameFast(bitmap, quaternion, overlapGate, null)

    fun addFrameFast(
        bitmap: Bitmap,
        quaternion: FloatArray,
        overlapGate: Float,
        nearnessMap: FloatArray?
    ): Int = if (addFrame(bitmap, quaternion)) STITCH_ACCEPTED else STITCH_REJECTED

    /**
     * Background refinement: re-align newly added frames and rebuild the
     * panorama. Returns true if the buffer changed (a refreshed preview is
     * warranted). Default is a no-op for test fakes.
     */
    fun refinePending(applyGlobalPose: Boolean = false): Boolean = false

    /** S32: true when native refine would change the panorama (dirty frames or finish upgrade). */
    fun hasRefineWorkPending(applyGlobalPose: Boolean = false): Boolean = true

    /** I3: seed overlap-bank focal scale before first frame (Camera2-derived). */
    fun setSessionFocalScale(scale: Float): Boolean = false

    /** Layer 2: global pose graph + equirect rebuild. */
    fun refineGlobalPose(): Boolean = refinePending(applyGlobalPose = true)

    /** Layer 3: overlap-bank intrinsic pass + rebuild. */
    fun refineIntrinsics(): Boolean = false

    /** Chained pose + motion-warp refine on stored frames (offline replay pass). */
    fun refineStoredPoses(): Boolean = false

    /** Resplat from current refined poses (does not reset to fast/IMU seeds). */
    fun rebuildFullAlign(): Boolean = false

    /** Targeted photometric polish on outlier frames (GN + small search). */
    fun refineTargetedPhotometric(): Int = 0

    /** SO(3) feature-graph reconciliation after local corrections. */
    fun refineFeatureGraphReconcile(): Boolean = false

    /** Conditional parallax warp where rotation-only residual remains. */
    fun refineConditionalParallax(): Int = 0

    /** Truth export: nearest-center resplat at current equirect resolution (no photometric align). */
    fun rebuildExportNearestCenter(): Boolean = false

    /** Restore refined poses from raw IMU quaternions (drops live fast-align drift). */
    fun resetPosesToImu(): Boolean = false

    /**
     * Commit queued live-capture frames (pixels + pose) into the stored-frame
     * buffer. Required before DONE export / refine when using deferred store.
     */
    fun flushPendingFrameStores(): Unit = Unit

    /**
     * Resize internal equirect to export resolution and resplat stored frames
     * (no photometric align — poses already refined at capture resolution).
     */
    fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean = false

    /**
     * Center-weighted feather resplat at preview resolution (capture / DONE viewer).
     */
    fun preparePreviewResplat(outW: Int, outH: Int): Boolean = false

    fun setGlobalRefinePasses(passes: Int) {}

    fun setFastFinishRefine(enabled: Boolean) {}

    fun setPoseGraphOnlyGlobal(enabled: Boolean) {}

    fun buildFeatureGraphCache(): Boolean = false

    fun getCoverage(): Float
    fun getSolidAngleCoverage(): Float
    fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap?
    fun getLastFrameInfo(): FrameAlignmentInfo?
    fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap?
    fun refineSeams(light: Boolean = false): Boolean = true
    fun getLastRefineStats(): RefineStats? = null
    fun getSessionDistortionK1(): Float = 0f
    fun reset()
    fun release()
}

object SphereNativeOperations : SphereStitchOperations {
    override fun init(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion
    ): Boolean = SphereNative.init(eqW, eqH, fovH, fovV, distortion)

    override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean =
        SphereNative.addFrame(bitmap, quaternion)

    override fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int =
        addFrameFast(bitmap, quaternion, overlapGate, null)

    override fun addFrameFast(
        bitmap: Bitmap,
        quaternion: FloatArray,
        overlapGate: Float,
        nearnessMap: FloatArray?
    ): Int = SphereNative.addFrameFast(bitmap, quaternion, overlapGate, nearnessMap)

    override fun refinePending(applyGlobalPose: Boolean): Boolean =
        SphereNative.refinePending(applyGlobalPose)

    override fun hasRefineWorkPending(applyGlobalPose: Boolean): Boolean =
        SphereNative.hasRefineWorkPending(applyGlobalPose)

    override fun setSessionFocalScale(scale: Float): Boolean =
        SphereNative.setSessionFocalScale(scale)

    override fun refineGlobalPose(): Boolean = SphereNative.refinePending(applyGlobalPose = true)

    override fun refineIntrinsics(): Boolean = false

    override fun refineStoredPoses(): Boolean = SphereNative.refineStoredPoses()

    override fun rebuildFullAlign(): Boolean = SphereNative.rebuildFullAlign()

    override fun refineTargetedPhotometric(): Int = SphereNative.refineTargetedPhotometric()

    override fun refineFeatureGraphReconcile(): Boolean =
        SphereNative.refineFeatureGraphReconcile()

    override fun refineConditionalParallax(): Int = SphereNative.refineConditionalParallax()

    override fun rebuildExportNearestCenter(): Boolean = SphereNative.rebuildExportNearestCenter()

    override fun resetPosesToImu(): Boolean = SphereNative.resetPosesToImu()

    override fun flushPendingFrameStores() = SphereNative.flushPendingFrameStores()

    override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean =
        SphereNative.prepareExportEquirect(exportW, exportH)

    override fun preparePreviewResplat(outW: Int, outH: Int): Boolean =
        SphereNative.preparePreviewResplat(outW, outH)

    override fun setGlobalRefinePasses(passes: Int) = SphereNative.setGlobalRefinePasses(passes)

    override fun setFastFinishRefine(enabled: Boolean) = SphereNative.setFastFinishRefine(enabled)

    override fun setPoseGraphOnlyGlobal(enabled: Boolean) =
        SphereNative.setPoseGraphOnlyGlobal(enabled)

    override fun buildFeatureGraphCache(): Boolean = SphereNative.buildFeatureGraphCache()

    override fun getCoverage(): Float = SphereNative.getCoverage()

    override fun getSolidAngleCoverage(): Float = SphereNative.getSolidAngleCoverage()

    override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? =
        SphereNative.getHoleMaskBitmap(outW, outH)

    override fun getLastFrameInfo(): FrameAlignmentInfo? = SphereNative.getLastFrameInfo()

    override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap? =
        SphereNative.getEquirectBitmap(eqW, eqH)

    override fun refineSeams(light: Boolean): Boolean = SphereNative.refineSeams(light)

    override fun getLastRefineStats(): RefineStats? = SphereNative.getLastRefineStats()

    override fun getSessionDistortionK1(): Float = SphereNative.getSessionDistortionK1()

    override fun reset() = SphereNative.reset()

    override fun release() = SphereNative.release()
}
