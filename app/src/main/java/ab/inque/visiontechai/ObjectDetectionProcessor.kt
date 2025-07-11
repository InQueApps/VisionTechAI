package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import kotlin.math.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)

class ObjectDetectionProcessor(
    private val context: Context,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val numThreads: Int = 2,
    private val maxResults: Int = 10
) {
    companion object {
        private const val TAG = "ObjectDetectionProcessor"
        private const val YOLO11N_MODEL_FILE = "yolo11n_float16.tflite"
        private const val CLASS_LABELS_FILE = "class.txt"
        private const val YOLO11N_INPUT_SIZE = 640
        private const val INPUT_CHANNELS = 3
        private const val DEFAULT_THRESHOLD = 0.5f  // Lower threshold for debugging
    }

    // YOLO11n components
    private var yolo11nInterpreter: Interpreter? = null
    private var isYolo11nInitialized = false
    private var classLabels: List<String> = emptyList()
    private val yolo11nImageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(YOLO11N_INPUT_SIZE, YOLO11N_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        Log.d(TAG, "Initializing ObjectDetectionProcessor...")
        initializeYOLO11n()
        loadClassLabels()
        Log.d(TAG, "ObjectDetectionProcessor initialization complete. Ready: ${isYolo11nInitialized && yolo11nInterpreter != null}")
    }

    data class CropInfo(
        val cropRect: Rect,
        val scaleFactor: Float,
        val modelInputSize: Int
    )

    private fun loadClassLabels() {
        try {
            Log.d(TAG, "Loading class labels from $CLASS_LABELS_FILE...")
            val inputStream = context.assets.open(CLASS_LABELS_FILE)
            classLabels = inputStream.bufferedReader().readLines()
            inputStream.close()
            Log.d(TAG, "Successfully loaded ${classLabels.size} class labels")
            
            // Log first few labels for verification
            val previewLabels = classLabels.take(5).joinToString(", ")
            Log.d(TAG, "Class labels preview: $previewLabels...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load class labels from $CLASS_LABELS_FILE: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            throw RuntimeException("Failed to load class labels", e)
        }
    }

    private fun initializeYOLO11n() {
        try {
            Log.d(TAG, "Loading YOLO11n model from $YOLO11N_MODEL_FILE...")
            
            // Check if model file exists
            val modelInputStream = context.assets.open(YOLO11N_MODEL_FILE)
            val modelBytes = modelInputStream.readBytes()
            modelInputStream.close()
            
            Log.d(TAG, "Model file loaded successfully. Size: ${modelBytes.size} bytes")
            
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(true)
            }

            Log.d(TAG, "Creating TensorFlow Lite interpreter with $numThreads threads...")
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()
            
            yolo11nInterpreter = Interpreter(modelBuffer, options)
            
            isYolo11nInitialized = true
            Log.d(TAG, "YOLO11n detector initialized successfully")
            
            // Log model input/output information
            val inputTensor = yolo11nInterpreter?.getInputTensor(0)
            val outputTensor = yolo11nInterpreter?.getOutputTensor(0)
            
            Log.d(TAG, "Model input shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "Model output shape: ${outputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "Model input data type: ${inputTensor?.dataType()}")
            Log.d(TAG, "Model output data type: ${outputTensor?.dataType()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLO11n detector: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            isYolo11nInitialized = false
            throw RuntimeException("Failed to initialize YOLO11n model", e)
        }
    }

    fun detectObjects(bitmap: Bitmap, imageRotation: Int = 0): List<DetectionResult> {
        if (!isReady()) {
            Log.e(TAG, "YOLO11n model is not ready for detection")
            return emptyList()
        }

        Log.d(TAG, "Starting object detection...")
        Log.d(TAG, "Input image size: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "Image rotation: $imageRotation degrees")
        Log.d(TAG, "Confidence threshold: $threshold")

        try {
            val inferenceTime = SystemClock.uptimeMillis()
            val cropInfo = calculateCropInfo(bitmap)

            Log.d(TAG, "Crop info - Crop rect: ${cropInfo.cropRect}, Scale factor: ${cropInfo.scaleFactor}")

            // Extract the crop region
            val croppedBitmap = Bitmap.createBitmap(
                bitmap, 
                cropInfo.cropRect.left, 
                cropInfo.cropRect.top, 
                cropInfo.cropRect.width(), 
                cropInfo.cropRect.height()
            )

            Log.d(TAG, "Cropped image size: ${croppedBitmap.width}x${croppedBitmap.height}")

            // Scale the cropped square to 640x640 for YOLO11n input
            val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(croppedBitmap)
            val processedImage = yolo11nImageProcessor.process(tensorImage)

            Log.d(TAG, "Scaled image size: ${processedImage.width}x${processedImage.height} (target: ${YOLO11N_INPUT_SIZE}x${YOLO11N_INPUT_SIZE})")

            // Get the expected input shape from the interpreter
            val inputShape = yolo11nInterpreter?.getInputTensor(0)?.shape()
            Log.d(TAG, "Expected input shape: ${inputShape?.contentToString()}")
            
            // Prepare input tensor
            val inputBuffer = TensorBuffer.createFixedSize(
                inputShape ?: intArrayOf(1, YOLO11N_INPUT_SIZE, YOLO11N_INPUT_SIZE, INPUT_CHANNELS), 
                org.tensorflow.lite.DataType.FLOAT32
            )
            
            // Convert the processed image to float array
            val floatArray = FloatArray(processedImage.buffer.capacity() / 4)
            processedImage.buffer.asFloatBuffer().get(floatArray)
            
            Log.d(TAG, "Raw float array size: ${floatArray.size}")
            Log.d(TAG, "Raw float array sample values: ${floatArray.take(10).joinToString(", ")}")
            
            // Normalize pixel values for YOLO11n (0-255 to 0-1)
            val normalizedArray = FloatArray(floatArray.size)
            for (i in floatArray.indices) {
                normalizedArray[i] = floatArray[i] / 255.0f
            }
            
            Log.d(TAG, "Normalized array sample values: ${normalizedArray.take(10).joinToString(", ")}")
            Log.d(TAG, "Input array min: ${normalizedArray.minOrNull()}, max: ${normalizedArray.maxOrNull()}")
            
            // Ensure the array size matches the expected input size
            val expectedSize = inputShape?.reduce { acc, i -> acc * i } ?: (1 * YOLO11N_INPUT_SIZE * YOLO11N_INPUT_SIZE * INPUT_CHANNELS)
            Log.d(TAG, "Expected input size: $expectedSize, Actual size: ${normalizedArray.size}")
            
            if (normalizedArray.size != expectedSize) {
                Log.w(TAG, "Array size mismatch. Expected: $expectedSize, Got: ${normalizedArray.size}")
                val resizedArray = FloatArray(expectedSize)
                val copySize = minOf(normalizedArray.size, expectedSize)
                System.arraycopy(normalizedArray, 0, resizedArray, 0, copySize)
                inputBuffer.loadArray(resizedArray)
                Log.d(TAG, "Resized array to match expected input size")
            } else {
                inputBuffer.loadArray(normalizedArray)
                Log.d(TAG, "Input array loaded successfully")
            }

            // Get the expected output shape
            val outputShape = yolo11nInterpreter?.getOutputTensor(0)?.shape()
            Log.d(TAG, "Expected output shape: ${outputShape?.contentToString()}")
            
            // Prepare output tensor
            val outputBuffer = TensorBuffer.createFixedSize(
                outputShape ?: intArrayOf(1, 84, 8400), // YOLO11n typical output format
                org.tensorflow.lite.DataType.FLOAT32
            )

            Log.d(TAG, "Running YOLO11n inference...")
            // Run inference
            yolo11nInterpreter?.run(inputBuffer.buffer, outputBuffer.buffer)
            Log.d(TAG, "YOLO11n inference completed")
            
            // Debug output array
            val outputArray = outputBuffer.floatArray
            Log.d(TAG, "Output array size: ${outputArray.size}")
            Log.d(TAG, "Output array min: ${outputArray.minOrNull()}, max: ${outputArray.maxOrNull()}")
            Log.d(TAG, "Output array sample values: ${outputArray.take(20).joinToString(", ")}")

            // Process results
            val detections = processYOLO11nOutput(outputBuffer.floatArray, cropInfo)
            
            val finalInferenceTime = SystemClock.uptimeMillis() - inferenceTime
            Log.d(TAG, "YOLO11n detection completed in ${finalInferenceTime}ms: ${detections.size} objects found")
            
            // Log detection details
            detections.forEachIndexed { index, detection ->
                Log.d(TAG, "Detection $index: ${detection.label} (${detection.confidence}) at ${detection.boundingBox}")
            }
            
            return detections
            
        } catch (e: Exception) {
            Log.e(TAG, "YOLO11n detection failed: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    private fun calculateCropInfo(bitmap: Bitmap): CropInfo {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        Log.d(TAG, "Original image size: ${originalWidth}x${originalHeight}")
        
        // Find the shortest side and crop to a square with that dimension
        val shortestSide = min(originalWidth, originalHeight)
        Log.d(TAG, "Shortest side: $shortestSide")
        
        // Calculate the crop rectangle (center crop)
        val xOffset = (originalWidth - shortestSide) / 2
        val yOffset = (originalHeight - shortestSide) / 2
        val cropRect = Rect(xOffset, yOffset, xOffset + shortestSide, yOffset + shortestSide)
        
        Log.d(TAG, "Crop rectangle: $cropRect (${cropRect.width()}x${cropRect.height()})")
        
        // The scale factor from cropped square to 640x640
        val scaleFactor = YOLO11N_INPUT_SIZE.toFloat() / shortestSide
        Log.d(TAG, "Scale factor to 640x640: $scaleFactor")
        
        return CropInfo(cropRect, scaleFactor, YOLO11N_INPUT_SIZE)
    }

    private fun processYOLO11nOutput(outputArray: FloatArray, cropInfo: CropInfo): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        
        // Check for NaN values in the output
        val hasNaN = outputArray.any { it.isNaN() }
        if (hasNaN) {
            Log.w(TAG, "Model output contains NaN values")
            return emptyList()
        }

        // YOLO11n output format analysis - try different formats
        val outputSize = outputArray.size
        Log.d(TAG, "YOLO11n output size: $outputSize")
        Log.d(TAG, "Output array sample values: ${outputArray.take(20).joinToString(", ")}")
        
        // Try different output formats
        when {
            // Format: [1, 84, 8400] - typical YOLO11n format
            outputSize == 1 * 84 * 8400 -> {
                Log.d(TAG, "Processing YOLO11n standard format [1, 84, 8400]")
                return processYOLO11nStandardFormat(outputArray, cropInfo)
            }
            // Format: [1, 8400, 84] - transposed format
            outputSize == 1 * 8400 * 84 -> {
                Log.d(TAG, "Processing YOLO11n transposed format [1, 8400, 84]")
                return processYOLO11nTransposedFormat(outputArray, cropInfo)
            }
            // Format: [8400, 84] - no batch dimension
            outputSize == 8400 * 84 -> {
                Log.d(TAG, "Processing YOLO11n no-batch format [8400, 84]")
                return processYOLO11nNoBatchFormat(outputArray, cropInfo)
            }
            // Format: [1, 85, 8400] - alternative format with objectness
            outputSize == 1 * 85 * 8400 -> {
                Log.d(TAG, "Processing YOLO11n alternative format [1, 85, 8400]")
                return processYOLO11nAlternativeFormat(outputArray, cropInfo)
            }
            else -> {
                Log.e(TAG, "Unknown YOLO11n output format. Size: $outputSize")
                Log.e(TAG, "Expected sizes: ${1 * 84 * 8400}, ${1 * 8400 * 84}, ${8400 * 84}, ${1 * 85 * 8400}")
            return emptyList()
            }
        }
    }

    private fun processYOLO11nStandardFormat(outputArray: FloatArray, cropInfo: CropInfo): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numDetections = 8400
        val numClasses = classLabels.size
        val bboxSize = 4
        
        Log.d(TAG, "Processing standard format with $numDetections detections, $numClasses classes")
        
        for (detectionIndex in 0 until numDetections) {
            val baseIndex = detectionIndex * (bboxSize + numClasses)

            if (baseIndex + bboxSize + numClasses >= outputArray.size) break

            // Extract bounding box coordinates (normalized 0-1)
            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]

            // Find the class with highest confidence
            var maxConfidence = 0f
            var bestClassId = 0
            
            for (classIndex in 0 until numClasses) {
                val rawConfidence = outputArray[baseIndex + bboxSize + classIndex]
                // Apply sigmoid activation to get proper probability
                val confidence = 1.0f / (1.0f + exp(-rawConfidence))
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            // Apply filtering criteria
            if (maxConfidence > threshold && 
                x >= 0f && y >= 0f && w > 0f && h > 0f && 
                x + w <= 1.0f && y + h <= 1.0f &&
                w <= 0.8f && h <= 0.8f && // Filter out very large boxes
                w >= 0.01f && h >= 0.01f) { // Filter out very small boxes
                
                    val label = getClassLabel(bestClassId)
                    
                // Convert normalized coordinates to absolute coordinates
                    val cropLeft = cropInfo.cropRect.left
                    val cropTop = cropInfo.cropRect.top
                    val cropWidth = cropInfo.cropRect.width()
                    val cropHeight = cropInfo.cropRect.height()
                    
                    val absoluteLeft = cropLeft + (x * cropWidth)
                    val absoluteTop = cropTop + (y * cropHeight)
                    val absoluteRight = cropLeft + ((x + w) * cropWidth)
                    val absoluteBottom = cropTop + ((y + h) * cropHeight)

                    val boundingBox = RectF(absoluteLeft, absoluteTop, absoluteRight, absoluteBottom)
                    
                    detections.add(DetectionResult(boundingBox, label, maxConfidence))
                
                Log.d(TAG, "Found detection: $label (${maxConfidence}) at ($x, $y, $w, $h)")
            }
        }

        Log.d(TAG, "Standard format processing complete. Raw detections: ${detections.size}")
        return applyNMS(detections)
    }

    private fun processYOLO11nTransposedFormat(outputArray: FloatArray, cropInfo: CropInfo): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numDetections = 8400
        val numClasses = classLabels.size
        val bboxSize = 4
        
        Log.d(TAG, "Processing transposed format with $numDetections detections, $numClasses classes")
        
        for (detectionIndex in 0 until numDetections) {
            val baseIndex = detectionIndex * (bboxSize + numClasses)
            
            if (baseIndex + bboxSize + numClasses >= outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]

            var maxConfidence = 0f
            var bestClassId = 0
            
            for (classIndex in 0 until numClasses) {
                val rawConfidence = outputArray[baseIndex + bboxSize + classIndex]
                // Apply sigmoid activation to get proper probability
                val confidence = 1.0f / (1.0f + exp(-rawConfidence))
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            if (maxConfidence > threshold && 
                x >= 0f && y >= 0f && w > 0f && h > 0f && 
                x + w <= 1.0f && y + h <= 1.0f &&
                w <= 0.8f && h <= 0.8f && w >= 0.01f && h >= 0.01f) {
                
                val label = getClassLabel(bestClassId)
                val cropLeft = cropInfo.cropRect.left
                val cropTop = cropInfo.cropRect.top
                val cropWidth = cropInfo.cropRect.width()
                val cropHeight = cropInfo.cropRect.height()
                
                val absoluteLeft = cropLeft + (x * cropWidth)
                val absoluteTop = cropTop + (y * cropHeight)
                val absoluteRight = cropLeft + ((x + w) * cropWidth)
                val absoluteBottom = cropTop + ((y + h) * cropHeight)

                val boundingBox = RectF(absoluteLeft, absoluteTop, absoluteRight, absoluteBottom)
                detections.add(DetectionResult(boundingBox, label, maxConfidence))
                
                Log.d(TAG, "Found detection: $label (${maxConfidence}) at ($x, $y, $w, $h)")
            }
        }
        
        Log.d(TAG, "Transposed format processing complete. Raw detections: ${detections.size}")
        return applyNMS(detections)
    }

    private fun processYOLO11nNoBatchFormat(outputArray: FloatArray, cropInfo: CropInfo): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numDetections = 8400
        val numClasses = classLabels.size
        val bboxSize = 4
        
        Log.d(TAG, "Processing no-batch format with $numDetections detections, $numClasses classes")
        
        for (detectionIndex in 0 until numDetections) {
            val baseIndex = detectionIndex * (bboxSize + numClasses)
            
            if (baseIndex + bboxSize + numClasses >= outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]

            var maxConfidence = 0f
            var bestClassId = 0
            
            for (classIndex in 0 until numClasses) {
                val rawConfidence = outputArray[baseIndex + bboxSize + classIndex]
                // Apply sigmoid activation to get proper probability
                val confidence = 1.0f / (1.0f + exp(-rawConfidence))
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            if (maxConfidence > threshold && 
                x >= 0f && y >= 0f && w > 0f && h > 0f && 
                x + w <= 1.0f && y + h <= 1.0f &&
                w <= 0.8f && h <= 0.8f && w >= 0.01f && h >= 0.01f) {
                
                val label = getClassLabel(bestClassId)
                val cropLeft = cropInfo.cropRect.left
                val cropTop = cropInfo.cropRect.top
                val cropWidth = cropInfo.cropRect.width()
                val cropHeight = cropInfo.cropRect.height()
                
                val absoluteLeft = cropLeft + (x * cropWidth)
                val absoluteTop = cropTop + (y * cropHeight)
                val absoluteRight = cropLeft + ((x + w) * cropWidth)
                val absoluteBottom = cropTop + ((y + h) * cropHeight)

                val boundingBox = RectF(absoluteLeft, absoluteTop, absoluteRight, absoluteBottom)
                detections.add(DetectionResult(boundingBox, label, maxConfidence))
                
                Log.d(TAG, "Found detection: $label (${maxConfidence}) at ($x, $y, $w, $h)")
            }
        }
        
        Log.d(TAG, "No-batch format processing complete. Raw detections: ${detections.size}")
        return applyNMS(detections)
    }

    private fun processYOLO11nAlternativeFormat(outputArray: FloatArray, cropInfo: CropInfo): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numDetections = 8400
        val numClasses = classLabels.size
        val bboxSize = 4
        
        Log.d(TAG, "Processing alternative format with $numDetections detections, $numClasses classes")
        
        for (detectionIndex in 0 until numDetections) {
            val baseIndex = detectionIndex * (bboxSize + numClasses + 1) // +1 for objectness score
            
            if (baseIndex + bboxSize + numClasses >= outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]
            val objectness = outputArray[baseIndex + bboxSize]

            var maxConfidence = 0f
            var bestClassId = 0
            
            for (classIndex in 0 until numClasses) {
                val confidence = outputArray[baseIndex + bboxSize + 1 + classIndex]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    bestClassId = classIndex
                }
            }

            val finalConfidence = objectness * maxConfidence

            if (finalConfidence > threshold && 
                x >= 0f && y >= 0f && w > 0f && h > 0f && 
                x + w <= 1.0f && y + h <= 1.0f &&
                w <= 0.8f && h <= 0.8f && w >= 0.01f && h >= 0.01f) {
                
                val label = getClassLabel(bestClassId)
                val cropLeft = cropInfo.cropRect.left
                val cropTop = cropInfo.cropRect.top
                val cropWidth = cropInfo.cropRect.width()
                val cropHeight = cropInfo.cropRect.height()
                
                val absoluteLeft = cropLeft + (x * cropWidth)
                val absoluteTop = cropTop + (y * cropHeight)
                val absoluteRight = cropLeft + ((x + w) * cropWidth)
                val absoluteBottom = cropTop + ((y + h) * cropHeight)

                val boundingBox = RectF(absoluteLeft, absoluteTop, absoluteRight, absoluteBottom)
                detections.add(DetectionResult(boundingBox, label, finalConfidence))
                
                Log.d(TAG, "Found detection: $label (${finalConfidence}) at ($x, $y, $w, $h)")
            }
        }
        
        Log.d(TAG, "Alternative format processing complete. Raw detections: ${detections.size}")
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        Log.d(TAG, "Applying NMS to ${detections.size} detections")

        // Sort by confidence (highest first)
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<DetectionResult>()
        val used = BooleanArray(sorted.size) { false }

        for (i in sorted.indices) {
            if (used[i]) continue

            result.add(sorted[i])
            used[i] = true

            // Check overlap with remaining candidates
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue

                val iou = calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox)
                if (iou > 0.5f) { // NMS threshold
                    used[j] = true
                }
            }
        }

        Log.d(TAG, "NMS complete. Final detections: ${result.size}")
        return result
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return intersectionArea / unionArea
    }

    private fun getClassLabel(classId: Int): String {
        return if (classId < classLabels.size) classLabels[classId] else "Unknown"
    }

    // Utility methods
    fun isReady(): Boolean {
        val ready = isYolo11nInitialized && yolo11nInterpreter != null
        Log.d(TAG, "Model ready check: $ready (YOLO11n initialized: $isYolo11nInitialized, Interpreter: ${yolo11nInterpreter != null})")
        return ready
    }

    fun getModelInfo(): String {
        val inputTensor = yolo11nInterpreter?.getInputTensor(0)
        val outputTensor = yolo11nInterpreter?.getOutputTensor(0)
        
        return """
            YOLO11n Model Info:
            - Initialized: $isYolo11nInitialized
            - Interpreter: ${yolo11nInterpreter != null}
            - Input shape: ${inputTensor?.shape()?.contentToString()}
            - Output shape: ${outputTensor?.shape()?.contentToString()}
            - Class labels loaded: ${classLabels.size}
            - Threshold: $threshold
        """.trimIndent()
    }

    fun clear() {
        Log.d(TAG, "Clearing YOLO11n interpreter...")
        yolo11nInterpreter = null
        isYolo11nInitialized = false
        Log.d(TAG, "YOLO11n interpreter cleared")
    }
} 