package ab.inque.robotics.panorama

import android.graphics.Bitmap
import android.util.Log
import ab.inque.robotics.math.LensDistortion

data class RefineStats(
    val ssdBefore: Float,
    val ssdAfter: Float,
    val framesRealigned: Int,
    val applyGlobalPose: Boolean,
    val ran: Boolean
) {
    /** Fraction of pre-refine SSD retained (lower is better). */
    val ssdRetentionRatio: Float
        get() = if (ssdBefore > 1e-6f) ssdAfter / ssdBefore else 1f
}

data class FrameAlignmentInfo(
    val frameIndex: Int,
    val overlapCount: Int,
    val totalSamples: Int,
    val alignmentOffsetDeg: Float,
    val residualSSD: Float,
    val wasRejected: Boolean,
    val rollOffsetDeg: Float = 0f,
    val warpTxPx: Float = 0f,
    val warpTyPx: Float = 0f
) {
    val overlapRatio: Float get() = if (totalSamples > 0) overlapCount.toFloat() / totalSamples else 0f
    val isFirstFrame: Boolean get() = frameIndex <= 1
    val hasLowOverlap: Boolean get() = !isFirstFrame && overlapRatio < 0.05f
    val hasExcessiveShift: Boolean get() = !isFirstFrame && alignmentOffsetDeg > 5f
}

/** JNI wrapper for the C++ equirectangular panorama stitcher. */
object SphereNative {

