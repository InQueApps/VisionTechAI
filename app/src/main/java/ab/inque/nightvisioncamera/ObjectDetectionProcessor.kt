package ab.inque.nightvisioncamera

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.sqrt
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.nio.ByteBuffer

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)

class ObjectDetectionProcessor(private val context: Context) {
    companion object {
        private const val TAG = "ObjectDetectionProcessor"
        private const val MODEL_FILE = "ssd_mobilenet_v1_1_metadata_1.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val MAX_RESULTS = 10
    }

    private var objectDetector: ObjectDetector? = null
    private var isInitialized = false

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setNumThreads(4)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()

            objectDetector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            isInitialized = true
            Log.d(TAG, "Object detector initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize object detector: ${e.message}")
            isInitialized = false
        }
    }

    fun detectObjects(bitmap: Bitmap): List<DetectionResult> {
        if (!isInitialized || objectDetector == null) {
            Log.w(TAG, "Object detector not initialized, using fallback detection")
            return fallbackObjectDetection(bitmap)
        }

        try {
            // Preprocess the image
            val processedImage = preprocessImage(bitmap)
            
            // Run detection
            val detector = objectDetector!!
            val results = detector.detect(processedImage)
            
            // Convert results to our format
            val detections = mutableListOf<DetectionResult>()
            for (detection in results) {
                val boundingBox = detection.boundingBox
                val category = detection.categories.firstOrNull()
                val label = category?.label ?: "Unknown"
                val confidence = category?.score ?: 0f
                
                detections.add(DetectionResult(boundingBox, label, confidence))
            }
            
            return detections
            
        } catch (e: Exception) {
            Log.e(TAG, "Object detection failed: ${e.message}")
            return fallbackObjectDetection(bitmap)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        val tensorImage = TensorImage.fromBitmap(bitmap)
        return imageProcessor.process(tensorImage)
    }

    /**
     * Fallback object detection using simple computer vision techniques
     * This provides basic detection when TensorFlow model is not available
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
                
                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
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
                perimeter += sqrt((dx * dx + dy * dy).toDouble())
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