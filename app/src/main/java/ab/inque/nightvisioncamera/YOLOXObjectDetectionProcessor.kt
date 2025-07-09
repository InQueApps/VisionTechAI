package ab.inque.nightvisioncamera

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class YOLOXObjectDetectionProcessor(private val context: Context) {
    companion object {
        private const val TAG = "YOLOXObjectDetectionProcessor"
        private const val MODEL_FILE = "yolox_nano.tflite"
        private const val LABELS_FILE = "coco_labels.txt"
        private const val INPUT_SIZE = 416 // YOLOX-Nano input size
        private const val CONFIDENCE_THRESHOLD = 0.05f  // Lowered even further to catch more detections
        private const val NMS_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 20
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var isInitialized = false
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        try {
            // Load labels from assets
            try {
                val inputStream = context.assets.open(LABELS_FILE)
                labels = inputStream.bufferedReader().readLines()
                inputStream.close()
            } catch (e: Exception) {
                Log.w(TAG, "Could not load labels from assets: ${e.message}")
                // Fallback to basic labels
                labels = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light")
            }

            // Load model from assets
            try {
                val modelInputStream = context.assets.open(MODEL_FILE)
                val modelBytes = modelInputStream.readBytes()
                modelInputStream.close()
                
                Log.d(TAG, "Model loaded from assets. Size: ${modelBytes.size} bytes")
                
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseXNNPACK(true) // Enable XNNPACK for better performance
                }

                interpreter = Interpreter(ByteBuffer.allocateDirect(modelBytes.size).apply {
                    put(modelBytes)
                    rewind()
                }, options)
                
                // Log model information
                val inputTensor = interpreter?.getInputTensor(0)
                val outputTensor = interpreter?.getOutputTensor(0)
                
                Log.d(TAG, "Model loaded successfully")
                Log.d(TAG, "Input tensor: ${inputTensor?.name()}, shape: ${inputTensor?.shape()?.contentToString()}")
                Log.d(TAG, "Output tensor: ${outputTensor?.name()}, shape: ${outputTensor?.shape()?.contentToString()}")
                
                isInitialized = true
                Log.d(TAG, "YOLOX detector initialized successfully from assets")
            } catch (e: Exception) {
                Log.w(TAG, "Could not load YOLOX model from assets: ${e.message}")
                e.printStackTrace()
                Log.w(TAG, "Using fallback detection")
                isInitialized = false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLOX detector: ${e.message}")
            isInitialized = false
        }
    }

    fun detectObjects(bitmap: Bitmap): List<DetectionResult> {
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "YOLOX detector not initialized, using fallback detection")
            return fallbackObjectDetection(bitmap)
        }

        try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            // Preprocess image
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)

            // Get the expected input shape from the interpreter
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            Log.d(TAG, "Expected input shape: ${inputShape?.contentToString()}")
            
            // Debug input preprocessing
            Log.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}")
            Log.d(TAG, "Processed image buffer size: ${processedImage.buffer.capacity()}")
            Log.d(TAG, "Processed image data type: ${processedImage.dataType}")
            
            // Prepare input tensor with correct shape
            val inputBuffer = TensorBuffer.createFixedSize(inputShape ?: intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3), org.tensorflow.lite.DataType.FLOAT32)
            
            // Convert the processed image to float array
            val floatArray = FloatArray(processedImage.buffer.capacity() / 4)
            processedImage.buffer.asFloatBuffer().get(floatArray)
            
            // Ensure the array size matches the expected input size
            val expectedSize = inputShape?.reduce { acc, i -> acc * i } ?: (1 * INPUT_SIZE * INPUT_SIZE * 3)
            if (floatArray.size != expectedSize) {
                Log.w(TAG, "Array size mismatch. Expected: $expectedSize, Got: ${floatArray.size}")
                // Resize the array to match expected size
                val resizedArray = FloatArray(expectedSize)
                val copySize = minOf(floatArray.size, expectedSize)
                System.arraycopy(floatArray, 0, resizedArray, 0, copySize)
                inputBuffer.loadArray(resizedArray)
            } else {
                inputBuffer.loadArray(floatArray)
            }

            // Get the expected output shape
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.d(TAG, "Expected output shape: ${outputShape?.contentToString()}")
            
            // Prepare output tensor
            val outputBuffer = TensorBuffer.createFixedSize(outputShape ?: intArrayOf(1, MAX_DETECTIONS, 6), org.tensorflow.lite.DataType.FLOAT32)

            // Run inference
            interpreter?.run(inputBuffer.buffer, outputBuffer.buffer)

            // Log raw output for debugging
            val rawOutput = outputBuffer.floatArray
            Log.d(TAG, "Raw model output size: ${rawOutput.size}")
            Log.d(TAG, "First 20 output values: ${rawOutput.take(20).joinToString(", ")}")
            Log.d(TAG, "Output min: ${rawOutput.minOrNull()}, max: ${rawOutput.maxOrNull()}, mean: ${rawOutput.average()}")
            
            // Log all non-zero values to see what the model is actually outputting
            val nonZeroValues = rawOutput.filter { it != 0f }
            Log.d(TAG, "Non-zero output values (${nonZeroValues.size}): ${nonZeroValues.take(50).joinToString(", ")}")

            // Process results
            val detections = processYOLOXOutput(outputBuffer.floatArray, originalWidth, originalHeight)
            
            Log.d(TAG, "YOLOX detection completed: ${detections.size} objects found")
            return detections

        } catch (e: Exception) {
            Log.e(TAG, "YOLOX detection failed: ${e.message}")
            e.printStackTrace()
            return fallbackObjectDetection(bitmap)
        }
    }

    private fun processYOLOXOutput(outputArray: FloatArray, originalWidth: Int, originalHeight: Int): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val candidates = mutableListOf<Triple<RectF, Float, Int>>()

        Log.d(TAG, "Processing YOLOX output. Array size: ${outputArray.size}")
        Log.d(TAG, "Original image size: ${originalWidth}x${originalHeight}")

        // Process each detection
        for (i in 0 until MAX_DETECTIONS) {
            val baseIndex = i * 6
            if (baseIndex + 5 >= outputArray.size) break

            val x = outputArray[baseIndex]
            val y = outputArray[baseIndex + 1]
            val w = outputArray[baseIndex + 2]
            val h = outputArray[baseIndex + 3]
            val confidence = outputArray[baseIndex + 4]
            val classId = outputArray[baseIndex + 5].toInt()

            // Log first few detections for debugging
            if (i < 3) {
                Log.d(TAG, "Detection $i: x=$x, y=$y, w=$w, h=$h, conf=$confidence, class=$classId")
            }
            
            // Log all detections with confidence > 0.01 to see what's being detected
            if (confidence > 0.01f) {
                val label = if (classId < labels.size) labels[classId] else "Unknown"
                Log.d(TAG, "Raw detection $i: $label (class=$classId) at ($x,$y) size ($w,$h) conf=$confidence")
            }

            if (confidence > CONFIDENCE_THRESHOLD && classId < labels.size) {
                // Convert normalized coordinates to pixel coordinates
                val left = x * originalWidth
                val top = y * originalHeight
                val right = (x + w) * originalWidth
                val bottom = (y + h) * originalHeight

                val boundingBox = RectF(left, top, right, bottom)
                candidates.add(Triple(boundingBox, confidence, classId))
                
                Log.d(TAG, "Added candidate: ${labels[classId]} with confidence $confidence")
            } else if (confidence > 0.01f) { // Log low confidence detections too
                val label = if (classId < labels.size) labels[classId] else "Unknown"
                Log.d(TAG, "Filtered out: $label (class=$classId, conf=$confidence, threshold=$CONFIDENCE_THRESHOLD)")
            }
        }

        Log.d(TAG, "Total candidates before NMS: ${candidates.size}")

        // Apply Non-Maximum Suppression
        val nmsResults = applyNMS(candidates)

        Log.d(TAG, "Total detections after NMS: ${nmsResults.size}")

        // Convert to DetectionResult
        for ((boundingBox, confidence, classId) in nmsResults) {
            val label = if (classId < labels.size) labels[classId] else "Unknown"
            detections.add(DetectionResult(boundingBox, label, confidence))
            Log.d(TAG, "Final detection: $label at ${boundingBox.left},${boundingBox.top},${boundingBox.right},${boundingBox.bottom} with confidence $confidence")
        }

        return detections
    }

    private fun applyNMS(candidates: List<Triple<RectF, Float, Int>>): List<Triple<RectF, Float, Int>> {
        if (candidates.isEmpty()) return emptyList()

        // Sort by confidence
        val sorted = candidates.sortedByDescending { it.second }
        val result = mutableListOf<Triple<RectF, Float, Int>>()
        val used = BooleanArray(sorted.size) { false }

        for (i in sorted.indices) {
            if (used[i]) continue

            result.add(sorted[i])
            used[i] = true

            // Check overlap with remaining candidates
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue

                val iou = calculateIoU(sorted[i].first, sorted[j].first)
                if (iou > NMS_THRESHOLD) {
                    used[j] = true
                }
            }
        }

        return result
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return intersectionArea / unionArea
    }

    /**
     * Fallback object detection using simple computer vision techniques
     */
    private fun fallbackObjectDetection(bitmap: Bitmap): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Simple motion/object detection using edge detection and contour analysis
            val edgeDetectedBitmap = detectEdges(bitmap)
            val contours = findContours(edgeDetectedBitmap)
            
            for (contour in contours) {
                if (contour.area() > 1000) { // Filter out small contours
                    val boundingBox = contour.boundingRect()
                    val confidence = calculateConfidence(contour, bitmap)
                    
                    if (confidence > 0.3f) {
                        detections.add(DetectionResult(
                            RectF(boundingBox),
                            "Object",
                            confidence
                        ))
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Fallback detection failed: ${e.message}")
        }
        
        return detections
    }

    private fun detectEdges(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centerPixel = pixels[y * width + x]
                val centerGray = (Color.red(centerPixel) + Color.green(centerPixel) + Color.blue(centerPixel)) / 3
                
                // Simple Sobel edge detection
                var gx = 0
                var gy = 0
                
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighborPixel = pixels[(y + dy) * width + (x + dx)]
                        val neighborGray = (Color.red(neighborPixel) + Color.green(neighborPixel) + Color.blue(neighborPixel)) / 3
                        
                        if (dx == 0 && dy == 0) continue
                        
                        gx += neighborGray * dx
                        gy += neighborGray * dy
                    }
                }
                
                val magnitude = kotlin.math.sqrt((gx * gx + gy * gy).toDouble()).toInt()
                val edgeValue = magnitude.coerceIn(0, 255)
                
                pixels[y * width + x] = Color.rgb(edgeValue, edgeValue, edgeValue)
            }
        }
        
        outputBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outputBitmap
    }

    private fun findContours(bitmap: Bitmap): List<Contour> {
        val contours = mutableListOf<Contour>()
        val width = bitmap.width
        val height = bitmap.height
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val visited = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!visited[y][x] && isEdgePixel(pixels[y * width + x])) {
                    val contour = traceContour(pixels, visited, x, y, width, height)
                    if (contour.points.size > 10) { // Minimum contour size
                        contours.add(contour)
                    }
                }
            }
        }
        
        return contours
    }

    private fun isEdgePixel(pixel: Int): Boolean {
        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
        return gray > 128
    }

    private fun traceContour(pixels: IntArray, visited: Array<BooleanArray>, startX: Int, startY: Int, width: Int, height: Int): Contour {
        val points = mutableListOf<Point>()
        val stack = mutableListOf<Point>()
        stack.add(Point(startX, startY))
        
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            val x = current.x
            val y = current.y
            
            if (x < 0 || x >= width || y < 0 || y >= height || visited[y][x]) continue
            
            visited[y][x] = true
            points.add(current)
            
            // Add neighbors
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx >= 0 && nx < width && ny >= 0 && ny < height && 
                        !visited[ny][nx] && isEdgePixel(pixels[ny * width + nx])) {
                        stack.add(Point(nx, ny))
                    }
                }
            }
        }
        
        return Contour(points)
    }

    private fun calculateConfidence(contour: Contour, bitmap: Bitmap): Float {
        val area = contour.area()
        val perimeter = contour.perimeter()
        
        // Simple confidence based on contour properties
        val compactness = if (perimeter > 0) (4 * Math.PI * area) / (perimeter * perimeter) else 0.0
        val sizeConfidence = (area / 10000.0).coerceAtMost(1.0)
        
        return (compactness * sizeConfidence).toFloat()
    }

    data class Point(val x: Int, val y: Int)
    
    data class Contour(val points: List<Point>) {
        fun area(): Int = points.size
        
        fun perimeter(): Double {
            var perimeter = 0.0
            for (i in points.indices) {
                val current = points[i]
                val next = points[(i + 1) % points.size]
                val dx = next.x - current.x
                val dy = next.y - current.y
                perimeter += kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            }
            return perimeter
        }
        
        fun boundingRect(): Rect {
            if (points.isEmpty()) return Rect(0, 0, 0, 0)
            
            var minX = points[0].x
            var maxX = points[0].x
            var minY = points[0].y
            var maxY = points[0].y
            
            for (point in points) {
                minX = minOf(minX, point.x)
                maxX = maxOf(maxX, point.x)
                minY = minOf(minY, point.y)
                maxY = maxOf(maxY, point.y)
            }
            
            return Rect(minX, minY, maxX, maxY)
        }
    }
} 