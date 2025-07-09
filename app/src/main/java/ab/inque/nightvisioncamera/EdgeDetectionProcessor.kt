package ab.inque.nightvisioncamera

import android.graphics.*
import android.util.Log
import kotlin.math.sqrt

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
            
            val edgePixels = IntArray(width * height)
            
            // Apply Sobel edge detection with sensitivity control
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val centerPixel = pixels[y * width + x]
                    val centerGray = (Color.red(centerPixel) + Color.green(centerPixel) + Color.blue(centerPixel)) / 3
                    
                    // Sobel operators
                    var gx = 0
                    var gy = 0
                    
                    // Apply Sobel kernels
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val neighborPixel = pixels[(y + dy) * width + (x + dx)]
                            val neighborGray = (Color.red(neighborPixel) + Color.green(neighborPixel) + Color.blue(neighborPixel)) / 3
                            
                            if (dx == 0 && dy == 0) continue
                            
                            // Sobel X kernel: [-1, 0, 1; -2, 0, 2; -1, 0, 1]
                            val sobelX = when {
                                dy == -1 && dx == -1 -> -1
                                dy == -1 && dx == 0 -> -2
                                dy == -1 && dx == 1 -> -1
                                dy == 0 && dx == -1 -> 0
                                dy == 0 && dx == 0 -> 0
                                dy == 0 && dx == 1 -> 0
                                dy == 1 && dx == -1 -> 1
                                dy == 1 && dx == 0 -> 2
                                dy == 1 && dx == 1 -> 1
                                else -> 0
                            }
                            
                            // Sobel Y kernel: [-1, -2, -1; 0, 0, 0; 1, 2, 1]
                            val sobelY = when {
                                dy == -1 && dx == -1 -> -1
                                dy == -1 && dx == 0 -> -2
                                dy == -1 && dx == 1 -> -1
                                dy == 0 && dx == -1 -> 0
                                dy == 0 && dx == 0 -> 0
                                dy == 0 && dx == 1 -> 0
                                dy == 1 && dx == -1 -> 1
                                dy == 1 && dx == 0 -> 2
                                dy == 1 && dx == 1 -> 1
                                else -> 0
                            }
                            
                            gx += neighborGray * sobelX
                            gy += neighborGray * sobelY
                        }
                    }
                    
                    // Calculate gradient magnitude
                    val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                    
                    // Apply sensitivity threshold
                    val threshold = (255 * sensitivity).toInt()
                    val edgeValue = if (magnitude > threshold) 0 else 255 // Black edges on white background
                    
                    edgePixels[y * width + x] = Color.rgb(edgeValue, edgeValue, edgeValue)
                }
            }
            
            edgeBitmap.setPixels(edgePixels, 0, width, 0, 0, width, height)
            
            Log.d(TAG, "Edge detection completed with sensitivity: $sensitivity")
            return edgeBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in edge detection: ${e.message}")
            e.printStackTrace()
            return bitmap // Return original if processing fails
        }
    }
} 