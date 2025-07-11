package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AdvancedMLUpsamplingProcessor(private val context: Context) {
    companion object {
        private const val TAG = "AdvancedMLUpsamplingProcessor"
        // Use the ESRGAN TFLite model
        private const val MODEL_FILE = "esrgan.tflite"
        // ESRGAN expects input: float32, [0,1], shape [1, 128, 128, 3]
        private const val INPUT_SIZE = 128 // ESRGAN input size
        private const val SCALE_FACTOR = 4 // ESRGAN 4x upsampling
        private const val OUTPUT_SIZE = INPUT_SIZE * SCALE_FACTOR // 512x512 output
        private const val PATCH_OVERLAP = 0 // No overlap for clean patch processing
        private const val MAX_INFERENCE_TIME_MS = 5000L // 5 second timeout
        private const val MAX_PATCHES_PER_IMAGE = 16 // Limit patches to prevent freezing
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
            Log.d(TAG, "Loading advanced ML super-resolution model from assets")
            
            val modelInputStream = context.assets.open(MODEL_FILE)
            val modelBytes = modelInputStream.readBytes()
            modelInputStream.close()
            
            Log.d(TAG, "Advanced model loaded from assets. Size: ${modelBytes.size} bytes")
            
            val options = Interpreter.Options().apply {
                setNumThreads(1) // Use single thread to prevent conflicts
                setUseXNNPACK(false) // Disable XNNPACK to prevent freezing
                // Don't use NNAPI to avoid conflicts with other services
                // setUseNNAPI(true) // Commented out to prevent conflicts
            }
            
            interpreter = Interpreter(ByteBuffer.allocateDirect(modelBytes.size).apply {
                put(modelBytes)
                rewind()
            }, options)
            
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            
            Log.d(TAG, "Advanced ML model loaded successfully")
            Log.d(TAG, "Input tensor: ${inputTensor?.name()}, shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "Output tensor: ${outputTensor?.name()}, shape: ${outputTensor?.shape()?.contentToString()}")
            
            isInitialized = true
            Log.d(TAG, "Advanced ML super-resolution model initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize advanced ML upsampling model: ${e.message}")
            e.printStackTrace()
            isInitialized = false
        }
    }

    fun upsampleImage(bitmap: Bitmap, scale: Float): Bitmap {
        if (scale == 1.0f) {
            Log.d(TAG, "Scale is 1.0, saving original image without upsampling.")
            return bitmap
        }
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "Advanced ML model not initialized, using fallback upsampling")
            return fallbackUpsampling(bitmap, scale)
        }

        try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            
            // For ML upsampling, always use 4x scale
            val targetWidth = originalWidth * SCALE_FACTOR
            val targetHeight = originalHeight * SCALE_FACTOR
            
            Log.d(TAG, "ESRGAN 4x upsampling from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight}")
            Log.d(TAG, "Processing with 128x128 patches -> 512x512 outputs")
            
            return processWithAdvancedMLModel(bitmap, targetWidth, targetHeight)
            
        } catch (e: Exception) {
            Log.e(TAG, "Advanced ML upsampling failed: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, scale)
        }
    }

    private fun processWithAdvancedMLModel(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        try {
            Log.d(TAG, "Processing image with 128x128 patches for 4x upsampling")
            Log.d(TAG, "Input: ${bitmap.width}x${bitmap.height} -> Output: ${targetWidth}x${targetHeight}")
            
            // Calculate number of 128x128 patches needed
            val inputPatchesX = (bitmap.width + INPUT_SIZE - 1) / INPUT_SIZE
            val inputPatchesY = (bitmap.height + INPUT_SIZE - 1) / INPUT_SIZE
            
            // Limit the number of patches to prevent freezing
            if (inputPatchesX * inputPatchesY > MAX_PATCHES_PER_IMAGE) {
                Log.w(TAG, "Too many patches (${inputPatchesX * inputPatchesY}), using fallback upsampling")
                return fallbackUpsampling(bitmap, targetWidth.toFloat() / bitmap.width)
            }
            
            Log.d(TAG, "Processing ${inputPatchesX}x${inputPatchesY} patches")
            Log.d(TAG, "Each patch: 128x128 -> 512x512 (4x upsampling)")
            
            val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            
            // Process each 128x128 patch
            var patchCount = 0
            val totalPatches = inputPatchesX * inputPatchesY
            
            for (patchY in 0 until inputPatchesY) {
                for (patchX in 0 until inputPatchesX) {
                    patchCount++
                    Log.d(TAG, "Processing patch $patchCount/$totalPatches (${patchX},${patchY})")
                    
                    // Check for timeout
                    if (SystemClock.uptimeMillis() > MAX_INFERENCE_TIME_MS) {
                        Log.w(TAG, "Inference timeout reached, using fallback for remaining patches")
                        return fallbackUpsampling(bitmap, targetWidth.toFloat() / bitmap.width)
                    }
                    
                    // Calculate input patch coordinates
                    val inputStartX = patchX * INPUT_SIZE
                    val inputStartY = patchY * INPUT_SIZE
                    val inputEndX = minOf(inputStartX + INPUT_SIZE, bitmap.width)
                    val inputEndY = minOf(inputStartY + INPUT_SIZE, bitmap.height)
                    
                    // Calculate output patch coordinates (4x larger)
                    val outputStartX = patchX * OUTPUT_SIZE
                    val outputStartY = patchY * OUTPUT_SIZE
                    val outputEndX = minOf(outputStartX + OUTPUT_SIZE, targetWidth)
                    val outputEndY = minOf(outputStartY + OUTPUT_SIZE, targetHeight)
                    
                    // Extract input patch
                    val inputPatch = Bitmap.createBitmap(bitmap, inputStartX, inputStartY, 
                                                       inputEndX - inputStartX, inputEndY - inputStartY)
                    
                    // Resize patch to exactly 128x128 if needed
                    val resizedPatch = if (inputPatch.width != INPUT_SIZE || inputPatch.height != INPUT_SIZE) {
                        Bitmap.createScaledBitmap(inputPatch, INPUT_SIZE, INPUT_SIZE, true)
                    } else {
                        inputPatch
                    }
                    
                    Log.d(TAG, "Processing patch ${patchX},${patchY}: ${resizedPatch.width}x${resizedPatch.height}")
                    
                    // Process patch with ESRGAN model (128x128 -> 512x512)
                    val outputPatch = processPatchWithAdvancedML(resizedPatch)
                    
                    if (outputPatch != null) {
                        // Apply color preservation
                        val colorPreservedPatch = applySimpleColorPreservation(outputPatch, inputPatch)
                        
                        // Copy processed patch to output bitmap
                        val srcRect = Rect(0, 0, colorPreservedPatch.width, colorPreservedPatch.height)
                        val dstRect = Rect(outputStartX, outputStartY, outputEndX, outputEndY)
                        
                        val paint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                        }
                        
                        canvas.drawBitmap(colorPreservedPatch, srcRect, dstRect, paint)
                        
                        // Clean up
                        colorPreservedPatch.recycle()
                        outputPatch.recycle()
                    }
                    
                    // Clean up
                    if (inputPatch != resizedPatch) {
                        resizedPatch.recycle()
                    }
                    inputPatch.recycle()
                }
            }
            
            Log.d(TAG, "Completed 4x upsampling with patch processing")
            return outputBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with advanced ML model: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, targetWidth.toFloat() / bitmap.width)
        }
    }

    private fun processPatchWithAdvancedML(inputPatch: Bitmap): Bitmap? {
        try {
            val startTime = SystemClock.uptimeMillis()
            
            // Convert Bitmap to float32 [0,1] and add batch dimension
            val inputWidth = inputPatch.width
            val inputHeight = inputPatch.height
            val inputArray = Array(1) { Array(inputHeight) { Array(inputWidth) { FloatArray(3) } } }
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = inputPatch.getPixel(x, y)
                    inputArray[0][y][x][0] = Color.red(pixel) / 255f
                    inputArray[0][y][x][1] = Color.green(pixel) / 255f
                    inputArray[0][y][x][2] = Color.blue(pixel) / 255f
                }
            }

            // Prepare input tensor
            val inputShape = intArrayOf(1, inputHeight, inputWidth, 3)
            val inputBuffer = ByteBuffer.allocateDirect(4 * 1 * inputHeight * inputWidth * 3)
                .order(ByteOrder.nativeOrder())
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    for (c in 0..2) {
                        inputBuffer.putFloat(inputArray[0][y][x][c])
                    }
                }
            }
            inputBuffer.rewind()

            // Prepare output tensor shape (1, h*4, w*4, 3)
            val outputHeight = inputHeight * SCALE_FACTOR
            val outputWidth = inputWidth * SCALE_FACTOR
            val outputShape = intArrayOf(1, outputHeight, outputWidth, 3)
            val outputBuffer = ByteBuffer.allocateDirect(4 * 1 * outputHeight * outputWidth * 3)
                .order(ByteOrder.nativeOrder())

            // Check for timeout before running inference
            if (SystemClock.uptimeMillis() - startTime > MAX_INFERENCE_TIME_MS) {
                Log.w(TAG, "Inference timeout before running model")
                return null
            }

            // Run inference with timeout protection
            try {
                interpreter?.resizeInput(0, inputShape)
                interpreter?.allocateTensors()
                interpreter?.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed: ${e.message}")
                return null
            }

            // Check for timeout after inference
            if (SystemClock.uptimeMillis() - startTime > MAX_INFERENCE_TIME_MS) {
                Log.w(TAG, "Inference timeout after running model")
                return null
            }

            // Convert output buffer to Bitmap (float32 [0,1] to [0,255])
            val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(outputWidth * outputHeight)
            
            // Reset buffer position
            outputBuffer.rewind()
            
            // Read all float values at once
            val floatBuffer = outputBuffer.asFloatBuffer()
            val floatArray = FloatArray(outputHeight * outputWidth * 3)
            floatBuffer.get(floatArray)
            
            // Convert to pixels with proper color preservation
            for (y in 0 until outputHeight) {
                for (x in 0 until outputWidth) {
                    val pixelIndex = y * outputWidth + x
                    val arrayIndex = pixelIndex * 3
                    
                    // Get RGB values and ensure they're in [0,1] range
                    val r = floatArray[arrayIndex].coerceIn(0f, 1f)
                    val g = floatArray[arrayIndex + 1].coerceIn(0f, 1f)
                    val b = floatArray[arrayIndex + 2].coerceIn(0f, 1f)
                    
                    // Convert to [0,255] range
                    val red = (r * 255f).toInt().coerceIn(0, 255)
                    val green = (g * 255f).toInt().coerceIn(0, 255)
                    val blue = (b * 255f).toInt().coerceIn(0, 255)
                    
                    pixels[pixelIndex] = Color.rgb(red, green, blue)
                }
            }
            
            outputBitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
            
            // Apply color preservation to maintain original color characteristics
            return applySimpleColorPreservation(outputBitmap, inputPatch)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing patch with advanced ML: ${e.message}")
            e.printStackTrace()
            // Fallback to simple upsampling
            return Bitmap.createScaledBitmap(inputPatch, inputPatch.width * SCALE_FACTOR, inputPatch.height * SCALE_FACTOR, true)
        }
    }

    private fun applySimpleColorPreservation(outputPatch: Bitmap, originalPatch: Bitmap): Bitmap {
        try {
            // Use simple color transfer instead of complex histogram matching
            return applySimpleColorTransfer(outputPatch, originalPatch)
        } catch (e: Exception) {
            Log.w(TAG, "Color preservation failed, returning original output: ${e.message}")
            return outputPatch
        }
    }
    
    private fun applySimpleColorTransfer(outputPatch: Bitmap, originalPatch: Bitmap): Bitmap {
        try {
            // Simple color transfer based on mean and standard deviation
            val originalPixels = IntArray(originalPatch.width * originalPatch.height)
            val outputPixels = IntArray(outputPatch.width * outputPatch.height)
            
            originalPatch.getPixels(originalPixels, 0, originalPatch.width, 0, 0, originalPatch.width, originalPatch.height)
            outputPatch.getPixels(outputPixels, 0, outputPatch.width, 0, 0, outputPatch.width, outputPatch.height)
            
            // Calculate statistics for each channel
            val originalStats = calculateColorStats(originalPixels)
            val outputStats = calculateColorStats(outputPixels)
            
            // Apply color transfer
            for (i in outputPixels.indices) {
                val pixel = outputPixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Transfer color statistics with safety checks
                val newR = if (outputStats.stdR > 0) {
                    ((r - outputStats.avgR) * (originalStats.stdR / outputStats.stdR) + originalStats.avgR).toInt().coerceIn(0, 255)
                } else {
                    r
                }
                val newG = if (outputStats.stdG > 0) {
                    ((g - outputStats.avgG) * (originalStats.stdG / outputStats.stdG) + originalStats.avgG).toInt().coerceIn(0, 255)
                } else {
                    g
                }
                val newB = if (outputStats.stdB > 0) {
                    ((b - outputStats.avgB) * (originalStats.stdB / outputStats.stdB) + originalStats.avgB).toInt().coerceIn(0, 255)
                } else {
                    b
                }
                
                outputPixels[i] = Color.rgb(newR, newG, newB)
            }
            
            val correctedBitmap = Bitmap.createBitmap(outputPatch.width, outputPatch.height, Bitmap.Config.ARGB_8888)
            correctedBitmap.setPixels(outputPixels, 0, outputPatch.width, 0, 0, outputPatch.width, outputPatch.height)
            
            return correctedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in simple color transfer: ${e.message}")
            return outputPatch
        }
    }
    
    private data class ColorStats(
        val avgR: Double,
        val avgG: Double,
        val avgB: Double,
        val stdR: Double,
        val stdG: Double,
        val stdB: Double
    )
    
    private fun calculateColorStats(pixels: IntArray): ColorStats {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        
        for (pixel in pixels) {
            sumR += Color.red(pixel)
            sumG += Color.green(pixel)
            sumB += Color.blue(pixel)
        }
        
        val count = pixels.size.toDouble()
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        
        var sumSqR = 0.0
        var sumSqG = 0.0
        var sumSqB = 0.0
        
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            sumSqR += (r - avgR) * (r - avgR)
            sumSqG += (g - avgG) * (g - avgG)
            sumSqB += (b - avgB) * (b - avgB)
        }
        
        val stdR = sqrt(sumSqR / count)
        val stdG = sqrt(sumSqG / count)
        val stdB = sqrt(sumSqB / count)
        
        return ColorStats(avgR, avgG, avgB, stdR, stdG, stdB)
    }

    private fun fallbackUpsampling(bitmap: Bitmap, scale: Float): Bitmap {
        val targetWidth = (bitmap.width * scale).toInt()
        val targetHeight = (bitmap.height * scale).toInt()
        
        Log.d(TAG, "Fallback upsampling to ${targetWidth}x${targetHeight}")
        
        return createHighQualityUpsampledBitmap(bitmap, targetWidth, targetHeight)
    }

    private fun createHighQualityUpsampledBitmap(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val outputBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val inputPixels = IntArray(bitmap.width * bitmap.height)
        val outputPixels = IntArray(newWidth * newHeight)
        
        bitmap.getPixels(inputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val scaleX = bitmap.width.toFloat() / newWidth
        val scaleY = bitmap.height.toFloat() / newHeight
        
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
        
        val p00 = pixels[y0 * width + x0]
        val p01 = pixels[y0 * width + x1]
        val p10 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        
        // Bicubic interpolation for better quality
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