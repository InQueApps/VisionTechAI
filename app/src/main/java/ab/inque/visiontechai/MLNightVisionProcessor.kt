package ab.inque.visiontechai

import android.graphics.*
import android.util.Log
import kotlin.math.*

class MLNightVisionProcessor {
    companion object {
        private const val TAG = "MLNightVisionProcessor"
    }

    /**
     * Lightweight neural network-inspired low-light enhancement
     * Uses learned parameters and adaptive processing for realistic enhancement
     */
    fun processMLNightVision(inputBitmap: Bitmap, intensity: Float): Bitmap {
        try {
            val width = inputBitmap.width
            val height = inputBitmap.height
            
            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Get pixel data
            val inputPixels = IntArray(width * height)
            val outputPixels = IntArray(width * height)
            inputBitmap.getPixels(inputPixels, 0, width, 0, 0, width, height)
            
            // Apply neural network-inspired enhancement
            val enhancedPixels = applyNeuralEnhancement(inputPixels, width, height, intensity)
            
            outputBitmap.setPixels(enhancedPixels, 0, width, 0, 0, width, height)
            return outputBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "ML night vision processing failed: ${e.message}")
            return inputBitmap
        }
    }

    /**
     * Neural network-inspired enhancement algorithm
     * Simulates a lightweight CNN with learned parameters for low-light enhancement
     */
    private fun applyNeuralEnhancement(pixels: IntArray, width: Int, height: Int, intensity: Float): IntArray {
        val result = IntArray(pixels.size)
        
        // Calculate global statistics (like CNN's global average pooling)
        val stats = calculateImageStatistics(pixels)
        
        // Adaptive enhancement parameters based on image characteristics
        val enhancementParams = calculateEnhancementParameters(stats, intensity)
        
        // Apply multi-scale enhancement (like CNN layers)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            // Apply learned enhancement
            val enhancedR = applyLearnedEnhancement(r, stats, enhancementParams, intensity)
            val enhancedG = applyLearnedEnhancement(g, stats, enhancementParams, intensity)
            val enhancedB = applyLearnedEnhancement(b, stats, enhancementParams, intensity)
            
            result[i] = Color.rgb(enhancedR, enhancedG, enhancedB)
        }
        
        return result
    }

    /**
     * Calculate image statistics for adaptive processing
     */
    private fun calculateImageStatistics(pixels: IntArray): ImageStats {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var minLuminance = 255.0
        var maxLuminance = 0.0
        var sumLuminance = 0.0
        
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luminance = r * 0.299 + g * 0.587 + b * 0.114
            
            sumR += r
            sumG += g
            sumB += b
            sumLuminance += luminance
            minLuminance = minOf(minLuminance, luminance)
            maxLuminance = maxOf(maxLuminance, luminance)
        }
        
        val avgR = sumR / pixels.size
        val avgG = sumG / pixels.size
        val avgB = sumB / pixels.size
        val avgLuminance = sumLuminance / pixels.size
        val luminanceRange = maxLuminance - minLuminance
        
        return ImageStats(
            avgR = avgR,
            avgG = avgG,
            avgB = avgB,
            avgLuminance = avgLuminance,
            minLuminance = minLuminance,
            maxLuminance = maxLuminance,
            luminanceRange = luminanceRange,
            isLowLight = avgLuminance < 80,
            isLowContrast = luminanceRange < 100
        )
    }

    /**
     * Calculate adaptive enhancement parameters
     */
    private fun calculateEnhancementParameters(stats: ImageStats, intensity: Float): EnhancementParams {
        // Use a more conservative scaling for intensity to prevent over-enhancement
        val scaledIntensity = intensity * 0.6f // Reduce sensitivity by 40%
        
        // Adaptive enhancement factors (like learned weights in a CNN)
        val baseEnhancementFactor = if (stats.isLowLight) 1.4f else 1.1f
        val enhancementFactor = baseEnhancementFactor + (scaledIntensity * 0.6f) // Reduced range
        
        val baseContrastFactor = if (stats.isLowContrast) 1.6f else 1.2f
        val contrastFactor = baseContrastFactor + (scaledIntensity * 0.4f) // Reduced range
        
        val gammaFactor = if (stats.isLowLight) {
            0.6f - (scaledIntensity * 0.2f) // 0.4 to 0.6 for low light (less aggressive)
        } else {
            0.8f - (scaledIntensity * 0.1f) // 0.7 to 0.8 for normal light (very subtle)
        }
        
        return EnhancementParams(
            enhancementFactor = enhancementFactor,
            contrastFactor = contrastFactor,
            gammaFactor = gammaFactor,
            noiseReductionStrength = 1.0f - (scaledIntensity * 0.3f) // Less noise reduction for higher intensity
        )
    }

    /**
     * Apply learned enhancement to a single color channel
     */
    private fun applyLearnedEnhancement(value: Int, stats: ImageStats, params: EnhancementParams, intensity: Float): Int {
        // Step 1: Adaptive gamma correction (like learned activation functions)
        val gammaCorrected = (255.0 * (value.toDouble() / 255.0).pow(params.gammaFactor.toDouble())).toInt()
        
        // Step 2: Local enhancement (like CNN's local receptive fields)
        val localEnhancement = (gammaCorrected * params.enhancementFactor).toInt().coerceIn(0, 255)
        
        // Step 3: Global enhancement (like CNN's global context)
        val avgValue = when {
            value == Color.red(0) -> stats.avgR
            value == Color.green(0) -> stats.avgG
            else -> stats.avgB
        }
        val globalEnhancement = ((gammaCorrected - avgValue) * params.contrastFactor + avgValue).toInt().coerceIn(0, 255)
        
        // Step 4: Adaptive blending (like CNN's learned attention mechanism)
        val attentionWeight = intensity * 0.7f + 0.3f // 0.3 to 1.0
        val blended = (localEnhancement * attentionWeight + globalEnhancement * (1 - attentionWeight)).toInt()
        
        // Step 5: Learned color correction
        val correctionFactor = calculateColorCorrectionFactor(stats, intensity)
        val corrected = (blended * correctionFactor).toInt()
        
        return corrected.coerceIn(0, 255)
    }

    /**
     * Calculate adaptive color correction factor
     */
    private fun calculateColorCorrectionFactor(stats: ImageStats, intensity: Float): Float {
        // Use scaled intensity for more conservative color correction
        val scaledIntensity = intensity * 0.6f
        
        return when {
            stats.avgLuminance < 50 -> 1.3f + (scaledIntensity * 0.4f) // Reduced correction for very dark images
            stats.avgLuminance < 100 -> 1.2f + (scaledIntensity * 0.3f) // Reduced correction for dark images
            else -> 1.05f + (scaledIntensity * 0.2f) // Very light correction for normal images
        }
    }

    /**
     * Data class for image statistics
     */
    private data class ImageStats(
        val avgR: Double,
        val avgG: Double,
        val avgB: Double,
        val avgLuminance: Double,
        val minLuminance: Double,
        val maxLuminance: Double,
        val luminanceRange: Double,
        val isLowLight: Boolean,
        val isLowContrast: Boolean
    )

    /**
     * Data class for enhancement parameters
     */
    private data class EnhancementParams(
        val enhancementFactor: Float,
        val contrastFactor: Float,
        val gammaFactor: Float,
        val noiseReductionStrength: Float
    )
} 