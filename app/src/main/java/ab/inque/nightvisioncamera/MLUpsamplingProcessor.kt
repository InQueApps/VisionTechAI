package ab.inque.nightvisioncamera

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MLUpsamplingProcessor(private val context: Context) {
    companion object {
        private const val TAG = "MLUpsamplingProcessor"
        private const val MODEL_FILE = "sr_model.tflite" // We'll create a lightweight SR model
        private const val INPUT_SIZE = 64 // Small input size for performance
        private const val SCALE_FACTOR = 2 // 2x upsampling
        private const val OUTPUT_SIZE = INPUT_SIZE * SCALE_FACTOR
    }

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        initializeModel()
    }

    private fun initializeModel() {
        try {
            // Load the actual super-resolution model
            Log.d(TAG, "Loading ML super-resolution model from assets")
            
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
            
            Log.d(TAG, "ML model loaded successfully")
            Log.d(TAG, "Input tensor: ${inputTensor?.name()}, shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "Output tensor: ${outputTensor?.name()}, shape: ${outputTensor?.shape()?.contentToString()}")
            
            isInitialized = true
            Log.d(TAG, "ML super-resolution model initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ML upsampling model: ${e.message}")
            e.printStackTrace()
            isInitialized = false
        }
    }

    fun upsampleImage(bitmap: Bitmap, scale: Float): Bitmap {
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "ML model not initialized, using fallback upsampling")
            return fallbackUpsampling(bitmap, scale)
        }

        try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            
            // Calculate target size based on scale
            val targetWidth = (originalWidth * scale).toInt()
            val targetHeight = (originalHeight * scale).toInt()
            
            Log.d(TAG, "ML upsampling from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight}")
            
            // Use the actual ML model for upsampling
            return processWithMLModel(bitmap, targetWidth, targetHeight)
            
        } catch (e: Exception) {
            Log.e(TAG, "ML upsampling failed: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, scale)
        }
    }



    private fun processWithMLModel(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        try {
            // Process the image in patches using the ML model
            val patchSize = INPUT_SIZE
            val outputPatchSize = OUTPUT_SIZE
            
            // Calculate number of patches needed
            val patchesX = (targetWidth + patchSize - 1) / patchSize
            val patchesY = (targetHeight + patchSize - 1) / patchSize
            
            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            
            // Process each patch
            for (patchY in 0 until patchesY) {
                for (patchX in 0 until patchesX) {
                    // Calculate patch coordinates
                    val startX = patchX * patchSize
                    val startY = patchY * patchSize
                    val endX = minOf(startX + patchSize, bitmap.width)
                    val endY = minOf(startY + patchSize, bitmap.height)
                    
                    // Extract patch from input image
                    val inputPatch = Bitmap.createBitmap(bitmap, startX, startY, endX - startX, endY - startY)
                    
                    // Resize patch to model input size if needed
                    val resizedPatch = if (inputPatch.width != patchSize || inputPatch.height != patchSize) {
                        Bitmap.createScaledBitmap(inputPatch, patchSize, patchSize, true)
                    } else {
                        inputPatch
                    }
                    
                    // Process patch with ML model
                    val outputPatch = processPatchWithML(resizedPatch)
                    
                    // Calculate output patch coordinates
                    val outputStartX = patchX * outputPatchSize
                    val outputStartY = patchY * outputPatchSize
                    val outputEndX = minOf(outputStartX + outputPatchSize, targetWidth)
                    val outputEndY = minOf(outputStartY + outputPatchSize, targetHeight)
                    
                    // Copy processed patch to output bitmap
                    val canvas = Canvas(outputBitmap)
                    val srcRect = Rect(0, 0, outputPatch.width, outputPatch.height)
                    val dstRect = Rect(outputStartX, outputStartY, outputEndX, outputEndY)
                    canvas.drawBitmap(outputPatch, srcRect, dstRect, null)
                    
                    // Clean up
                    if (inputPatch != resizedPatch) {
                        resizedPatch.recycle()
                    }
                    inputPatch.recycle()
                    outputPatch.recycle()
                }
            }
            
            return outputBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with ML model: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, targetWidth.toFloat() / bitmap.width)
        }
    }

    private fun processPatchWithML(inputPatch: Bitmap): Bitmap {
        try {
            // Store original color statistics for preservation
            val originalStats = calculateColorStatistics(inputPatch)
            
            // Prepare input tensor
            val tensorImage = TensorImage.fromBitmap(inputPatch)
            val processedImage = imageProcessor.process(tensorImage)
            
            // Get input shape
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val inputBuffer = TensorBuffer.createFixedSize(inputShape ?: intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3), org.tensorflow.lite.DataType.FLOAT32)
            
            // Convert to float array and normalize to [0, 1]
            val floatArray = FloatArray(processedImage.buffer.capacity() / 4)
            processedImage.buffer.asFloatBuffer().get(floatArray)
            
            // Normalize pixel values from [0, 255] to [0, 1]
            for (i in floatArray.indices) {
                floatArray[i] = floatArray[i] / 255f
            }
            
            inputBuffer.loadArray(floatArray)
            
            // Prepare output tensor
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val outputBuffer = TensorBuffer.createFixedSize(outputShape ?: intArrayOf(1, OUTPUT_SIZE, OUTPUT_SIZE, 3), org.tensorflow.lite.DataType.FLOAT32)
            
            // Run inference
            interpreter?.run(inputBuffer.buffer, outputBuffer.buffer)
            
            // Process output
            val outputArray = outputBuffer.floatArray
            
            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(OUTPUT_SIZE * OUTPUT_SIZE)
            
            // Apply color preservation and correction
            val correctedPixels = applyColorPreservation(outputArray, originalStats)
            
            // Convert to pixels
            for (i in 0 until OUTPUT_SIZE * OUTPUT_SIZE) {
                val r = correctedPixels[i * 3].toInt().coerceIn(0, 255)
                val g = correctedPixels[i * 3 + 1].toInt().coerceIn(0, 255)
                val b = correctedPixels[i * 3 + 2].toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
            
            outputBitmap.setPixels(pixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
            return outputBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing patch with ML: ${e.message}")
            e.printStackTrace()
            // Fallback to simple upsampling
            return Bitmap.createScaledBitmap(inputPatch, OUTPUT_SIZE, OUTPUT_SIZE, true)
        }
    }

    private fun calculateColorStatistics(bitmap: Bitmap): ColorStats {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var minR = 255.0
        var minG = 255.0
        var minB = 255.0
        var maxR = 0.0
        var maxG = 0.0
        var maxB = 0.0
        
        for (pixel in pixels) {
            val r = Color.red(pixel).toDouble()
            val g = Color.green(pixel).toDouble()
            val b = Color.blue(pixel).toDouble()
            
            sumR += r
            sumG += g
            sumB += b
            
            minR = minOf(minR, r)
            minG = minOf(minG, g)
            minB = minOf(minB, b)
            
            maxR = maxOf(maxR, r)
            maxG = maxOf(maxG, g)
            maxB = maxOf(maxB, b)
        }
        
        val pixelCount = pixels.size.toDouble()
        return ColorStats(
            meanR = sumR / pixelCount,
            meanG = sumG / pixelCount,
            meanB = sumB / pixelCount,
            minR = minR,
            minG = minG,
            minB = minB,
            maxR = maxR,
            maxG = maxG,
            maxB = maxB,
            rangeR = maxR - minR,
            rangeG = maxG - minG,
            rangeB = maxB - minB
        )
    }

    private fun applyColorPreservation(outputArray: FloatArray, originalStats: ColorStats): FloatArray {
        val correctedArray = FloatArray(outputArray.size)
        
        // Calculate current output statistics
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var minR = Float.MAX_VALUE
        var minG = Float.MAX_VALUE
        var minB = Float.MAX_VALUE
        var maxR = Float.MIN_VALUE
        var maxG = Float.MIN_VALUE
        var maxB = Float.MIN_VALUE
        
        // First pass: calculate current statistics
        for (i in 0 until OUTPUT_SIZE * OUTPUT_SIZE) {
            val r = outputArray[i * 3] * 255f
            val g = outputArray[i * 3 + 1] * 255f
            val b = outputArray[i * 3 + 2] * 255f
            
            sumR += r
            sumG += g
            sumB += b
            
            minR = minOf(minR, r)
            minG = minOf(minG, g)
            minB = minOf(minB, b)
            
            maxR = maxOf(maxR, r)
            maxG = maxOf(maxG, g)
            maxB = maxOf(maxB, b)
        }
        
        val pixelCount = OUTPUT_SIZE * OUTPUT_SIZE.toDouble()
        val currentMeanR = sumR / pixelCount
        val currentMeanG = sumG / pixelCount
        val currentMeanB = sumB / pixelCount
        val currentRangeR = maxR - minR
        val currentRangeG = maxG - minG
        val currentRangeB = maxB - minB
        
        // Second pass: apply color correction
        for (i in 0 until OUTPUT_SIZE * OUTPUT_SIZE) {
            val r = outputArray[i * 3] * 255f
            val g = outputArray[i * 3 + 1] * 255f
            val b = outputArray[i * 3 + 2] * 255f
            
            // Apply histogram matching to preserve color distribution
            val correctedR = if (currentRangeR > 0) {
                originalStats.meanR + (r - currentMeanR) * (originalStats.rangeR / currentRangeR)
            } else {
                originalStats.meanR
            }
            
            val correctedG = if (currentRangeG > 0) {
                originalStats.meanG + (g - currentMeanG) * (originalStats.rangeG / currentRangeG)
            } else {
                originalStats.meanG
            }
            
            val correctedB = if (currentRangeB > 0) {
                originalStats.meanB + (b - currentMeanB) * (originalStats.rangeB / currentRangeB)
            } else {
                originalStats.meanB
            }
            
            // Apply color balance preservation
            val originalRatio = originalStats.meanR / (originalStats.meanG + originalStats.meanB)
            val currentRatio = correctedR / (correctedG + correctedB)
            val balanceFactor = if (currentRatio > 0) originalRatio / currentRatio else 1.0
            
            correctedArray[i * 3] = (correctedR * balanceFactor).toFloat()
            correctedArray[i * 3 + 1] = correctedG.toFloat()
            correctedArray[i * 3 + 2] = correctedB.toFloat()
        }
        
        return correctedArray
    }

    private data class ColorStats(
        val meanR: Double,
        val meanG: Double,
        val meanB: Double,
        val minR: Double,
        val minG: Double,
        val minB: Double,
        val maxR: Double,
        val maxG: Double,
        val maxB: Double,
        val rangeR: Double,
        val rangeG: Double,
        val rangeB: Double
    )



    private fun fallbackUpsampling(bitmap: Bitmap, scale: Float): Bitmap {
        val targetWidth = (bitmap.width * scale).toInt()
        val targetHeight = (bitmap.height * scale).toInt()
        
        Log.d(TAG, "Fallback upsampling to ${targetWidth}x${targetHeight}")
        
        // Use high-quality upsampling with color preservation
        return createHighQualityUpsampledBitmap(bitmap, targetWidth, targetHeight)
    }

    private fun createHighQualityUpsampledBitmap(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        // Create output bitmap
        val outputBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        
        // Get pixel arrays
        val inputPixels = IntArray(bitmap.width * bitmap.height)
        val outputPixels = IntArray(newWidth * newHeight)
        
        bitmap.getPixels(inputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Calculate scale factors
        val scaleX = bitmap.width.toFloat() / newWidth
        val scaleY = bitmap.height.toFloat() / newHeight
        
        // High-quality upsampling with color preservation
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                val srcX = x * scaleX
                val srcY = y * scaleY
                
                val pixel = getHighQualityUpsampledPixel(inputPixels, srcX, srcY, bitmap.width, bitmap.height)
                outputPixels[y * newWidth + x] = pixel
            }
        }
        
        outputBitmap.setPixels(outputPixels, 0, newWidth, 0, 0, newWidth, newHeight)
        return outputBitmap
    }

    private fun getHighQualityUpsampledPixel(pixels: IntArray, x: Float, y: Float, width: Int, height: Int): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        
        val dx = x - x0
        val dy = y - y0
        
        // Get 4 surrounding pixels for bicubic interpolation
        val p00 = pixels[y0 * width + x0]
        val p01 = pixels[y0 * width + x1]
        val p10 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        
        // Interpolate each color channel separately to preserve colors
        val r = (Color.red(p00) * (1 - dx) * (1 - dy) +
                Color.red(p01) * dx * (1 - dy) +
                Color.red(p10) * (1 - dx) * dy +
                Color.red(p11) * dx * dy).toInt().coerceIn(0, 255)
        
        val g = (Color.green(p00) * (1 - dx) * (1 - dy) +
                Color.green(p01) * dx * (1 - dy) +
                Color.green(p10) * (1 - dx) * dy +
                Color.green(p11) * dx * dy).toInt().coerceIn(0, 255)
        
        val b = (Color.blue(p00) * (1 - dx) * (1 - dy) +
                Color.blue(p01) * dx * (1 - dy) +
                Color.blue(p10) * (1 - dx) * dy +
                Color.blue(p11) * dx * dy).toInt().coerceIn(0, 255)
        
        return Color.rgb(r, g, b)
    }
} 