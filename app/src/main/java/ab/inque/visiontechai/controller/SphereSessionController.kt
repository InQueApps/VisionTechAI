package ab.inque.visiontechai.controller

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.Constants
import ab.inque.visiontechai.LensDistortionStore
import ab.inque.visiontechai.OrientationTracker
import ab.inque.visiontechai.SavedMediaTracker
import ab.inque.visiontechai.BuildConfig
import ab.inque.visiontechai.SphereCaptureController
import ab.inque.visiontechai.CameraLensIntrinsics
import ab.inque.visiontechai.SphereCoverageFormatter
import ab.inque.visiontechai.SphereMotionDepthEstimator
import ab.inque.visiontechai.DepthEstimationProcessor
import ab.inque.visiontechai.SphereExtrinsicCalibration
import ab.inque.visiontechai.SphereFovHelper
import ab.inque.visiontechai.SpherePreviewBinder
import ab.inque.visiontechai.SphereExportNotificationHelper
import ab.inque.visiontechai.SphereExportPersistence
import ab.inque.visiontechai.SphereExportQualityGate
import ab.inque.visiontechai.SphereRefinePolicy
import ab.inque.visiontechai.SphereStitchTelemetry
import ab.inque.visiontechai.SphereXmpMetadata
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.sphere.debug.SphereDebugSessionRecorder
import ab.inque.visiontechai.CameraFrameOrientation
import ab.inque.visiontechai.sphere.debug.SphereDebugSessionReplayer
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages 360° sphere capture lifecycle and equirectangular export.
 */
