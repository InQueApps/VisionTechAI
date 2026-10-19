package ab.inque.visiontechai.controller

import android.view.View
import android.widget.SeekBar
import ab.inque.visiontechai.Constants
import ab.inque.robotics.accumulate.FrameAccumulatorNative
import ab.inque.visiontechai.HDFrameProcessor
import ab.inque.visiontechai.OpticalFlowProcessor
import ab.inque.visiontechai.ImageSegmentationProcessor
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.VisionModeTransitions
import ab.inque.visiontechai.VisionState
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.FrameProcessor
import ab.inque.visiontechai.ui.VisionOverlayHost
import android.os.Handler
import android.os.Looper
import com.google.android.material.button.MaterialButton

/**
 * Owns vision mode transitions, toolbar highlights, and slider panel visibility.
 */
class VisionModeController(
    private val binding: ActivityMainBinding,
    private val visionState: VisionState,
    private val overlayHost: VisionOverlayHost,
    private val frameProcessors: Map<VisionMode, FrameProcessor>,
    private val opticalFlowProcessor: OpticalFlowProcessor,
    private val imageSegmentationProcessor: ImageSegmentationProcessor,
    private val onSphereCleanup: () -> Unit
) {
    fun setVisionMode(mode: VisionMode) {
        val prev = visionState.mode
        val cleanup = VisionModeTransitions.cleanupOnModeChange(prev, mode)
        if (cleanup.clearSphere) {
            onSphereCleanup()
        }
        if (cleanup.clearFrameAccumulator) {
            FrameAccumulatorNative.clear()
        }
        if (cleanup.clearHdCache) {
            (frameProcessors[VisionMode.HD] as? HDFrameProcessor)?.clearCache()
        }
        visionState.mode = mode
        updateUI()
    }

    fun toggleSlider() {
        visionState.isSliderExpanded = !visionState.isSliderExpanded
        if (visionState.isSliderExpanded) {
            binding.sliderArrow.setImageResource(ab.inque.visiontechai.R.drawable.ic_arrow_up)
            showActiveSlider()
        } else {
            binding.sliderArrow.setImageResource(ab.inque.visiontechai.R.drawable.ic_arrow_down)
            hideAllSliders()
        }
    }

    fun toggleCategoryOverlay(category: VisionMode.Category) {
        val row = when (category) {
            VisionMode.Category.NV -> return
            VisionMode.Category.IMG -> binding.imgOverlayRow
            VisionMode.Category.ROB -> binding.robOverlayRow
            VisionMode.Category.TXT, VisionMode.Category.SPHERE, VisionMode.Category.NONE -> return
        }
        val visible = row.visibility == View.VISIBLE
        binding.imgOverlayRow.visibility = View.GONE
        binding.robOverlayRow.visibility = View.GONE
        setMainButtonActive(binding.imgUnderline, false)
        setMainButtonActive(binding.robUnderline, false)
        if (visible) return
        row.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            if (row.visibility == View.VISIBLE) {
                row.visibility = View.GONE
                setMainButtonActive(binding.imgUnderline, false)
                setMainButtonActive(binding.robUnderline, false)
            }
        }, Constants.OVERLAY_AUTO_HIDE_MS)
    }

    fun updateUI() {
        val m = visionState.mode
        setMainButtonActive(binding.nvUnderline, false)
        setMainButtonActive(binding.imgUnderline, false)
        setMainButtonActive(binding.robUnderline, false)
        setMainButtonActive(binding.txtUnderline, false)
        setMainButtonActive(binding.sphereUnderline, false)
        setSubButtonActive(binding.imgEDButton, false)
        setSubButtonActive(binding.imgCOLButton, false)
        setSubButtonActive(binding.imgHDButton, false)
        setSubButtonActive(binding.robOFButton, false)
        setSubButtonActive(binding.robSEGButton, false)
        setSubButtonActive(binding.robDFButton, false)
        hideAllSliders()
        binding.langSettingsPanel.visibility = View.GONE
        binding.infoButton.visibility = View.VISIBLE
        overlayHost.hideAllEffectOverlays()
        overlayHost.clearFilterBitmap()
        binding.imgOverlayRow.visibility = View.GONE
        binding.robOverlayRow.visibility = View.GONE

        if (m.hasSlider) {
            visionState.isSliderExpanded = true
            binding.sliderArrow.setImageResource(ab.inque.visiontechai.R.drawable.ic_arrow_up)
        }

        when (m) {
            VisionMode.NONE -> { }
            VisionMode.NIGHT_VISION -> {
                setMainButtonActive(binding.nvUnderline, true)
                overlayHost.showFilterOverlay(m)
                syncNightVisionSliders()
                showActiveSlider()
            }
            VisionMode.EDGE_DETECTION -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgEDButton, true)
                overlayHost.showFilterOverlay(m)
                showActiveSlider()
            }
            VisionMode.COLOR_ADJUSTMENT -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgCOLButton, true)
                overlayHost.showFilterOverlay(m)
                showActiveSlider()
            }
            VisionMode.HD -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgHDButton, true)
                overlayHost.showFilterOverlay(m)
                syncHdSliders()
                showActiveSlider()
            }
            VisionMode.OPTICAL_FLOW -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robOFButton, true)
                overlayHost.showFilterOverlay(m)
                opticalFlowProcessor.clear()
                syncOpticalFlowSliders()
                showActiveSlider()
            }
            VisionMode.SEGMENTATION -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robSEGButton, true)
                overlayHost.showFilterOverlay(m)
                syncSegmentationSliders()
                showActiveSlider()
            }
            VisionMode.DEPTH_FIELD -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robDFButton, true)
                overlayHost.showFilterOverlay(m)
                syncDepthSliders()
                showActiveSlider()
            }
            VisionMode.OBJECT_DETECTION -> {
                setMainButtonActive(binding.robUnderline, true)
                overlayHost.showFilterOverlay(m)
            }
            VisionMode.TEXT_TRANSLATION -> {
                setMainButtonActive(binding.txtUnderline, true)
                overlayHost.showFilterOverlay(m)
                showActiveSlider()
            }
            VisionMode.SPHERE -> {
                setMainButtonActive(binding.sphereUnderline, true)
            }
        }
        overlayHost.bringHudToFront(binding.controlsContainer)
    }

    private fun showActiveSlider() {
        if (!visionState.isSliderExpanded) return
        when (visionState.mode) {
            VisionMode.NIGHT_VISION -> binding.nightVisionSliderContainer.visibility = View.VISIBLE
            VisionMode.EDGE_DETECTION -> binding.edgeDetectionSliderContainer.visibility = View.VISIBLE
            VisionMode.COLOR_ADJUSTMENT -> binding.colorSliderContainer.visibility = View.VISIBLE
            VisionMode.HD -> binding.hdSliderContainer.visibility = View.VISIBLE
            VisionMode.OPTICAL_FLOW -> binding.opticalFlowSliderContainer.visibility = View.VISIBLE
            VisionMode.SEGMENTATION -> binding.segmentationSliderContainer.visibility = View.VISIBLE
            VisionMode.DEPTH_FIELD -> binding.depthFieldSliderContainer.visibility = View.VISIBLE
            VisionMode.TEXT_TRANSLATION -> binding.translationOptionsContainer.visibility = View.VISIBLE
            else -> { }
        }
    }

    private fun hideAllSliders() {
        binding.nightVisionSliderContainer.visibility = View.GONE
        binding.edgeDetectionSliderContainer.visibility = View.GONE
        binding.colorSliderContainer.visibility = View.GONE
        binding.hdSliderContainer.visibility = View.GONE
        binding.segmentationSliderContainer.visibility = View.GONE
        binding.depthFieldSliderContainer.visibility = View.GONE
        binding.opticalFlowSliderContainer.visibility = View.GONE
        binding.translationOptionsContainer.visibility = View.GONE
    }

    private fun syncNightVisionSliders() {
        binding.nightVisionSlider.progress =
            (visionState.params.nightVisionIntensity * 100).toInt().coerceIn(0, 100)
        binding.nightVisionIntensityValueText.text =
            "${(visionState.params.nightVisionIntensity * 100).toInt()}%"
        val expFrames = visionState.params.nightVisionExposureFrames
        binding.nightVisionExposureSlider.progress = (expFrames - 1).coerceIn(0, 9)
        binding.nightVisionExposureValueText.text = "$expFrames"
    }

    private fun syncHdSliders() {
        binding.hdSlider.progress = visionState.params.hdScaleSliderValue
        val p = visionState.params.hdScaleSliderValue
        binding.hdScaleValueText.text = when {
            p < 50 -> String.format("%.2fx", 1f - (50 - p) * 0.0198f)
            p > 50 -> String.format("%.1fx", 1f + (p - 50) * 0.06f)
            else -> "1.0x"
        }
        binding.hdExposureSlider.progress = visionState.params.hdExposureFrames - 1
        binding.hdExposureValueText.text = "${visionState.params.hdExposureFrames}"
    }

    private fun syncOpticalFlowSliders() {
        binding.opticalFlowPixelationSlider.progress =
            (visionState.params.opticalFlowPixelation - 2).coerceIn(0, 14)
        binding.opticalFlowPixelationValueText.text = "${visionState.params.opticalFlowPixelation}"
        binding.opticalFlowIntensitySlider.progress =
            (visionState.params.opticalFlowIntensity * 100).toInt().coerceIn(0, 100)
        binding.opticalFlowIntensityValueText.text =
            "${(visionState.params.opticalFlowIntensity * 100).toInt()}%"
        binding.opticalFlowSensitivitySlider.progress =
            (visionState.params.opticalFlowSensitivity * 100).toInt().coerceIn(0, 100)
        binding.opticalFlowSensitivityValueText.text =
            "${(visionState.params.opticalFlowSensitivity * 100).toInt()}%"
    }

    private fun syncSegmentationSliders() {
        binding.segmentationClustersSlider.progress =
            (visionState.params.segmentationNumClusters - 3).coerceIn(0, 7)
        binding.segmentationClustersValueText.text = "${visionState.params.segmentationNumClusters}"
        binding.segmentationIterationsSlider.progress =
            (visionState.params.segmentationIterations - 3).coerceIn(0, 12)
        binding.segmentationIterationsValueText.text = "${visionState.params.segmentationIterations}"
    }

    private fun syncDepthSliders() {
        binding.depthFieldFocusSlider.progress =
            (visionState.params.depthFieldFocus * 100).toInt().coerceIn(0, 100)
        binding.depthFieldFocusValueText.text =
            "${(visionState.params.depthFieldFocus * 100).toInt()}%"
    }

    private fun setMainButtonActive(underline: View, active: Boolean) {
        underline.setBackgroundColor(
            if (active) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT
        )
    }

    private fun setSubButtonActive(btn: MaterialButton, active: Boolean) {
        btn.setTextColor(
            btn.context.getColor(
                if (active) android.R.color.white else ab.inque.visiontechai.R.color.text_secondary
            )
        )
    }
}
