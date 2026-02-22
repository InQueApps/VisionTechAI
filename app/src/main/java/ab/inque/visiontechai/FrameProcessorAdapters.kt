package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Night vision: native adaptive low-light enhancement with aligned multi-frame exposure stacking. */
class NightVisionFrameProcessor : FrameProcessor {
    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            val frames = params.nightVisionExposureFrames
            val source = if (frames > 1) {
                FrameAccumulatorNative.accumulate(input, frames, align = true) ?: input
            } else {
                input
            }
            NightVisionNative.enhance(source, params.nightVisionIntensity)
                ?: ImageUtils.histogramEqualizationRGB(source, params.nightVisionIntensity)
        } catch (e: Exception) {
            Log.e("NightVisionFP", "Failed: ${e.message}")
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
            processor.processOpticalFlow(input, params)
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

/**
 * HD: accumulation runs every frame (synchronous, fast C++), while the
 * expensive bicubic upscale runs asynchronously on a dedicated thread.
 *
 * When the upscaler is idle it grabs the latest accumulated snapshot and
 * starts processing.  Until the first upscale completes, process() returns
 * the raw accumulated bitmap so the feed is never blank.
 */
class HDFrameProcessor(private val hdImageProcessor: HDImageProcessor) : FrameProcessor {

    private val upscaleExecutor = Executors.newSingleThreadExecutor()
    private val upscaleBusy = AtomicBoolean(false)
    @Volatile private var latestUpscaled: Bitmap? = null

    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        return try {
            val frames = params.hdExposureFrames
            val accumulated = if (frames > 1) {
                FrameAccumulatorNative.accumulate(input, frames, align = true) ?: input
            } else {
                input
            }

            val scaleValue = params.hdScaleSliderValue
            if (scaleValue == 50) {
                latestUpscaled = null
                return accumulated
            }

            if (upscaleBusy.compareAndSet(false, true)) {
                val snapshot = accumulated.copy(
                    accumulated.config ?: Bitmap.Config.ARGB_8888, false
                )
                upscaleExecutor.submit {
                    try {
                        val result = hdImageProcessor.processImage(snapshot, scaleValue)
                        latestUpscaled = result
                        if (result !== snapshot) snapshot.recycle()
                    } catch (e: Exception) {
                        Log.e("HDFrameProcessor", "Async upscale failed: ${e.message}")
                    } finally {
                        upscaleBusy.set(false)
                    }
                }
            }

            latestUpscaled ?: accumulated
        } catch (e: Exception) {
            Log.e("HDFrameProcessor", "Failed: ${e.message}")
            null
        }
    }

    fun clearCache() {
        latestUpscaled = null
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

