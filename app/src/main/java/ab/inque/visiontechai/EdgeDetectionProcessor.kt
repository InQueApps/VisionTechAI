package ab.inque.visiontechai

import android.graphics.*
import android.util.Log
import kotlin.math.*

class EdgeDetectionProcessor {
    companion object {
        private const val TAG = "EdgeDetectionProcessor"
    }

    fun detectEdges(bitmap: Bitmap, sensitivity: Float): Bitmap {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Create a white canvas for the edge detection result
            val edgeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            edgeBitmap.eraseColor(Color.WHITE)
            
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Step 1: Apply Gaussian blur to reduce noise
            val blurredPixels = applyGaussianBlur(pixels, width, height)
            
            // Step 2: Apply Sobel edge detection
            val edgePixels = applySobelEdgeDetection(blurredPixels, width, height, sensitivity)
            
            // Step 3: Apply morphological operations to clean up edges
            val cleanedPixels = applyMorphologicalCleaning(edgePixels, width, height)
            
            edgeBitmap.setPixels(cleanedPixels, 0, width, 0, 0, width, height)
            
            Log.d(TAG, "Enhanced edge detection completed with sensitivity: $sensitivity")
            return edgeBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in edge detection: ${e.message}")
            e.printStackTrace()
            return bitmap // Return original if processing fails
        }
    }

    private fun applyGaussianBlur(pixels: IntArray, width: Int, height: Int): IntArray {
        val blurredPixels = IntArray(width * height)
        val kernel = floatArrayOf(0.0625f, 0.125f, 0.0625f, 0.125f, 0.25f, 0.125f, 0.0625f, 0.125f, 0.0625f)
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0f
                var g = 0f
                var b = 0f
                
                // Apply 3x3 Gaussian kernel
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val pixel = pixels[(y + dy) * width + (x + dx)]
                        val weight = kernel[(dy + 1) * 3 + (dx + 1)]
                        r += Color.red(pixel) * weight
                        g += Color.green(pixel) * weight
                        b += Color.blue(pixel) * weight
                    }
                }
                
                blurredPixels[y * width + x] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
            }
        }
        
        // Copy border pixels
        for (x in 0 until width) {
            blurredPixels[x] = pixels[x]
            blurredPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            blurredPixels[y * width] = pixels[y * width]
            blurredPixels[y * width + (width - 1)] = pixels[y * width + (width - 1)]
        }
        
        return blurredPixels
    }

    private fun applySobelEdgeDetection(pixels: IntArray, width: Int, height: Int, sensitivity: Float): IntArray {
        val edgePixels = IntArray(width * height)
        
        // Sobel kernels
        val sobelX = intArrayOf(-1, 0, 1, -2, 0, 2, -1, 0, 1)
        val sobelY = intArrayOf(-1, -2, -1, 0, 0, 0, 1, 2, 1)
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0
                
                // Apply Sobel operators
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val pixel = pixels[(y + dy) * width + (x + dx)]
                        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        val kernelIndex = (dy + 1) * 3 + (dx + 1)
                        
                        gx += gray * sobelX[kernelIndex]
                        gy += gray * sobelY[kernelIndex]
                    }
                }
                
                // Calculate gradient magnitude
                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                
                // Apply adaptive thresholding
                val threshold = (255 * (0.1f + sensitivity * 0.4f)).toInt()
                val edgeValue = if (magnitude > threshold) 0 else 255 // Black edges on white background
                
                edgePixels[y * width + x] = Color.rgb(edgeValue, edgeValue, edgeValue)
            }
        }
        
        // Copy border pixels
        for (x in 0 until width) {
            edgePixels[x] = Color.WHITE
            edgePixels[(height - 1) * width + x] = Color.WHITE
        }
        for (y in 0 until height) {
            edgePixels[y * width] = Color.WHITE
            edgePixels[y * width + (width - 1)] = Color.WHITE
        }
        
        return edgePixels
    }

    private fun applyMorphologicalCleaning(pixels: IntArray, width: Int, height: Int): IntArray {
        val cleanedPixels = pixels.clone()
        
        // Step 1: Remove isolated pixels (noise reduction)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centerPixel = pixels[y * width + x]
                if (centerPixel == Color.BLACK) {
                    var neighborCount = 0
                    
                    // Count black neighbors
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (pixels[(y + dy) * width + (x + dx)] == Color.BLACK) {
                                neighborCount++
                            }
                        }
                    }
                    
                    // Remove isolated pixels (less than 2 neighbors)
                    if (neighborCount < 2) {
                        cleanedPixels[y * width + x] = Color.WHITE
                    }
                }
            }
        }
        
        // Step 2: Thin edges (remove redundant pixels)
        val thinnedPixels = cleanedPixels.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centerPixel = cleanedPixels[y * width + x]
                if (centerPixel == Color.BLACK) {
                    // Check if this pixel can be removed without breaking connectivity
                    if (canRemovePixel(cleanedPixels, x, y, width)) {
                        thinnedPixels[y * width + x] = Color.WHITE
                    }
                }
            }
        }
        
        return thinnedPixels
    }

    private fun canRemovePixel(pixels: IntArray, x: Int, y: Int, width: Int): Boolean {
        // Simple connectivity check - if removing this pixel doesn't break edge continuity
        var horizontalEdges = 0
        var verticalEdges = 0
        
        // Count horizontal edges
        if (pixels[y * width + (x - 1)] == Color.BLACK) horizontalEdges++
        if (pixels[y * width + (x + 1)] == Color.BLACK) horizontalEdges++
        
        // Count vertical edges
        if (pixels[(y - 1) * width + x] == Color.BLACK) verticalEdges++
        if (pixels[(y + 1) * width + x] == Color.BLACK) verticalEdges++
        
        // Remove pixel if it's not a critical connection point
        return horizontalEdges <= 1 || verticalEdges <= 1
    }
} 