package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.*

class HDImageProcessor(private val context: Context) {
    companion object {
        private const val TAG = "HDImageProcessor"
        private const val MIN_SCALE = 0.01f  // 100x downsampling (1/100)
        private const val MAX_SCALE = 4.0f   // 4x upsampling (ESRGAN scale)
        private const val CENTER_SCALE = 1.0f // No effect at center
        private const val ML_UPSAMPLING_THRESHOLD = 1.5f // Use ML for upsampling above this threshold
    }

    private val advancedMLProcessor = AdvancedMLUpsamplingProcessor(context)
    private var useMLUpsampling = true // Default to ML upsampling

    fun setUseMLUpsampling(useML: Boolean) {
        useMLUpsampling = useML
        Log.d(TAG, "ML upsampling ${if (useML) "enabled" else "disabled"}")
    }

    fun isUsingMLUpsampling(): Boolean {
        return useMLUpsampling
    }

    fun processImage(bitmap: Bitmap, sliderValue: Int): Bitmap {
        try {
            // Convert slider value (0-100) to scale factor
            val scale = convertSliderToScale(sliderValue)
            
            Log.d(TAG, "Processing image with scale: $scale (slider: $sliderValue), ML: $useMLUpsampling")
            
            return if (scale < CENTER_SCALE) {
                // Downsampling
                downsampleImage(bitmap, scale)
            } else if (scale > CENTER_SCALE) {
                // Upsampling - choose between ML and standard based on flag
                if (useMLUpsampling && scale >= ML_UPSAMPLING_THRESHOLD) {
                    Log.d(TAG, "Using ML upsampling for scale: $scale")
                    advancedMLProcessor.upsampleImage(bitmap, scale)
                } else {
                    Log.d(TAG, "Using standard upsampling for scale: $scale")
                    standardUpsampleImage(bitmap, scale)
                }
            } else {
                // Scale = 1.0 (CENTER_SCALE) - return original bitmap without any processing
                Log.d(TAG, "Scale is 1.0, returning original bitmap without processing")
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "HD processing failed: ${e.message}")
            e.printStackTrace()
            return bitmap
        }
    }

    private fun convertSliderToScale(sliderValue: Int): Float {
        // Convert slider (0-100) to scale factor
        // 0 = 100x downsampling (0.01)
        // 50 = no effect (1.0)
        // 51-100 = upsampling (1.0x to 4.0x for standard, locked to 4.0x for ML)
        
        return when {
            sliderValue < 50 -> {
                // Downsampling: 50 -> 1.0, 0 -> 0.01
                val normalized = sliderValue / 50f
                CENTER_SCALE - (CENTER_SCALE - MIN_SCALE) * (1f - normalized)
            }
            sliderValue > 50 -> {
                if (useMLUpsampling) {
                    // ML upsampling: Always use 4x (ESRGAN scale)
                    MAX_SCALE
                } else {
                    // Standard upsampling: 51 -> 1.0x, 100 -> 4.0x
                    val normalized = (sliderValue - 50) / 50f
                    CENTER_SCALE + (MAX_SCALE - CENTER_SCALE) * normalized
                }
            }
            else -> CENTER_SCALE
        }
    }

    private fun downsampleImage(bitmap: Bitmap, scale: Float): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        val newWidth = (originalWidth * scale).toInt().coerceAtLeast(1)
        val newHeight = (originalHeight * scale).toInt().coerceAtLeast(1)
        
        Log.d(TAG, "Downsampling from ${originalWidth}x${originalHeight} to ${newWidth}x${newHeight}")
        
        // Use high-quality downsampling with color preservation
        return createHighQualityDownsampledBitmap(bitmap, newWidth, newHeight)
    }

    private fun createHighQualityDownsampledBitmap(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        // Create output bitmap
        val outputBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        
        // Get pixel arrays
        val inputPixels = IntArray(bitmap.width * bitmap.height)
        val outputPixels = IntArray(newWidth * newHeight)
        
        bitmap.getPixels(inputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Calculate scale factors
        val scaleX = bitmap.width.toFloat() / newWidth
        val scaleY = bitmap.height.toFloat() / newHeight
        
        // High-quality downsampling with color preservation
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                val srcX = x * scaleX
                val srcY = y * scaleY
                
                val pixel = getHighQualityDownsampledPixel(inputPixels, srcX, srcY, bitmap.width, bitmap.height)
                outputPixels[y * newWidth + x] = pixel
            }
        }
        
        outputBitmap.setPixels(outputPixels, 0, newWidth, 0, 0, newWidth, newHeight)
        return outputBitmap
    }

    private fun getHighQualityDownsampledPixel(pixels: IntArray, x: Float, y: Float, width: Int, height: Int): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        
        val dx = x - x0
        val dy = y - y0
        
        // Get 4 surrounding pixels for bilinear interpolation
        val p00 = pixels[y0 * width + x0]
        val p01 = pixels[y0 * width + x1]
        val p10 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        
        // Simple bilinear interpolation for each color channel
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

    private fun standardUpsampleImage(bitmap: Bitmap, scale: Float): Bitmap {
        val targetWidth = (bitmap.width * scale).toInt()
        val targetHeight = (bitmap.height * scale).toInt()
        
        Log.d(TAG, "Standard upsampling from ${bitmap.width}x${bitmap.height} to ${targetWidth}x${targetHeight}")
        
        // Try to use Android's built-in high-quality scaling first
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (e: Exception) {
            Log.w(TAG, "Built-in scaling failed, using custom implementation: ${e.message}")
            // Fallback to custom high-quality upsampling
            createHighQualityUpsampledBitmap(bitmap, targetWidth, targetHeight)
        }
    }

    private fun createHighQualityUpsampledBitmap(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val outputBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val inputPixels = IntArray(bitmap.width * bitmap.height)
        val outputPixels = IntArray(newWidth * newHeight)
        
        bitmap.getPixels(inputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
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
        
        // Get 4 surrounding pixels for bilinear interpolation
        val p00 = pixels[y0 * width + x0]
        val p01 = pixels[y0 * width + x1]
        val p10 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        
        // Bilinear interpolation for standard upsampling
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