class SphereSessionController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val orientationTracker: OrientationTracker,
    private val setVisionMode: (VisionMode) -> Unit,
    private val getCameraFovH: () -> Float,
    private val getCameraFovV: () -> Float,
    private val getAnalysisWidth: () -> Int,
    private val getAnalysisHeight: () -> Int,
    private val getDisplayRotationDegrees: () -> Int,
    private val getUseFrontCamera: () -> Boolean = { false },
    private val depthProcessor: DepthEstimationProcessor? = null
) {
    var controller: SphereCaptureController? = null
        internal set

    private val extrinsicCalibration = SphereExtrinsicCalibration(activity)
    private val lensDistortionStore = LensDistortionStore(activity)
    private val debugRecorder = SphereDebugSessionRecorder(activity)
    private val debugExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val exportNotificationHelper = SphereExportNotificationHelper(activity)
    private val motionDepthEstimator = SphereMotionDepthEstimator(depthProcessor)

    @Volatile
    private var debugReplayRunning = false

    private var sessionFovH = 65f
    private var sessionFovV = 50f
    private var sessionDistortion: LensDistortion = LensDistortion.NONE

    @Volatile
    var captureNextFrame: Boolean = false

    /** Photo vs video shutter mode while in sphere capture (mirrors MainActivity.isPhotoMode). */
    @Volatile
    var sphereCaptureIsPhotoMode: Boolean = true

    @Volatile
    var lastRawFrame: Bitmap? = null
        private set

    @Volatile
    private var lastRawFrameTimestampNanos: Long = 0L

    @Volatile
    private var lastImageRotationDegrees: Int = 0

    /** Bumped when sphere UI session ends so stale controller callbacks are ignored. */
    @Volatile
    private var sphereUiGeneration = 0

    private fun invalidateSphereUiCallbacks() {
        sphereUiGeneration++
    }

    private fun isCurrentSphereUi(generation: Int): Boolean = generation == sphereUiGeneration

    fun enter() {
        checkInterruptedExport()
        if (isBackgroundExportActive()) {
            Toast.makeText(
                activity,
                "Still finishing your previous 360° photo…",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        controller?.release()
        controller = null
        invalidateSphereUiCallbacks()
        val uiGeneration = sphereUiGeneration
        setVisionMode(VisionMode.SPHERE)
        orientationTracker.start()
        wireViewerOrientation()
        clearSphereCapturePreview()
        binding.sphereViewerView.cleanup()
        val ctrl = SphereCaptureController(
            orientationTracker,
            extrinsicCalibration,
            onCoverageUpdate = { area, solid, preview ->
                activity.runOnUiThread {
                    if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                    binding.sphereCoverageText.text =
                        SphereCoverageFormatter.format(area, solid)
                    if (preview != null) {
                        SpherePreviewBinder.apply(binding.spherePreviewImage, preview)
                    }
                }
            },
            onPreviewReady = { equirect ->
                activity.runOnUiThread {
                    if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                    updateSphereViewerEquirect(equirect)
                }
            },
            onFrameWarning = { warning ->
                activity.runOnUiThread {
                    if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                    val msg = when (warning) {
                        SphereCaptureController.FrameWarning.NO_OVERLAP ->
                            "No overlap — move back toward previous frames"
                        SphereCaptureController.FrameWarning.EXCESSIVE_SHIFT ->
                            "Slow down — too much movement between frames"
                        SphereCaptureController.FrameWarning.ALIGNMENT_FAILED ->
                            "Frame skipped — could not align with panorama"
                        SphereCaptureController.FrameWarning.INSUFFICIENT_OVERLAP ->
                            "Pan slower — not enough overlap with previous frames"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onFrameAccepted = {
                activity.runOnUiThread {
                    if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                    Toast.makeText(activity, "Frame added to sphere", Toast.LENGTH_SHORT).show()
                }
            },
            onIntrinsicsConverged = { distortion ->
                lensDistortionStore.set(distortion)
                sessionDistortion = distortion
                Log.i(TAG, "Persisted session k1=${distortion.k1}")
            },
            motionDepthEstimator = { bitmap -> motionDepthEstimator.estimateForStitch(bitmap) }
        )
        ctrl.onExportViewerSnapshot = { snapshot ->
            activity.runOnUiThread {
                if (!isCurrentSphereUi(uiGeneration)) {
                    if (!snapshot.isRecycled) snapshot.recycle()
                    return@runOnUiThread
                }
                if (binding.sphereViewerOverlay.visibility == View.VISIBLE) {
                    binding.sphereViewerView.setEquirectBitmap(snapshot)
                }
                if (!snapshot.isRecycled) snapshot.recycle()
            }
        }
        ctrl.onFullExportReady = { _ ->
            activity.runOnUiThread {
                if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                if (binding.sphereViewerOverlay.visibility == View.VISIBLE) {
                    binding.sphereSaveButton.isEnabled = !ctrl.isFinalizationInProgress()
                    binding.sphereSaveButton.text = if (ctrl.isFinalized()) "SAVE" else "SAVE"
                }
            }
        }
        ctrl.onExportProgress = { percent, stage ->
            activity.runOnUiThread {
                if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                if (binding.sphereViewerOverlay.visibility != View.VISIBLE) return@runOnUiThread
                val refining = ctrl.isFinalizationInProgress()
                binding.sphereSaveButton.isEnabled = !refining
                binding.sphereSaveButton.text = when {
                    refining && percent < 100 -> when {
                        stage.contains("seams", ignoreCase = true) -> "Finalizing… seams"
                        stage == "prepareExport" -> "Finalizing… export"
                        stage == "previewResplat" -> "Finalizing… preview"
                        stage == "finalization_failed" -> "Finalizing failed"
                        percent < 35 -> "Finalizing… align"
                        else -> "Finalizing… $percent%"
                    }
                    ctrl.isFinalized() -> "SAVE"
                    stage == "finalization_failed" -> "Retry SAVE"
                    else -> "Finalizing…"
                }
            }
        }
        ctrl.onBackgroundPolishReady = {
            activity.runOnUiThread {
                if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                if (binding.sphereViewerOverlay.visibility == View.VISIBLE) {
                    Toast.makeText(
                        activity,
                        "Preview improved — finalizing full quality",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        ctrl.onStitchProcessingChanged = { _ ->
            activity.runOnUiThread {
                if (!isCurrentSphereUi(uiGeneration)) return@runOnUiThread
                syncSpherePhotoCaptureButton()
            }
        }
        ctrl.onDebugFrameCaptured = { bitmap, ts, imuQuat, calQuat ->
            if (debugRecorder.isRecording) {
                debugRecorder.recordFrame(bitmap, ts, imuQuat, calQuat)
            }
        }
        ctrl.onDebugFrameStitchResult = { accepted, overlapRatio, alignmentOffsetDeg, wasRejected ->
            if (debugRecorder.isRecording) {
                debugRecorder.updateLastFrameOutcome(
                    accepted = accepted,
                    overlapRatio = overlapRatio,
                    alignmentOffsetDeg = alignmentOffsetDeg,
                    wasRejected = wasRejected,
                    stitchedQuaternion = SphereNative.getLastCorrectedQuaternion()
                )
            }
        }
        controller = ctrl
        val isPortrait = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val sensorFovH = getCameraFovH()
        val sensorFovV = getCameraFovV()
        val (effectiveFovH, effectiveFovV) = SphereFovHelper.effectiveFov(
            isPortrait = isPortrait,
            fovH = sensorFovH,
            fovV = sensorFovV,
            analysisWidth = getAnalysisWidth().coerceAtLeast(320),
            analysisHeight = getAnalysisHeight().coerceAtLeast(240),
            rotationDegrees = getDisplayRotationDegrees()
        )
        sessionFovH = effectiveFovH
        sessionFovV = effectiveFovV
        sessionDistortion = CameraLensIntrinsics.resolveSessionDistortion(activity, lensDistortionStore)
        controller?.startSession(
            sensorFovH = sensorFovH,
            sensorFovV = sensorFovV,
            fovH = effectiveFovH,
            fovV = effectiveFovV,
            distortion = sessionDistortion,
            bitmapAlreadyMirrored = getUseFrontCamera()
        )
        CameraLensIntrinsics.applySessionFocalScaleSeed(activity, sensorFovH)
        if (!SphereNative.isAvailable()) {
            Toast.makeText(
                activity,
                "Sphere stitching unavailable on this device",
                Toast.LENGTH_LONG
            ).show()
        }
        binding.sphereCaptureOverlay.visibility = View.VISIBLE
        binding.sphereViewerOverlay.visibility = View.GONE
        clearSphereCapturePreview()
        binding.sphereCoverageText.text = SphereCoverageFormatter.format(0f, 0f)
        setLiveCameraFeedActive(true)
        if (BuildConfig.DEBUG) {
            binding.sphereDebugButton.visibility = View.VISIBLE
            updateDebugButtonUi()
        } else {
            binding.sphereDebugButton.visibility = View.GONE
        }
        Toast.makeText(activity, "Sphere mode — scan your surroundings", Toast.LENGTH_SHORT).show()
    }

    /** Use analysis frames for live view; hide CameraX preview. Layout matches stitch crop (center square, full width). */
    private fun setLiveCameraFeedActive(active: Boolean) {
        binding.viewFinder.visibility = if (active) View.INVISIBLE else View.VISIBLE
        if (active) {
            layoutSquareCapturePreview()
            syncSpherePhotoCaptureButton()
        } else {
            binding.sphereCameraFeed.setImageDrawable(null)
        }
    }

    /** Grey out sphere photo shutter while stitch worker is processing a frame. */
    fun syncSpherePhotoCaptureButton() {
        val ctrl = controller
        val capturing = ctrl?.state == SphereCaptureController.State.CAPTURING
        val stitchBusy = ctrl?.isStitchBusy() == true
        val blockPhoto = capturing && sphereCaptureIsPhotoMode && stitchBusy
        binding.recordButton.isEnabled = !blockPhoto
        binding.recordButton.alpha = if (blockPhoto) 0.45f else 1f
    }

    fun onSphereCaptureModeChanged(isPhotoMode: Boolean) {
        sphereCaptureIsPhotoMode = isPhotoMode
        syncSpherePhotoCaptureButton()
    }

    /** Square viewport sized to overlay width — same aspect as [SphereStitchFramePrep.cropCenterSquare]. */
    private fun layoutSquareCapturePreview() {
        val overlay = binding.sphereCaptureOverlay
        overlay.post {
            val side = overlay.width
            if (side <= 0) return@post
            val views = listOf(binding.sphereCameraFeed, binding.spherePreviewImage)
            for (view in views) {
                view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
                    width = side
                    height = side
                    gravity = Gravity.CENTER
                }
            }
        }
    }

    fun resetExtrinsicCalibration() {
        extrinsicCalibration.reset()
        Toast.makeText(activity, "Sphere calibration reset", Toast.LENGTH_SHORT).show()
    }

    fun onRawFrame(bitmap: Bitmap, timestampNanos: Long, imageRotationDegrees: Int = 0) {
        // Reference only — shutter capture uses the same frame via captureNextFrame in the dispatcher.
        if (!bitmap.isRecycled) {
            lastRawFrame = bitmap
            lastRawFrameTimestampNanos = timestampNanos
            lastImageRotationDegrees = imageRotationDegrees
        }
    }

    fun capturePhotoFrame() {
        val ctrl = controller ?: return
        if (ctrl.state != SphereCaptureController.State.CAPTURING) return
        val raw = lastRawFrame
        if (raw != null && !raw.isRecycled) {
            ctrl.captureFrame(raw, lastRawFrameTimestampNanos, imageRotationDegrees = lastImageRotationDegrees)
        } else {
            captureNextFrame = true
            Toast.makeText(activity, "Capturing frame…", Toast.LENGTH_SHORT).show()
        }
    }

    fun exit() {
        if (debugRecorder.isRecording) {
            stopDebugRecording(runOfflineReplay = false)
        }
        val ctrl = controller
        if (ctrl != null && isBackgroundExportActive()) {
            dismissSphereUiKeepSession()
            Toast.makeText(
                activity,
                "Finishing 360° photo in background…",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        cancelPendingBackgroundSave()
        invalidateSphereUiCallbacks()
        setLiveCameraFeedActive(false)
        controller?.release()
        controller = null
        orientationTracker.stop()
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        clearSphereCapturePreview()
        binding.recordButton.isEnabled = true
        binding.recordButton.alpha = 1f
        setVisionMode(VisionMode.NONE)
    }

    fun cleanupOnDestroy() {
        cancelPendingBackgroundSave()
        orientationTracker.stop()
        controller?.release(awaitWorkerMs = DESTROY_RELEASE_AWAIT_MS)
        controller = null
        debugExecutor.shutdown()
        if (!debugExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            debugExecutor.shutdownNow()
        }
        saveExecutor.shutdown()
        if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            saveExecutor.shutdownNow()
        }
    }

    fun onSphereCleanupFromModeSwitch() {
        setLiveCameraFeedActive(false)
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        if (isBackgroundExportActive()) {
            Log.i(TAG, "Keeping sphere session alive for background export")
            return
        }
        controller?.release()
        controller = null
        orientationTracker.stop()
    }

    private fun isBackgroundExportActive(): Boolean {
        val ctrl = controller ?: return pendingBackgroundSave
        return pendingBackgroundSave || ctrl.isFinalizationInProgress()
    }

    /** S42: notify user if a background SAVE was interrupted by process death. */
    fun checkInterruptedExport() {
        if (SphereExportPersistence.consumeInterruptedExport(activity)) {
            Toast.makeText(
                activity,
                "Previous 360° export was interrupted — capture and save again",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shouldRetainSphereSessionForBackgroundWork(): Boolean = isBackgroundExportActive()

    private fun cancelPendingBackgroundSave() {
        pendingBackgroundSave = false
        SphereExportPersistence.clear(activity)
        exportNotificationHelper.dismiss()
    }

    fun handleRecordButton(isPhotoMode: Boolean, onRecordIconChange: (Int) -> Unit) {
        val ctrl = controller ?: return
        if (ctrl.state != SphereCaptureController.State.CAPTURING) return
        sphereCaptureIsPhotoMode = isPhotoMode
        if (isPhotoMode) {
            if (ctrl.isStitchBusy()) return
            capturePhotoFrame()
            syncSpherePhotoCaptureButton()
        } else if (ctrl.isContinuousCapturing) {
            ctrl.stopContinuousCapture()
            onRecordIconChange(ab.inque.visiontechai.R.drawable.ic_record)
            Toast.makeText(activity, "Continuous capture stopped", Toast.LENGTH_SHORT).show()
        } else {
            ctrl.startContinuousCapture()
            onRecordIconChange(ab.inque.visiontechai.R.drawable.ic_video)
            Toast.makeText(activity, "Continuous capture started — pan slowly", Toast.LENGTH_SHORT).show()
        }
    }

    @Volatile
    private var pendingBackgroundSave = false

    fun toggleImuViewerMode() {
        if (binding.sphereViewerOverlay.visibility != View.VISIBLE) return
        wireViewerOrientation()
        val viewer = binding.sphereViewerView
        viewer.setImuModeEnabled(!viewer.isImuModeEnabled)
        updateImuViewerButton()
        Toast.makeText(
            activity,
            if (viewer.isImuModeEnabled) {
                "IMU view — move device or drag to pan"
            } else {
                "Touch-only view"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun wireViewerOrientation() {
        binding.sphereViewerView.setImuOrientationSupplier {
            extrinsicCalibration.applyToImu(orientationTracker.getQuaternion())
        }
    }

    private fun updateImuViewerButton() {
        val enabled = binding.sphereViewerView.isImuModeEnabled
        binding.sphereImuButton.isSelected = enabled
        binding.sphereImuButton.alpha = if (enabled) 1f else 0.75f
    }

    fun finishCapture() {
        val ctrl = controller
        if (ctrl == null || !ctrl.hasCapturedFrames) {
            Toast.makeText(activity, "Capture some frames first", Toast.LENGTH_SHORT).show()
            return
        }
        val immediatePreview = ctrl.lastPreviewBitmap
            ?.takeIf { !it.isRecycled }
            ?.copy(Bitmap.Config.ARGB_8888, false)
        if (!ctrl.finishCapture()) {
            immediatePreview?.recycle()
            return
        }
        if (immediatePreview != null) {
            showSphereViewer(immediatePreview)
            immediatePreview.recycle()
        }
        SphereStitchTelemetry.logSummary("capture")
        Toast.makeText(
            activity,
            "Finalizing 360° panorama — quality will improve",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showSphereViewer(equirect: Bitmap) {
        if (equirect.isRecycled) return
        setLiveCameraFeedActive(false)
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.VISIBLE
        wireViewerOrientation()
        binding.sphereViewerView.setImuModeEnabled(false)
        updateImuViewerButton()
        binding.sphereViewerView.setEquirectBitmap(equirect)
        binding.sphereViewerView.setLookDirection(0f, 0f, 90f)
        binding.sphereSaveButton.isEnabled = false
        binding.sphereSaveButton.text = "Finalizing…"
    }

    private fun updateSphereViewerEquirect(equirect: Bitmap) {
        if (equirect.isRecycled) {
            Toast.makeText(activity, "Could not load panorama", Toast.LENGTH_SHORT).show()
            return
        }
        if (binding.sphereViewerOverlay.visibility != View.VISIBLE) {
            showSphereViewer(equirect)
        } else {
            binding.sphereViewerView.setEquirectBitmap(equirect)
        }
    }

    /** Resume live capture after DONE preview to append more frames. */
    fun addMoreFrames() {
        val ctrl = controller ?: return
        if (isBackgroundExportActive()) {
            Toast.makeText(activity, "Wait for 360° export to finish", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ctrl.resumeCapture()) return
        showCaptureOverlay()
        binding.sphereCoverageText.text = SphereCoverageFormatter.format(
            ctrl.lastCoverage,
            ctrl.lastSolidAngleCoverage
        )
        ctrl.lastPreviewBitmap?.let { preview ->
            SpherePreviewBinder.apply(binding.spherePreviewImage, preview)
        }
        Toast.makeText(activity, "Add more frames to your sphere", Toast.LENGTH_SHORT).show()
    }

    fun cancelCapture() {
        if (isBackgroundExportActive()) {
            Toast.makeText(activity, "Wait for 360° export to finish", Toast.LENGTH_SHORT).show()
            return
        }
        val ctrl = controller ?: return
        if (!ctrl.restartCapture(sessionFovH, sessionFovV, sessionDistortion)) return
        showCaptureOverlay()
        Toast.makeText(activity, "Sphere capture reset", Toast.LENGTH_SHORT).show()
    }

    /**
     * Close the viewer (e.g. after save) and reset so the user can scan again.
     */
    fun closeViewerAndReset() {
        if (isBackgroundExportActive()) {
            Toast.makeText(activity, "Wait for 360° export to finish", Toast.LENGTH_SHORT).show()
            return
        }
        val ctrl = controller
        if (ctrl != null) {
            if (!ctrl.restartCapture(sessionFovH, sessionFovV, sessionDistortion)) return
            showCaptureOverlay()
        } else {
            exit()
        }
    }

    private fun showCaptureOverlay() {
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        binding.sphereCaptureOverlay.visibility = View.VISIBLE
        setLiveCameraFeedActive(true)
        binding.sphereCoverageText.text = SphereCoverageFormatter.format(0f, 0f)
        clearSphereCapturePreview()
    }

    private fun clearSphereCapturePreview() {
        binding.spherePreviewImage.setImageDrawable(null)
    }

    fun saveEquirectangular() {
        val ctrl = controller
        if (ctrl == null || !ctrl.hasCapturedFrames) {
            Toast.makeText(activity, "No sphere data to save", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ctrl.isPreviewSession()) {
            Toast.makeText(activity, "Tap Done before saving your 360° photo", Toast.LENGTH_SHORT).show()
            return
        }
        if (ctrl.isFinalizationInProgress()) {
            Toast.makeText(
                activity,
                "Still finalizing your 360° panorama…",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        saveExecutor.execute {
            val equirect = ctrl.ensureFullResExportBitmap()
            activity.runOnUiThread {
                if (activity.isDestroyed) {
                    equirect?.recycle()
                    return@runOnUiThread
                }
                if (equirect != null) {
                    attemptSaveEquirectangular(ctrl, equirect)
                } else {
                    Toast.makeText(
                        activity,
                        "Could not finish 360° photo — try again in a moment",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun deliverBackgroundSaveResult(result: ab.inque.visiontechai.SphereFinishResult) {
        activity.runOnUiThread {
            if (activity.isDestroyed) {
                cancelPendingBackgroundSave()
                releaseControllerIfIdle()
                return@runOnUiThread
            }
            if (!pendingBackgroundSave) {
                releaseControllerIfIdle()
                return@runOnUiThread
            }
            cancelPendingBackgroundSave()
            val ctrl = controller
            val bmp = result.equirect
            if (bmp != null && ctrl != null) {
                attemptSaveEquirectangular(ctrl, bmp)
            } else {
                exportNotificationHelper.showExportComplete(success = false)
                Toast.makeText(
                    activity,
                    "Could not finish 360° photo",
                    Toast.LENGTH_SHORT
                ).show()
            }
            SphereStitchTelemetry.logSummary("export")
            releaseControllerIfIdle()
        }
    }

    private fun attemptSaveEquirectangular(ctrl: SphereCaptureController, bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            exportNotificationHelper.showExportComplete(success = false)
            Toast.makeText(activity, "Could not finish 360° photo", Toast.LENGTH_SHORT).show()
            return
        }
        if (bitmap.width < ctrl.exportWidth || bitmap.height < ctrl.exportHeight) {
            Log.w(
                TAG,
                "Rejecting save at ${bitmap.width}x${bitmap.height} — expected ${ctrl.exportWidth}x${ctrl.exportHeight}"
            )
            exportNotificationHelper.showExportComplete(success = false)
            Toast.makeText(
                activity,
                "Full-resolution export not ready — wait for finalizing to finish",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val preview = ctrl.getPreviewBitmap()
        if (
            preview != null &&
            !preview.isRecycled &&
            SphereRefinePolicy.enforceSaveQualityGate()
        ) {
            val gate = SphereExportQualityGate.evaluate(preview, bitmap)
            if (!gate.passed) {
                if (SphereRefinePolicy.allowSaveDespiteQualityFail()) {
                    AlertDialog.Builder(activity)
                        .setTitle("Export quality check")
                        .setMessage(
                            "The full-resolution export may have visible seams or misalignment.\n\n" +
                                gate.summary +
                                "\n\nSave anyway?"
                        )
                        .setPositiveButton("Save anyway") { _, _ ->
                            saveEquirectangularBitmapAsync(bitmap)
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            exportNotificationHelper.showExportComplete(success = false)
                            Toast.makeText(
                                activity,
                                "360° photo not saved",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .show()
                } else {
                    exportNotificationHelper.showExportComplete(success = false)
                    Toast.makeText(
                        activity,
                        "360° export failed quality check — not saved",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w(TAG, "Save rejected: ${gate.summary}")
                }
                return
            }
        }
        saveEquirectangularBitmapAsync(bitmap)
    }

    private fun saveEquirectangularBitmapAsync(bitmap: Bitmap) {
        if (activity.isDestroyed || bitmap.isRecycled) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        saveExecutor.execute {
            val success = try {
                saveBitmapToMediaStore(copy)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save sphere: ${e.message}")
                false
            } finally {
                copy.recycle()
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                exportNotificationHelper.showExportComplete(success = success)
                if (success) {
                    Toast.makeText(activity, "360° photo saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Failed to save sphere photo", Toast.LENGTH_SHORT).show()
                }
                if (binding.sphereViewerOverlay.visibility == View.VISIBLE) {
                    binding.sphereSaveButton.text = "SAVE"
                }
            }
        }
    }

    /** Hide sphere UI but keep [controller] alive for background export. */
    private fun dismissSphereUiKeepSession() {
        invalidateSphereUiCallbacks()
        setLiveCameraFeedActive(false)
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        clearSphereCapturePreview()
        setVisionMode(VisionMode.NONE)
    }

    private fun releaseControllerIfIdle() {
        val ctrl = controller ?: return
        if (!ctrl.isFinalizationInProgress()) {
            ctrl.release()
            controller = null
            orientationTracker.stop()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val name = "PrismAI_SPHERE_$timestamp.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Constants.GALLERY_IMAGE_RELATIVE_PATH)
            }
        }
        val jpegBytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegBytes)
        val xmpData = SphereXmpMetadata.buildGpanoXmp(bitmap.width, bitmap.height).toByteArray(Charsets.UTF_8)
        val finalData = injectXmpIntoJpeg(jpegBytes.toByteArray(), xmpData)
        val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Log.e(TAG, "MediaStore insert returned null")
            return false
        }
        activity.contentResolver.openOutputStream(uri)?.use { it.write(finalData) }
            ?: run {
                Log.e(TAG, "MediaStore openOutputStream returned null for $uri")
                return false
            }
        SavedMediaTracker.addImage(activity, uri)
        activity.sendBroadcast(
            android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
        )
        Log.d(TAG, "Sphere photo saved: $uri")
        return true
    }

    private fun injectXmpIntoJpeg(jpegData: ByteArray, xmpData: ByteArray): ByteArray {
        val namespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val payloadLen = namespace.size + xmpData.size
        val segmentLen = payloadLen + 2
        val result = ByteArrayOutputStream(jpegData.size + segmentLen + 4)
        result.write(jpegData, 0, 2)
        result.write(0xFF)
        result.write(0xE1)
        result.write((segmentLen shr 8) and 0xFF)
        result.write(segmentLen and 0xFF)
        result.write(namespace)
        result.write(xmpData)
        result.write(jpegData, 2, jpegData.size - 2)
        return result.toByteArray()
    }

    /** Toggle debug frame + IMU recording (debug builds). */
    fun toggleDebugRecording() {
        if (!BuildConfig.DEBUG) return
        if (debugRecorder.isRecording) {
            stopDebugRecording(runOfflineReplay = true)
        } else {
            startDebugRecording()
        }
    }

    /** Replay the most recent saved debug session offline and show quality metrics. */
    fun replayLastDebugSession() {
        if (!BuildConfig.DEBUG || debugReplayRunning) return
        val sessions = debugRecorder.listSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(activity, "No debug sessions to replay", Toast.LENGTH_SHORT).show()
            return
        }
        runOfflineReplay(sessions.first())
    }

    private fun startDebugRecording() {
        val dist = sessionDistortion
        debugRecorder.startSession(
            fovH = sessionFovH,
            fovV = sessionFovV,
            distortionK1 = dist.k1,
            distortionK2 = dist.k2,
            distortionP1 = dist.p1,
            distortionP2 = dist.p2,
            extrinsicOffset = extrinsicCalibration.getOffsetQuaternion(),
            eqW = SphereCaptureController.EQ_W,
            eqH = SphereCaptureController.EQ_H,
            imageRotationDegrees = lastImageRotationDegrees.takeIf { it != 0 }
                ?: CameraFrameOrientation.inferRotationDegrees(sessionFovH, sessionFovV, null)
        )
        updateDebugButtonUi()
        Toast.makeText(
            activity,
            "Debug recording — capture frames, then tap STOP",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopDebugRecording(runOfflineReplay: Boolean) {
        val dir = debugRecorder.endSession()
        updateDebugButtonUi()
        if (dir == null) return
        Toast.makeText(
            activity,
            "Debug session saved (${dir.name})",
            Toast.LENGTH_SHORT
        ).show()
        if (runOfflineReplay) {
            runOfflineReplay(dir)
        }
    }

    private fun runOfflineReplay(sessionDir: java.io.File) {
        if (debugReplayRunning) return
        debugReplayRunning = true
        binding.sphereDebugButton.isEnabled = false
        Toast.makeText(activity, "Offline replay started…", Toast.LENGTH_SHORT).show()
        debugExecutor.execute {
            val result = try {
                SphereDebugSessionReplayer.replay(sessionDir)
            } catch (e: Exception) {
                Log.e(TAG, "Offline replay failed: ${e.message}", e)
                null
            }
            activity.runOnUiThread {
                debugReplayRunning = false
                binding.sphereDebugButton.isEnabled = true
                if (!isBackgroundExportActive()) {
                    controller?.restartCapture(sessionFovH, sessionFovV, sessionDistortion)
                }
                if (result == null) {
                    Toast.makeText(activity, "Offline replay failed", Toast.LENGTH_LONG).show()
                } else {
                    showQualityReportDialog(result.report, sessionDir)
                }
            }
        }
    }

    private fun showQualityReportDialog(
        report: ab.inque.visiontechai.sphere.debug.SphereDebugQualityReport,
        sessionDir: java.io.File
    ) {
        val passesGhost = report.finishGhostDelta <=
            ab.inque.visiontechai.SphereRefinePolicy.maxFinishExportGhostDelta()
        val passesBand = report.finishOverlapBandCorrelation >=
            ab.inque.visiontechai.SphereRefinePolicy.minFinishOverlapBandCorrelation()
        val body = buildString {
            appendLine("Session: ${report.sessionId}")
            appendLine("Frames: ${report.frameCount}")
            appendLine("Fast ghost: ${"%.3f".format(report.fastGhostScore)}")
            appendLine("Finish ghost Δ: ${"%.3f".format(report.finishGhostDelta)} ${if (passesGhost) "✓" else "✗"}")
            appendLine(
                "Overlap band corr: ${"%.3f".format(report.finishOverlapBandCorrelation)} " +
                    if (passesBand) "✓" else "✗"
            )
            appendLine("Finish sharpness: ${"%.1f".format(report.finishSharpness)}")
            appendLine(
                "SSD global: ${"%.0f".format(report.ssdBeforeGlobal)} → " +
                    "${"%.0f".format(report.ssdAfterGlobal)}"
            )
            appendLine("Replay: ${report.replayDurationMs}ms")
            appendLine()
            append(sessionDir.absolutePath)
        }
        AlertDialog.Builder(activity)
            .setTitle("Offline stitch quality")
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        Log.i(TAG, "Debug quality: ${report.summaryLine()}")
    }

    private fun updateDebugButtonUi() {
        if (!BuildConfig.DEBUG) return
        val recording = debugRecorder.isRecording
        binding.sphereDebugButton.text = if (recording) "STOP" else "REC"
        binding.sphereDebugButton.strokeColor = ColorStateList.valueOf(
            if (recording) 0xFFFF1744.toInt() else 0xFFFF5252.toInt()
        )
    }

    companion object {
        private const val TAG = "SphereSession"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val DESTROY_RELEASE_AWAIT_MS = 10_000L
    }
}
