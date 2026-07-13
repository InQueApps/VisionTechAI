package ab.inque.visiontechai.controller

import ab.inque.visiontechai.BuildConfig
import ab.inque.visiontechai.PipelineResult
import ab.inque.visiontechai.ProcessedVideoRecorder
import ab.inque.visiontechai.SphereCaptureController
import ab.inque.visiontechai.SphereNative
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.VisionState
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.ui.VisionOverlayHost
import android.util.Log

/**
 * Applies [PipelineResult] to HUD overlays, filter bitmap, sphere capture, and video encoder.
 */
class PipelineResultDispatcher(
    private val visionState: VisionState,
    private val overlayHost: VisionOverlayHost,
    private val binding: ActivityMainBinding,
    private val sphereControllerProvider: () -> SphereCaptureController?,
    private val sphereCaptureNextFrame: () -> Boolean,
    private val clearSphereCaptureNextFrame: () -> Unit,
    private val isRecordingProvider: () -> Boolean,
    private val processedVideoRecorderProvider: () -> ProcessedVideoRecorder?
) {
    private var segmentationNullLogCounter = 0

    fun dispatch(result: PipelineResult) {
        result.fps?.let { overlayHost.fpsView.text = "FPS: %.1f".format(it) }
        result.histograms?.let { (r, g, b) ->
            overlayHost.histogramView.updateHistograms(r, g, b)
        }
        result.detections?.let { dets ->
            overlayHost.objectDetectionOverlay.setResults(dets, result.imageHeight, result.imageWidth)
        }

        if (visionState.mode == VisionMode.SEGMENTATION && result.processedBitmap == null) {
            if (BuildConfig.DEBUG && segmentationNullLogCounter++ % 45 == 0) {
                Log.w(TAG, "Segmentation: no bitmap from pipeline")
            }
        }

        val sphereController = sphereControllerProvider()
        if (visionState.mode == VisionMode.SPHERE && sphereController?.state == SphereCaptureController.State.CAPTURING) {
            result.rawBitmap?.let { raw ->
                val ts = result.frameTimestampNanos
                if (sphereCaptureNextFrame()) {
                    clearSphereCaptureNextFrame()
                    sphereController.captureFrame(raw, ts)
                }
                sphereController.feedContinuousFrame(raw, ts)
                val coverage = SphereNative.getCoverage()
                binding.sphereCoverageText.text = "Coverage: ${(coverage * 100).toInt()}%"
                sphereController.getPreviewBitmap()?.let { preview ->
                    binding.spherePreviewImage.setImageBitmap(preview)
                }
            }
        }

        result.processedBitmap?.let { bmp ->
            if (overlayHost.usesFilterOverlay(visionState.mode)) {
                val bringFront = visionState.mode == VisionMode.SEGMENTATION ||
                    visionState.mode == VisionMode.DEPTH_FIELD
                overlayHost.setFilterBitmap(bmp, bringToFront = bringFront)
            }
            if (isRecordingProvider()) {
                processedVideoRecorderProvider()?.addFrame(bmp)
            }
        }
    }

    companion object {
        private const val TAG = "PipelineDispatcher"
    }
}