    private const val TAG = "SphereNative"
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_sphere")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            Log.w(TAG, "vision_sphere native library not available")
        }
    }

    fun isAvailable() = loaded

    fun init(
        eqW: Int = 4096,
        eqH: Int = 2048,
        fovH: Float = 65f,
        fovV: Float = 50f,
        distortion: LensDistortion = LensDistortion.NONE
    ): Boolean {
        if (!loaded) return false
        return nativeInit(eqW, eqH, fovH, fovV, distortion.k1, distortion.k2, distortion.p1, distortion.p2)
    }

    fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean =
        addFrameAligned(bitmap, quaternion, allowReject = true)

    /** Full visual alignment; set [allowReject]=false for offline replay of saved sessions. */
    fun addFrameAligned(
        bitmap: Bitmap,
        quaternion: FloatArray,
        allowReject: Boolean = true
    ): Boolean {
        if (!loaded || quaternion.size < 4) return false
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return nativeAddFrame(
            pixels, w, h,
            quaternion[0], quaternion[1], quaternion[2], quaternion[3],
            allowReject
        )
    }

    fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int =
        addFrameFast(bitmap, quaternion, overlapGate, null)

    fun addFrameFast(
        bitmap: Bitmap,
        quaternion: FloatArray,
        overlapGate: Float,
        nearnessMap: FloatArray? = null,
        allowReject: Boolean = true,
        cameraEv: Float = 0f
    ): Int {
        if (!loaded || quaternion.size < 4) return 0
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val depth = if (nearnessMap != null && nearnessMap.size == w * h) nearnessMap else null
        return nativeAddFrameFast(
            pixels, w, h,
            quaternion[0], quaternion[1], quaternion[2], quaternion[3],
            overlapGate,
            depth,
            allowReject,
            cameraEv
        )
    }

    fun refinePending(applyGlobalPose: Boolean = false): Boolean {
        if (!loaded) return false
        return nativeRefinePending(applyGlobalPose)
    }

    /** Commit deferred live-capture frame stores before export/refine. */
    fun flushPendingFrameStores() {
        if (!loaded) return
        nativeFlushPendingFrameStores()
    }

    /** S32: whether [refinePending] would run (avoids no-op global passes). */
    fun hasRefineWorkPending(applyGlobalPose: Boolean = false): Boolean {
        if (!loaded) return false
        return nativeHasRefineWorkPending(applyGlobalPose)
    }

    fun setSessionFocalScale(scale: Float): Boolean {
        if (!loaded) return false
        return nativeSetSessionFocalScale(scale)
    }

    /** Layer 3: focal/k1 intrinsic pass after global pose refine. */
    fun refineIntrinsicsPending(): Boolean {
        if (!loaded) return false
        return nativeRefineIntrinsicsPending()
    }

    fun setSessionFov(fovH: Float, fovV: Float): Boolean {
        if (!loaded) return false
        return nativeSetSessionFov(fovH, fovV)
    }

    fun refineSeams(light: Boolean = false): Boolean {
        if (!loaded) return false
        return nativeRefineSeams(light)
    }

    /** Global pose + parallax warp on stored frames (requires existing stitch + sFrames). */
    fun refineStoredPoses(): Boolean {
        if (!loaded) return false
        return nativeRefineStoredPoses()
    }

    /** Resplat from current refined poses at full resolution (does not reset to IMU/fast). */
    fun rebuildFullAlign(): Boolean {
        if (!loaded) return false
        return nativeRebuildFullAlign()
    }

    /** Targeted GN/small-search polish on outlier frames only. */
    fun refineTargetedPhotometric(): Int {
        if (!loaded) return 0
        return nativeRefineTargetedPhotometric()
    }

    /** Cheap SO(3) reconciliation after local pose corrections. */
    fun refineFeatureGraphReconcile(): Boolean {
        if (!loaded) return false
        return nativeRefineFeatureGraphReconcile()
    }

    /** Parallax warp on frames with residual rotation error only. */
    fun refineConditionalParallax(): Int {
        if (!loaded) return 0
        return nativeRefineConditionalParallax()
    }

    /** Resplat stored poses with nearest-center overlap (no photometric align). */
    fun rebuildExportNearestCenter(): Boolean {
        if (!loaded) return false
        return nativeRebuildExportNearestCenter()
    }

    /** Reset refined poses to imuQuat (clears fast-path align drift). */
    fun resetPosesToImu(): Boolean {
        if (!loaded) return false
        return nativeResetPosesToImu()
    }

    fun equirectWidth(): Int = if (loaded) nativeGetEquirectWidth() else 0
    fun equirectHeight(): Int = if (loaded) nativeGetEquirectHeight() else 0

    /**
     * Copy the native stitch buffer. Never upscales: a larger request returns null
     * so SAVE cannot treat a preview buffer as the final 360.
     */
    fun getEquirectBitmap(eqW: Int = 0, eqH: Int = 0): Bitmap? {
        if (!loaded) return null
        val nativeW = nativeGetEquirectWidth()
        val nativeH = nativeGetEquirectHeight()
        if (nativeW <= 0 || nativeH <= 0) return null
        if (eqW > 0 && eqH > 0 && (eqW > nativeW || eqH > nativeH)) return null
        val w = if (eqW <= 0) nativeW else eqW
        val h = if (eqH <= 0) nativeH else eqH
        val pixels = IntArray(w * h)
        if (!nativeGetEquirect(pixels, w, h)) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    fun getRectilinearBitmap(outW: Int, outH: Int, azimuth: Float, elevation: Float, fov: Float): Bitmap? {
        if (!loaded) return null
        val pixels = IntArray(outW * outH)
        if (!nativeGetRectilinear(pixels, outW, outH, azimuth, elevation, fov)) return null
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    fun loadEquirect(bitmap: Bitmap): Boolean {
        if (!loaded) return false
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return nativeLoadEquirect(pixels, w, h)
    }

    fun getCoverage(): Float {
        if (!loaded) return 0f
        return nativeGetCoverage()
    }

    fun getSolidAngleCoverage(): Float {
        if (!loaded) return 0f
        return nativeGetSolidAngleCoverage()
    }

    fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? {
        if (!loaded || outW <= 0 || outH <= 0) return null
        val pixels = IntArray(outW * outH)
        if (!nativeGetHoleMask(pixels, outW, outH)) return null
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    fun getLastFrameInfo(): FrameAlignmentInfo? {
        if (!loaded) return null
        val data = nativeGetLastFrameInfo() ?: return null
        if (data.size < 6) return null
        return FrameAlignmentInfo(
            frameIndex = data[0].toInt(),
            overlapCount = data[1].toInt(),
            totalSamples = data[2].toInt(),
            alignmentOffsetDeg = data[3],
            residualSSD = data[4],
            wasRejected = data[5].toInt() != 0,
            rollOffsetDeg = if (data.size >= 7) data[6] else 0f,
            warpTxPx = if (data.size >= 8) data[7] else 0f,
            warpTyPx = if (data.size >= 9) data[8] else 0f
        )
    }

    /** Visual-align corrected orientation from the most recent [addFrame] / [addFrameAligned]. */
    fun getLastCorrectedQuaternion(): FloatArray? {
        if (!loaded) return null
        return nativeGetLastCorrectedQuaternion()
    }

    /** Per-frame [fastCorrectedQuat] after stitch (one xyzw quaternion per stored frame). */
    fun getStoredFrameQuaternions(): List<FloatArray>? {
        if (!loaded) return null
        val flat = nativeGetStoredFrameQuaternions() ?: return null
        if (flat.isEmpty()) return emptyList()
        require(flat.size % 4 == 0) { "stored quaternion buffer size ${flat.size}" }
        return List(flat.size / 4) { i ->
            floatArrayOf(flat[i * 4], flat[i * 4 + 1], flat[i * 4 + 2], flat[i * 4 + 3])
        }
    }

    fun getSessionFocalScale(): Float {
        if (!loaded) return 1f
        return nativeGetSessionFocalScale()
    }

    fun getSessionDistortionK1(): Float {
        if (!loaded) return 0f
        return nativeGetSessionDistortionK1()
    }

    fun getOverlapBankSize(): Int {
        if (!loaded) return 0
        return nativeGetOverlapBankSize()
    }

    /** Mean photometric ray SSD over the overlap bank (no focal prior). */
    fun evalOverlapBankSSD(): Float {
        if (!loaded) return Float.MAX_VALUE
        return nativeEvalOverlapBankSSD()
    }

    /** Stats from the most recent [refinePending] call. */
    fun getLastRefineStats(): RefineStats? {
        if (!loaded) return null
        val data = nativeGetLastRefineStats() ?: return null
        if (data.size < 5) return null
        return RefineStats(
            ssdBefore = data[0],
            ssdAfter = data[1],
            framesRealigned = data[2].toInt(),
            applyGlobalPose = data[3].toInt() != 0,
            ran = data[4].toInt() != 0
        )
    }

    fun reset() {
        if (loaded) nativeReset()
    }

    /** Negate camera-frame X in ray projection (fixes mirrored back-camera buffers). */
    fun setMirrorCameraX(mirror: Boolean) {
        if (loaded) nativeSetMirrorCameraX(mirror)
    }

    fun release() {
        if (loaded) nativeRelease()
    }

    /** Caps [globalRefinePosesLocked] outer passes for fast E2E (0 = production default). */
    fun setGlobalRefinePasses(passes: Int) {
        if (loaded) nativeSetGlobalRefinePasses(passes)
    }

    /** Chained pose + soft rebuild only in [refineStoredPoses] (skips motion-warp pass). */
    fun setFastFinishRefine(enabled: Boolean) {
        if (loaded) nativeSetFastFinishRefine(enabled)
    }

    /**
     * DONE preview: cached feature-edge SO(3) solve only (no image SSD global iterations).
     */
    fun setPoseGraphOnlyGlobal(enabled: Boolean) {
        if (loaded) nativeSetPoseGraphOnlyGlobal(enabled)
    }

    /** Idle pre-finish: BRIEF + relative rotation edges without pose solve. */
    fun buildFeatureGraphCache(): Boolean {
        if (!loaded) return false
        return nativeBuildFeatureGraphCache()
    }

    /**
     * Grow/shrink internal equirect to export size and resplat all stored frames
     * without photometric align (fast DONE after background pre-finish).
     */
    fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean {
        if (!loaded) return false
        return nativePrepareExportEquirect(exportW, exportH)
    }

    /** Center-weighted feather resplat for DONE / capture preview (no seams). */
    fun preparePreviewResplat(outW: Int, outH: Int): Boolean {
        if (!loaded) return false
        return nativePreparePreviewResplat(outW, outH)
    }

    /** F2: longest frame side for live-path splat downscale (full-res stored for refine). */
    fun liveSplatDim(): Int = if (loaded) nativeGetLiveSplatDim() else 320

    /** Isolated BRIEF detect + mutual match + RANSAC benchmark (logs SphereProfile). */
    fun profileFeatureMatcher(bitmapA: Bitmap, bitmapB: Bitmap) {
        if (!loaded) return
        val wA = bitmapA.width
        val hA = bitmapA.height
        val wB = bitmapB.width
        val hB = bitmapB.height
        val pxA = IntArray(wA * hA)
        val pxB = IntArray(wB * hB)
        bitmapA.getPixels(pxA, 0, wA, 0, 0, wA, hA)
        bitmapB.getPixels(pxB, 0, wB, 0, 0, wB, hB)
        nativeProfileFeatureMatcher(pxA, wA, hA, pxB, wB, hB)
    }

    /** Live pairwise search vs last stored frame (logs SphereProfile section timers). */
    fun profileLivePairwise(bitmap: Bitmap, quaternion: FloatArray) {
        if (!loaded || quaternion.size < 4) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        nativeProfileLivePairwise(
            pixels, w, h,
            quaternion[0], quaternion[1], quaternion[2], quaternion[3]
        )
    }

    /** Feature graph edge build over stored frames (logs SphereProfile). */
    fun profileFeatureGraph() {
        if (loaded) nativeProfileFeatureGraph()
    }

    private external fun nativeInit(
        eqW: Int, eqH: Int, fovH: Float, fovV: Float,
        k1: Float, k2: Float, p1: Float, p2: Float
    ): Boolean
    private external fun nativeAddFrame(
        pixels: IntArray, w: Int, h: Int,
        qx: Float, qy: Float, qz: Float, qw: Float,
        allowReject: Boolean
    ): Boolean
    private external fun nativeAddFrameFast(
        pixels: IntArray, w: Int, h: Int,
        qx: Float, qy: Float, qz: Float, qw: Float,
        overlapGate: Float,
        depth: FloatArray?,
        allowReject: Boolean,
        cameraEv: Float
    ): Int
    private external fun nativeRefinePending(applyGlobalPose: Boolean): Boolean
    private external fun nativeFlushPendingFrameStores()
    private external fun nativeHasRefineWorkPending(applyGlobalPose: Boolean): Boolean
    private external fun nativeRefineIntrinsicsPending(): Boolean
    private external fun nativeGetEquirect(pixelsOut: IntArray, outW: Int, outH: Int): Boolean
    private external fun nativeGetEquirectWidth(): Int
    private external fun nativeGetEquirectHeight(): Int
    private external fun nativeSetSessionFov(fovH: Float, fovV: Float): Boolean
    private external fun nativeRefineSeams(light: Boolean): Boolean
    private external fun nativeRefineStoredPoses(): Boolean
    private external fun nativeRebuildFullAlign(): Boolean
    private external fun nativeRefineTargetedPhotometric(): Int
    private external fun nativeRefineFeatureGraphReconcile(): Boolean
    private external fun nativeRefineConditionalParallax(): Int
    private external fun nativeRebuildExportNearestCenter(): Boolean
    private external fun nativeResetPosesToImu(): Boolean
    private external fun nativeGetRectilinear(pixelsOut: IntArray, outW: Int, outH: Int, azimuth: Float, elevation: Float, fov: Float): Boolean
    private external fun nativeLoadEquirect(pixels: IntArray, w: Int, h: Int): Boolean
    private external fun nativeGetCoverage(): Float
    private external fun nativeGetSolidAngleCoverage(): Float
    private external fun nativeGetHoleMask(pixelsOut: IntArray, outW: Int, outH: Int): Boolean
    private external fun nativeGetLastFrameInfo(): FloatArray?
    private external fun nativeGetLastCorrectedQuaternion(): FloatArray?
    private external fun nativeGetStoredFrameQuaternions(): FloatArray?
    private external fun nativeGetOverlapBankSize(): Int
    private external fun nativeGetSessionFocalScale(): Float
    private external fun nativeSetSessionFocalScale(scale: Float): Boolean
    private external fun nativeGetSessionDistortionK1(): Float
    private external fun nativeEvalOverlapBankSSD(): Float
    private external fun nativeGetLastRefineStats(): FloatArray?
    private external fun nativeSetGlobalRefinePasses(passes: Int)
    private external fun nativeSetFastFinishRefine(enabled: Boolean)
    private external fun nativeSetPoseGraphOnlyGlobal(enabled: Boolean)
    private external fun nativeBuildFeatureGraphCache(): Boolean
    private external fun nativePrepareExportEquirect(exportW: Int, exportH: Int): Boolean
    private external fun nativePreparePreviewResplat(outW: Int, outH: Int): Boolean
    private external fun nativeGetLiveSplatDim(): Int
    private external fun nativeProfileFeatureMatcher(
        pixelsA: IntArray, wA: Int, hA: Int,
        pixelsB: IntArray, wB: Int, hB: Int
    )
    private external fun nativeProfileLivePairwise(
        pixels: IntArray, w: Int, h: Int,
        qx: Float, qy: Float, qz: Float, qw: Float
    )
    private external fun nativeProfileFeatureGraph()
    private external fun nativeReset()
    private external fun nativeSetMirrorCameraX(mirror: Boolean)
    private external fun nativeRelease()
}
