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

        private fun debugLog(message: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, message)
        }
    }

    // YOLO11n components
    private var yolo11nInterpreter: Interpreter? = null
    private var isYolo11nInitialized = false
    private var classLabels: List<String> = emptyList()
    private val yolo11nImageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(YOLO11N_INPUT_SIZE, YOLO11N_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        debugLog( "Initializing ObjectDetectionProcessor...")
        initializeYOLO11n()
        loadClassLabels()
        debugLog( "ObjectDetectionProcessor initialization complete. Ready: ${isYolo11nInitialized && yolo11nInterpreter != null}")
    }

    private fun loadClassLabels() {
        try {
            debugLog( "Loading class labels from $CLASS_LABELS_FILE...")
            val inputStream = context.assets.open(CLASS_LABELS_FILE)
            classLabels = inputStream.bufferedReader().readLines()
            inputStream.close()
            debugLog( "Successfully loaded ${classLabels.size} class labels")
            
            // Log first few labels for verification
            val previewLabels = classLabels.take(5).joinToString(", ")
            debugLog( "Class labels preview: $previewLabels...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load class labels from $CLASS_LABELS_FILE: ${e.message}")
            classLabels = emptyList()
            // Do not throw: allow app to run without labels (object detection will still work but show empty labels)
        }
    }

    private fun initializeYOLO11n() {
        try {
            debugLog( "Loading YOLO11n model from $YOLO11N_MODEL_FILE...")
            
            // Check if model file exists
            val modelInputStream = context.assets.open(YOLO11N_MODEL_FILE)
            val modelBytes = modelInputStream.readBytes()
            modelInputStream.close()
            
            debugLog( "Model file loaded successfully. Size: ${modelBytes.size} bytes")
            
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(true)
            }

            debugLog( "Creating TensorFlow Lite interpreter with $numThreads threads...")
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()
            
            yolo11nInterpreter = Interpreter(modelBuffer, options)
            
            isYolo11nInitialized = true
            debugLog( "YOLO11n detector initialized successfully")
            
            // Log model input/output information
            val inputTensor = yolo11nInterpreter?.getInputTensor(0)
            val outputTensor = yolo11nInterpreter?.getOutputTensor(0)
            
            debugLog( "Model input shape: ${inputTensor?.shape()?.contentToString()}")
            debugLog( "Model output shape: ${outputTensor?.shape()?.contentToString()}")
            debugLog( "Model input data type: ${inputTensor?.dataType()}")
            debugLog( "Model output data type: ${outputTensor?.dataType()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLO11n detector: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            isYolo11nInitialized = false
            yolo11nInterpreter = null
            // Do not throw: allow app to run without object detection (e.g. if yolo11n_float16.tflite is missing from assets)
        }
    }

    fun detectObjects(bitmap: Bitmap, imageRotation: Int = 0): List<DetectionResult> {
        if (!isReady()) {
            Log.e(TAG, "YOLO11n model is not ready for detection")
            return emptyList()
        }

        debugLog( "Starting object detection...")
        debugLog( "Input image size: ${bitmap.width}x${bitmap.height}")
        debugLog( "Image rotation: $imageRotation degrees")
        debugLog( "Confidence threshold: $threshold")

        try {
            val inferenceTime = SystemClock.uptimeMillis()
            val cropInfo = Yolo11OutputParser.centerCropInfo(bitmap.width, bitmap.height, YOLO11N_INPUT_SIZE)

            debugLog( "Crop info - Crop rect: ${cropInfo.cropRect}, Scale factor: ${cropInfo.scaleFactor}")

            // Extract the crop region
            val croppedBitmap = Bitmap.createBitmap(
                bitmap, 
                cropInfo.cropRect.left, 
                cropInfo.cropRect.top, 
                cropInfo.cropRect.width(), 
                cropInfo.cropRect.height()
            )

            debugLog( "Cropped image size: ${croppedBitmap.width}x${croppedBitmap.height}")

            // Scale the cropped square to 640x640 for YOLO11n input
            val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(croppedBitmap)
            val processedImage = yolo11nImageProcessor.process(tensorImage)

            debugLog( "Scaled image size: ${processedImage.width}x${processedImage.height} (target: ${YOLO11N_INPUT_SIZE}x${YOLO11N_INPUT_SIZE})")

            // Get the expected input shape from the interpreter
            val inputShape = yolo11nInterpreter?.getInputTensor(0)?.shape()
            debugLog( "Expected input shape: ${inputShape?.contentToString()}")
            
            // Prepare input tensor
            val inputBuffer = TensorBuffer.createFixedSize(
                inputShape ?: intArrayOf(1, YOLO11N_INPUT_SIZE, YOLO11N_INPUT_SIZE, INPUT_CHANNELS), 
                org.tensorflow.lite.DataType.FLOAT32
            )
            
            // Convert the processed image to float array
            val floatArray = FloatArray(processedImage.buffer.capacity() / 4)
            processedImage.buffer.asFloatBuffer().get(floatArray)
            
            debugLog( "Raw float array size: ${floatArray.size}")
            debugLog( "Raw float array sample values: ${floatArray.take(10).joinToString(", ")}")
            
            // Normalize pixel values for YOLO11n (0-255 to 0-1)
            val normalizedArray = FloatArray(floatArray.size)
            for (i in floatArray.indices) {
                normalizedArray[i] = floatArray[i] / 255.0f
            }
            
            debugLog( "Normalized array sample values: ${normalizedArray.take(10).joinToString(", ")}")
            debugLog( "Input array min: ${normalizedArray.minOrNull()}, max: ${normalizedArray.maxOrNull()}")
            
            // Ensure the array size matches the expected input size
            val expectedSize = inputShape?.reduce { acc, i -> acc * i } ?: (1 * YOLO11N_INPUT_SIZE * YOLO11N_INPUT_SIZE * INPUT_CHANNELS)
            debugLog( "Expected input size: $expectedSize, Actual size: ${normalizedArray.size}")
            
            if (normalizedArray.size != expectedSize) {
                Log.w(TAG, "Array size mismatch. Expected: $expectedSize, Got: ${normalizedArray.size}")
                val resizedArray = FloatArray(expectedSize)
                val copySize = minOf(normalizedArray.size, expectedSize)
                System.arraycopy(normalizedArray, 0, resizedArray, 0, copySize)
                inputBuffer.loadArray(resizedArray)
                debugLog( "Resized array to match expected input size")
            } else {
                inputBuffer.loadArray(normalizedArray)
                debugLog( "Input array loaded successfully")
            }

            // Get the expected output shape
            val outputShape = yolo11nInterpreter?.getOutputTensor(0)?.shape()
            debugLog( "Expected output shape: ${outputShape?.contentToString()}")
            
            // Prepare output tensor
            val outputBuffer = TensorBuffer.createFixedSize(
                outputShape ?: intArrayOf(1, 84, 8400), // YOLO11n typical output format
                org.tensorflow.lite.DataType.FLOAT32
            )

            debugLog( "Running YOLO11n inference...")
            // Run inference
            yolo11nInterpreter?.run(inputBuffer.buffer, outputBuffer.buffer)
            debugLog( "YOLO11n inference completed")
            
            // Debug output array
            val outputArray = outputBuffer.floatArray
            debugLog( "Output array size: ${outputArray.size}")
            debugLog( "Output array min: ${outputArray.minOrNull()}, max: ${outputArray.maxOrNull()}")
            debugLog( "Output array sample values: ${outputArray.take(20).joinToString(", ")}")

            // Process results
            val detections = Yolo11OutputParser.parse(
                outputBuffer.floatArray,
                cropInfo,
                classLabels,
                threshold
            )
            
            val finalInferenceTime = SystemClock.uptimeMillis() - inferenceTime
            debugLog( "YOLO11n detection completed in ${finalInferenceTime}ms: ${detections.size} objects found")
            
            // Log detection details
            detections.forEachIndexed { index, detection ->
                debugLog( "Detection $index: ${detection.label} (${detection.confidence}) at ${detection.boundingBox}")
            }
            
            return detections
            
        } catch (e: Exception) {
            Log.e(TAG, "YOLO11n detection failed: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    // Utility methods
    fun isReady(): Boolean {
        val ready = isYolo11nInitialized && yolo11nInterpreter != null
        debugLog( "Model ready check: $ready (YOLO11n initialized: $isYolo11nInitialized, Interpreter: ${yolo11nInterpreter != null})")
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
        debugLog( "Clearing YOLO11n interpreter...")
        yolo11nInterpreter = null
        isYolo11nInitialized = false
        debugLog( "YOLO11n interpreter cleared")
    }
} 