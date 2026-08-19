package ab.inque.robotics.vision.detection

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.exp
import kotlin.math.min

data class YoloCropInfo(
    val cropRect: Rect,
    val scaleFactor: Float,
    val modelInputSize: Int
)

/** Parses YOLO11 tensor output into [DetectionResult]s and applies NMS. */
object Yolo11OutputParser {
    private const val TAG = "Yolo11OutputParser"
    private const val NUM_DETECTIONS = 8400
    private const val BBOX_SIZE = 4
    private const val NMS_IOU_THRESHOLD = 0.5f

    fun centerCropInfo(width: Int, height: Int, modelInputSize: Int = 640): YoloCropInfo {
        val shortestSide = min(width, height)
        val xOffset = (width - shortestSide) / 2
        val yOffset = (height - shortestSide) / 2
        val cropRect = Rect(xOffset, yOffset, xOffset + shortestSide, yOffset + shortestSide)
        val scaleFactor = modelInputSize.toFloat() / shortestSide
        return YoloCropInfo(cropRect, scaleFactor, modelInputSize)
    }

    fun parse(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ): List<DetectionResult> {
        if (outputArray.any { it.isNaN() }) {
            Log.w(TAG, "Model output contains NaN values")
            return emptyList()
        }

        val outputSize = outputArray.size
        return when (outputSize) {
            1 * 84 * 8400 -> processStandardFormat(outputArray, cropInfo, classLabels, threshold)
            1 * 8400 * 84 -> processTransposedFormat(outputArray, cropInfo, classLabels, threshold)
            8400 * 84 -> processNoBatchFormat(outputArray, cropInfo, classLabels, threshold)
            1 * 85 * 8400 -> processAlternativeFormat(outputArray, cropInfo, classLabels, threshold)
            else -> {
                Log.e(TAG, "Unknown YOLO11n output format. Size: $outputSize")
                emptyList()
            }
        }
    }

    private fun processStandardFormat(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ): List<DetectionResult> {
        return applyNMS(
            collectDetections(outputArray, cropInfo, classLabels, threshold, recordStride = 4 + classLabels.size)
        )
    }

    private fun processTransposedFormat(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ): List<DetectionResult> {
        return applyNMS(
            collectDetections(outputArray, cropInfo, classLabels, threshold, recordStride = 4 + classLabels.size)
        )
    }

    private fun processNoBatchFormat(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ): List<DetectionResult> {
        return applyNMS(
            collectDetections(outputArray, cropInfo, classLabels, threshold, recordStride = 4 + classLabels.size)
        )
    }

    private fun processAlternativeFormat(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numClasses = classLabels.size

        for (detectionIndex in 0 until NUM_DETECTIONS) {
            val baseIndex = detectionIndex * (BBOX_SIZE + numClasses + 1)
            if (baseIndex + BBOX_SIZE + numClasses >= outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]
            val objectness = outputArray[baseIndex + BBOX_SIZE]

            var maxConfidence = 0f
            var bestClassId = 0
            for (classIndex in 0 until numClasses) {
                val confidence = outputArray[baseIndex + BBOX_SIZE + 1 + classIndex]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            val finalConfidence = objectness * maxConfidence
            addIfValid(
                detections, x, y, w, h, finalConfidence, bestClassId, cropInfo, classLabels, threshold
            )
        }
        return applyNMS(detections)
    }

    private fun collectDetections(
        outputArray: FloatArray,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float,
        recordStride: Int
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numClasses = classLabels.size

        for (detectionIndex in 0 until NUM_DETECTIONS) {
            val baseIndex = detectionIndex * recordStride
            if (baseIndex + BBOX_SIZE + numClasses > outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]

            var maxConfidence = 0f
            var bestClassId = 0
            for (classIndex in 0 until numClasses) {
                val rawConfidence = outputArray[baseIndex + BBOX_SIZE + classIndex]
                val confidence = 1.0f / (1.0f + exp(-rawConfidence))
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            addIfValid(
                detections, x, y, w, h, maxConfidence, bestClassId, cropInfo, classLabels, threshold
            )
        }
        return detections
    }

    private fun addIfValid(
        detections: MutableList<DetectionResult>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        confidence: Float,
        classId: Int,
        cropInfo: YoloCropInfo,
        classLabels: List<String>,
        threshold: Float
    ) {
        if (confidence <= threshold) return
        if (x < 0f || y < 0f || w <= 0f || h <= 0f) return
        if (x + w > 1.0f || y + h > 1.0f) return
        if (w > 0.8f || h > 0.8f || w < 0.01f || h < 0.01f) return

        val label = if (classId < classLabels.size) classLabels[classId] else "Unknown"
        val cropLeft = cropInfo.cropRect.left
        val cropTop = cropInfo.cropRect.top
        val cropWidth = cropInfo.cropRect.width()
        val cropHeight = cropInfo.cropRect.height()

        val boundingBox = RectF(
            cropLeft + (x * cropWidth),
            cropTop + (y * cropHeight),
            cropLeft + ((x + w) * cropWidth),
            cropTop + ((y + h) * cropHeight)
        )
        detections.add(DetectionResult(boundingBox, label, confidence))
    }

    internal fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<DetectionResult>()
        val used = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (used[i]) continue
            result.add(sorted[i])
            used[i] = true
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > NMS_IOU_THRESHOLD) {
                    used[j] = true
                }
            }
        }
        return result
    }

    internal fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea =
            (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        return intersectionArea / unionArea
    }
}
