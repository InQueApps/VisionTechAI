package ab.inque.visiontechai

import ab.inque.robotics.vision.detection.DetectionResult


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
    val rawBitmap: Bitmap? = null,
    val detections: List<DetectionResult>?,
    val imageHeight: Int = 0,
    val imageWidth: Int = 0,
    val frameTimestampNanos: Long = 0,
    /** CameraX clockwise rotation applied to [rawBitmap] in [ImageUtils.yuvToBitmap]. */
    val imageRotationDegrees: Int = 0,
    /** Camera AE: ISO × t × boost / f². 0 when unknown. */
    val cameraEv: Float = 0f
)

/**
 * Runs image analysis: one YUV→Bitmap conversion per frame, then histogram + optional processor + optional object detection.
 * Callback is invoked on the analyzer thread; the Activity should run it on the main thread.
 */
class VisionAnalysisPipeline(
    private val getState: () -> VisionState,
    private val processors: Map<VisionMode, FrameProcessor>,
    private val detectObjects: (Bitmap) -> List<DetectionResult>,
    private val applyFlip: (Bitmap) -> Bitmap,
    private val shouldDecodeFrame: () -> Boolean = { true },
    private val exposureEvForTimestamp: (Long) -> Float = { 0f }
) {
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var lastHistograms: Triple<IntArray, IntArray, IntArray>? = null

    fun createAnalyzer(callback: (PipelineResult) -> Unit): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { image ->
            val state = getState()
            if (state.mode == VisionMode.SPHERE && !shouldDecodeFrame()) {
                image.close()
                return@Analyzer
            }
            val frameTs = image.imageInfo.timestamp
            val rotationDegrees = image.imageInfo.rotationDegrees
            val cameraEv = exposureEvForTimestamp(frameTs)
            val bitmap = ImageUtils.yuvToBitmap(image)
            if (bitmap == null) {
                image.close()
                return@Analyzer
            }
            callback(processFrame(bitmap, frameTs, rotationDegrees, cameraEv))
            image.close()
        }
    }

    /** Frame logic extracted for unit tests (no [ImageProxy] required). */
    internal fun processFrame(
        bitmap: Bitmap,
        frameTimestampNanos: Long,
        imageRotationDegrees: Int = 0,
        cameraEv: Float = 0f
    ): PipelineResult {
        val state = getState()

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= Constants.FPS_UPDATE_INTERVAL_MS) {
            currentFps = frameCount * 1000f / (now - lastFpsTime)
            frameCount = 0
            lastFpsTime = now
        }

        val histograms = if (state.mode == VisionMode.SPHERE) {
            null
        } else if (lastHistograms == null || frameCount % 2 == 0) {
            ImageUtils.calculateHistograms(bitmap).also { lastHistograms = it }
        } else {
            lastHistograms
        }

        val detections = if (state.mode == VisionMode.OBJECT_DETECTION) {
            detectObjects(bitmap)
        } else {
            null
        }

        val processor = if (state.mode != VisionMode.NONE && state.mode != VisionMode.OBJECT_DETECTION) {
            processors[state.mode]
        } else {
            null
        }
        val processedBitmap = processor?.process(bitmap, state.params)?.let { applyFlip(it) }
        val rawForSphere = if (state.mode == VisionMode.SPHERE) applyFlip(bitmap) else null

        return PipelineResult(
            fps = currentFps,
            histograms = histograms,
            processedBitmap = processedBitmap,
            rawBitmap = rawForSphere,
            detections = detections,
            imageHeight = bitmap.height,
            imageWidth = bitmap.width,
            frameTimestampNanos = frameTimestampNanos,
            imageRotationDegrees = imageRotationDegrees,
            cameraEv = cameraEv
        )
    }
}
