package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log

/** FrameProcessor for regular (histogram equalization) night vision. */
class NightVisionFrameProcessor : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            ImageUtils.histogramEqualizationRGB(input, params.nightVisionIntensity)
        } catch (e: Exception) {
            Log.e("NightVisionFP", "Failed: ${e.message}")
            null
        }
    }
}

/** Adapter for ML Night Vision. */
class MLNightVisionFrameProcessor(private val processor: MLNightVisionProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            processor.processMLNightVision(input, params.mlNightVisionSensitivity)
        } catch (e: Exception) {
            Log.e("MLNightVisionFP", "Failed: ${e.message}")
            null
        }
    }
}

/** Adapter for Edge Detection. */
class EdgeDetectionFrameProcessor(private val processor: EdgeDetectionProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            processor.detectEdges(input, params.edgeDetectionSensitivity)
        } catch (e: Exception) {
            Log.e("EdgeDetectionFP", "Failed: ${e.message}")
            null
        }
    }
}

/** Adapter for Optical Flow. */
class OpticalFlowFrameProcessor(private val processor: OpticalFlowProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            processor.processOpticalFlow(input)
        } catch (e: Exception) {
            Log.e("OpticalFlowFP", "Failed: ${e.message}")
            null
        }
    }
}

/** FrameProcessor for color adjustment (uses ImageUtils). */
class ColorAdjustmentFrameProcessor : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            ImageUtils.applyColorAdjustment(input, params)
        } catch (e: Exception) {
            Log.e("ColorAdjustmentFP", "Failed: ${e.message}")
            null
        }
    }
}

/** Adapter for Image Segmentation. */
class SegmentationFrameProcessor(private val processor: ImageSegmentationProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            processor.segmentImage(input, params)
        } catch (e: Exception) {
            Log.e("SegmentationFP", "Failed: ${e.message}")
            null
        }
    }
}

/** FrameProcessor for HD scale (upsample/downsample only). Used only when HD mode is active. */
class HDFrameProcessor(private val hdImageProcessor: HDImageProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            hdImageProcessor.processImage(input, params.hdScaleSliderValue)
        } catch (e: Exception) {
            Log.e("HDFrameProcessor", "Failed: ${e.message}")
            null
        }
    }
}

/** Adapter for Depth Field (MiDaS depth as greyscale). Shows placeholder if model not loaded. Focus slider brightens the selected depth band. */
class DepthFieldFrameProcessor(private val processor: DepthEstimationProcessor) : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            val raw = processor.processDepthField(input, params) ?: processor.createPlaceholderBitmap(input.width, input.height)
            processor.applyDepthFieldParams(raw, params)
        } catch (e: Exception) {
            Log.e("DepthFieldFP", "Failed: ${e.message}")
            processor.createPlaceholderBitmap(input.width, input.height)
        }
    }
}

