package ab.inque.visiontechai

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Result from one frame analysis: FPS, histograms, optional processed bitmap, optional detections.
 */
data class PipelineResult(
    val fps: Float?,
    val histograms: Triple<IntArray, IntArray, IntArray>?,
    val processedBitmap: Bitmap?,
    val detections: List<DetectionResult>?,
    val imageHeight: Int = 0,
    val imageWidth: Int = 0
)

/**
 * Runs image analysis: one YUV→Bitmap conversion per frame, then histogram + optional processor + optional object detection.
 * Callback is invoked on the analyzer thread; the Activity should run it on the main thread.
 */
class VisionAnalysisPipeline(
    private val getState: () -> VisionState,
    private val processors: Map<VisionMode, FrameProcessor>,
    private val objectDetectionProcessor: ObjectDetectionProcessor,
    private val applyFlip: (Bitmap) -> Bitmap
) {
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var lastHistograms: Triple<IntArray, IntArray, IntArray>? = null

    fun createAnalyzer(callback: (PipelineResult) -> Unit): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { image ->
            val bitmap = ImageUtils.yuvToBitmap(image)
            if (bitmap == null) {
                image.close()
                return@Analyzer
            }
            val state = getState()

            // FPS
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= Constants.FPS_UPDATE_INTERVAL_MS) {
                currentFps = frameCount * 1000f / (now - lastFpsTime)
                frameCount = 0
                lastFpsTime = now
            }

            // Histograms: compute every 2nd frame; reuse last for lower latency
            val histograms = if (lastHistograms == null || frameCount % 2 == 0) {
                ImageUtils.calculateHistograms(bitmap).also { lastHistograms = it }
            } else {
                lastHistograms
            }

            // Object detection (overlay only)
            val detections = if (state.mode == VisionMode.OBJECT_DETECTION) {
                objectDetectionProcessor.detectObjects(bitmap)
            } else null

            // Frame processor for overlay modes
            val processor = if (state.mode != VisionMode.NONE && state.mode != VisionMode.OBJECT_DETECTION) {
                processors[state.mode]
            } else null
            val processedBitmap = processor?.process(bitmap, state.params)?.let { applyFlip(it) }

            callback(
                PipelineResult(
                    fps = currentFps,
                    histograms = histograms,
                    processedBitmap = processedBitmap,
                    detections = detections,
                    imageHeight = bitmap.height,
                    imageWidth = bitmap.width
                )
            )
            image.close()
        }
    }
}
