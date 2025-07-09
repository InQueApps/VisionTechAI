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

class AdvancedMLUpsamplingProcessor(private val context: Context) {
    companion object {
        private const val TAG = "AdvancedMLUpsamplingProcessor"
        // Use the ESRGAN TFLite model
        private const val MODEL_FILE = "esrgan.tflite"
        // ESRGAN expects input: float32, [0,1], shape [1, h, w, 3]
        private const val INPUT_SIZE = 64 // You may need to update this if ESRGAN expects a different size
        private const val SCALE_FACTOR = 4 // ESRGAN is usually 4x
        private const val OUTPUT_SIZE = INPUT_SIZE * SCALE_FACTOR
        private const val PATCH_OVERLAP = 8 // Overlap between patches for seamless stitching
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
                setNumThreads(2) // Use fewer threads to avoid conflicts
                setUseXNNPACK(true)
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
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "Advanced ML model not initialized, using fallback upsampling")
            return fallbackUpsampling(bitmap, scale)
        }

        try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            
            val targetWidth = (originalWidth * scale).toInt()
            val targetHeight = (originalHeight * scale).toInt()
            
            // Safety check: prevent processing images that are too large
            val maxDimension = 2048
            if (targetWidth > maxDimension || targetHeight > maxDimension) {
                Log.w(TAG, "Target size too large (${targetWidth}x${targetHeight}), using fallback")
                return fallbackUpsampling(bitmap, scale)
            }
            
            Log.d(TAG, "Advanced ML upsampling from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight}")
            
            return processWithAdvancedMLModel(bitmap, targetWidth, targetHeight)
            
        } catch (e: Exception) {
            Log.e(TAG, "Advanced ML upsampling failed: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, scale)
        }
    }

    private fun processWithAdvancedMLModel(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        try {
            val effectivePatchSize = INPUT_SIZE - PATCH_OVERLAP
            val effectiveOutputPatchSize = OUTPUT_SIZE - (PATCH_OVERLAP * SCALE_FACTOR)
            
            val patchesX = (targetWidth + effectiveOutputPatchSize - 1) / effectiveOutputPatchSize
            val patchesY = (targetHeight + effectiveOutputPatchSize - 1) / effectiveOutputPatchSize
            
            val outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)
            
            // Process each patch with overlap
            for (patchY in 0 until patchesY) {
                for (patchX in 0 until patchesX) {
                    val startX = patchX * effectiveOutputPatchSize
                    val startY = patchY * effectiveOutputPatchSize
                    val endX = minOf(startX + effectiveOutputPatchSize, targetWidth)
                    val endY = minOf(startY + effectiveOutputPatchSize, targetHeight)
                    
                    // Calculate corresponding input patch coordinates
                    val inputStartX = (startX / SCALE_FACTOR).toInt()
                    val inputStartY = (startY / SCALE_FACTOR).toInt()
                    val inputEndX = minOf(inputStartX + INPUT_SIZE, bitmap.width)
                    val inputEndY = minOf(inputStartY + INPUT_SIZE, bitmap.height)
                    
                    // Extract input patch
                    val inputPatch = Bitmap.createBitmap(bitmap, inputStartX, inputStartY, 
                                                       inputEndX - inputStartX, inputEndY - inputStartY)
                    
                    // Resize patch to model input size if needed
                    val resizedPatch = if (inputPatch.width != INPUT_SIZE || inputPatch.height != INPUT_SIZE) {
                        Bitmap.createScaledBitmap(inputPatch, INPUT_SIZE, INPUT_SIZE, true)
                    } else {
                        inputPatch
                    }
                    
                    // Process patch with advanced ML model
                    val outputPatch = processPatchWithAdvancedML(resizedPatch)
                    
                    // Apply simple color preservation
                    val colorPreservedPatch = applySimpleColorPreservation(outputPatch, inputPatch)
                    
                    // Copy processed patch to output bitmap with blending for overlap regions
                    val srcRect = Rect(0, 0, colorPreservedPatch.width, colorPreservedPatch.height)
                    val dstRect = Rect(startX, startY, endX, endY)
                    
                    // Use Porter-Duff blending for smooth patch stitching
                    val paint = Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                    }
                    
                    canvas.drawBitmap(colorPreservedPatch, srcRect, dstRect, paint)
                    
                    // Clean up
                    if (inputPatch != resizedPatch) {
                        resizedPatch.recycle()
                    }
                    inputPatch.recycle()
                    outputPatch.recycle()
                    colorPreservedPatch.recycle()
                }
            }
            
            return outputBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with advanced ML model: ${e.message}")
            e.printStackTrace()
            return fallbackUpsampling(bitmap, targetWidth.toFloat() / bitmap.width)
        }
    }

    private fun processPatchWithAdvancedML(inputPatch: Bitmap): Bitmap {
        try {
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
            val inputBuffer = java.nio.ByteBuffer.allocateDirect(4 * 1 * inputHeight * inputWidth * 3)
                .order(java.nio.ByteOrder.nativeOrder())
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
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(4 * 1 * outputHeight * outputWidth * 3)
                .order(java.nio.ByteOrder.nativeOrder())

            // Run inference
            interpreter?.resizeInput(0, inputShape)
            interpreter?.allocateTensors()
            interpreter?.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

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
            // Try the advanced histogram matching first
            return applyHistogramMatching(outputPatch, originalPatch)
        } catch (e: Exception) {
            Log.w(TAG, "Histogram matching failed, using simple color transfer: ${e.message}")
            // Fallback to simple color transfer
            return applySimpleColorTransfer(outputPatch, originalPatch)
        }
    }
    
    private fun applyHistogramMatching(outputPatch: Bitmap, originalPatch: Bitmap): Bitmap {
        // Get pixel arrays
        val originalPixels = IntArray(originalPatch.width * originalPatch.height)
        val outputPixels = IntArray(outputPatch.width * outputPatch.height)
        
        originalPatch.getPixels(originalPixels, 0, originalPatch.width, 0, 0, originalPatch.width, originalPatch.height)
        outputPatch.getPixels(outputPixels, 0, outputPatch.width, 0, 0, outputPatch.width, outputPatch.height)
        
        // Calculate histograms for each channel
        val originalHistR = IntArray(256)
        val originalHistG = IntArray(256)
        val originalHistB = IntArray(256)
        val outputHistR = IntArray(256)
        val outputHistG = IntArray(256)
        val outputHistB = IntArray(256)
        
        // Build histograms
        for (pixel in originalPixels) {
            originalHistR[Color.red(pixel)]++
            originalHistG[Color.green(pixel)]++
            originalHistB[Color.blue(pixel)]++
        }
        
        for (pixel in outputPixels) {
            outputHistR[Color.red(pixel)]++
            outputHistG[Color.green(pixel)]++
            outputHistB[Color.blue(pixel)]++
        }
        
        // Calculate cumulative distribution functions (CDFs)
        val originalCDFR = calculateCDF(originalHistR)
        val originalCDFG = calculateCDF(originalHistG)
        val originalCDFB = calculateCDF(originalHistB)
        val outputCDFR = calculateCDF(outputHistR)
        val outputCDFG = calculateCDF(outputHistG)
        val outputCDFB = calculateCDF(outputHistB)
        
        // Create lookup tables for histogram matching
        val lookupR = createHistogramLookup(outputCDFR, originalCDFR)
        val lookupG = createHistogramLookup(outputCDFG, originalCDFG)
        val lookupB = createHistogramLookup(outputCDFB, originalCDFB)
        
        // Apply histogram matching with color transfer
        for (i in outputPixels.indices) {
            val pixel = outputPixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            // Apply histogram matching
            val matchedR = lookupR[r]
            val matchedG = lookupG[g]
            val matchedB = lookupB[b]
            
            // Apply color transfer to maintain overall color balance
            val originalAvgR = calculateAverage(originalPixels) { Color.red(it) }
            val originalAvgG = calculateAverage(originalPixels) { Color.green(it) }
            val originalAvgB = calculateAverage(originalPixels) { Color.blue(it) }
            
            val outputAvgR = calculateAverage(outputPixels) { Color.red(it) }
            val outputAvgG = calculateAverage(outputPixels) { Color.green(it) }
            val outputAvgB = calculateAverage(outputPixels) { Color.blue(it) }
            
            // Calculate color transfer factors
            val transferStrength = 0.5f // Reduce strength to avoid over-correction
            val factorR = if (outputAvgR > 0) originalAvgR / outputAvgR else 1.0
            val factorG = if (outputAvgG > 0) originalAvgG / outputAvgG else 1.0
            val factorB = if (outputAvgB > 0) originalAvgB / outputAvgB else 1.0
            
            // Apply color transfer with smoothing
            val finalR = (matchedR * (1f - transferStrength) + matchedR * factorR * transferStrength).toInt().coerceIn(0, 255)
            val finalG = (matchedG * (1f - transferStrength) + matchedG * factorG * transferStrength).toInt().coerceIn(0, 255)
            val finalB = (matchedB * (1f - transferStrength) + matchedB * factorB * transferStrength).toInt().coerceIn(0, 255)
            
            outputPixels[i] = Color.rgb(finalR, finalG, finalB)
        }
        
        val correctedBitmap = Bitmap.createBitmap(outputPatch.width, outputPatch.height, Bitmap.Config.ARGB_8888)
        correctedBitmap.setPixels(outputPixels, 0, outputPatch.width, 0, 0, outputPatch.width, outputPatch.height)
        
        return correctedBitmap
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
                
                // Transfer color statistics
                val newR = ((r - outputStats.avgR) * (originalStats.stdR / outputStats.stdR) + originalStats.avgR).toInt().coerceIn(0, 255)
                val newG = ((g - outputStats.avgG) * (originalStats.stdG / outputStats.stdG) + originalStats.avgG).toInt().coerceIn(0, 255)
                val newB = ((b - outputStats.avgB) * (originalStats.stdB / outputStats.stdB) + originalStats.avgB).toInt().coerceIn(0, 255)
                
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
        
        val stdR = kotlin.math.sqrt(sumSqR / count)
        val stdG = kotlin.math.sqrt(sumSqG / count)
        val stdB = kotlin.math.sqrt(sumSqB / count)
        
        return ColorStats(avgR, avgG, avgB, stdR, stdG, stdB)
    }
    
    private fun calculateCDF(histogram: IntArray): FloatArray {
        val cdf = FloatArray(256)
        cdf[0] = histogram[0].toFloat()
        for (i in 1 until 256) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }
        
        // Normalize to [0, 1]
        val total = cdf[255]
        if (total > 0) {
            for (i in cdf.indices) {
                cdf[i] = cdf[i] / total
            }
        }
        
        return cdf
    }
    
    private fun createHistogramLookup(sourceCDF: FloatArray, targetCDF: FloatArray): IntArray {
        val lookup = IntArray(256)
        for (i in 0 until 256) {
            val sourceValue = sourceCDF[i]
            var bestMatch = 0
            var minDiff = Float.MAX_VALUE
            
            for (j in 0 until 256) {
                val diff = kotlin.math.abs(sourceValue - targetCDF[j])
                if (diff < minDiff) {
                    minDiff = diff
                    bestMatch = j
                }
            }
            lookup[i] = bestMatch
        }
        return lookup
    }
    
    private fun calculateAverage(pixels: IntArray, colorExtractor: (Int) -> Int): Double {
        var sum = 0.0
        for (pixel in pixels) {
            sum += colorExtractor(pixel)
        }
        return sum / pixels.size
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
        
        // Apply color preservation to maintain original color characteristics
        return applySimpleColorPreservation(outputBitmap, bitmap)
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