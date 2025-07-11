package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.*

class OpticalFlowProcessor(private val context: Context) {
    companion object {
        private const val TAG = "OpticalFlowProcessor"
        private const val GRID_SIZE = 15 // Increased grid size for fewer arrows
        private const val ARROW_LENGTH = 15f // Reduced arrow length
        private const val ARROW_THICKNESS = 1f // Reduced thickness
        private const val MIN_FLOW_MAGNITUDE = 2.0f // Increased minimum flow magnitude
        private const val MAX_FLOW_MAGNITUDE = 30.0f // Reduced max flow magnitude
        private const val MIN_GRADIENT_MAGNITUDE = 5.0f // Minimum gradient magnitude for reliable flow
        private const val MAX_FLOW_VELOCITY = 20.0f // Maximum allowed flow velocity
    }

    private var previousFrame: Bitmap? = null
    private var previousGray: FloatArray? = null
    private var currentGray: FloatArray? = null

    fun processOpticalFlow(currentFrame: Bitmap): Bitmap {
        try {
            Log.d(TAG, "Processing optical flow for ${currentFrame.width}x${currentFrame.height}")
            
            // Convert current frame to grayscale
            val currentGrayArray = convertToGrayscale(currentFrame)
            
            // If we have a previous frame, calculate optical flow
            val flowOverlay = if (previousGray != null && previousGray!!.size == currentGrayArray.size) {
                calculateOpticalFlow(previousGray!!, currentGrayArray, currentFrame.width, currentFrame.height)
            } else {
                Log.d(TAG, "No previous frame available, skipping flow calculation")
                null
            }
            
            // Update previous frame
            previousGray = currentGrayArray
            previousFrame = currentFrame.copy(currentFrame.config ?: Bitmap.Config.ARGB_8888, false)
            
            // Create overlay bitmap with flow arrows
            return if (flowOverlay != null) {
                createOverlayBitmap(currentFrame, flowOverlay)
            } else {
                currentFrame
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Optical flow processing failed: ${e.message}")
            e.printStackTrace()
            return currentFrame
        }
    }

    private fun convertToGrayscale(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val grayArray = FloatArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            // Convert to grayscale using luminance formula
            grayArray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }
        
        return grayArray
    }

    private fun calculateOpticalFlow(
        prevGray: FloatArray, 
        currGray: FloatArray, 
        width: Int, 
        height: Int
    ): List<FlowVector> {
        val flowVectors = mutableListOf<FlowVector>()
        
        // Calculate flow at grid points
        for (y in GRID_SIZE until height - GRID_SIZE step GRID_SIZE) {
            for (x in GRID_SIZE until width - GRID_SIZE step GRID_SIZE) {
                val flow = calculateFlowAtPoint(prevGray, currGray, x, y, width, height)
                if (isValidFlow(flow)) {
                    flowVectors.add(flow)
                }
            }
        }
        
        Log.d(TAG, "Calculated ${flowVectors.size} valid flow vectors")
        return flowVectors
    }

    private fun isValidFlow(flow: FlowVector): Boolean {
        val magnitude = flow.magnitude
        return magnitude > MIN_FLOW_MAGNITUDE && 
               magnitude < MAX_FLOW_VELOCITY &&
               abs(flow.u) < MAX_FLOW_VELOCITY &&
               abs(flow.v) < MAX_FLOW_VELOCITY
    }

    private fun calculateFlowAtPoint(
        prevGray: FloatArray, 
        currGray: FloatArray, 
        x: Int, 
        y: Int, 
        width: Int, 
        height: Int
    ): FlowVector {
        // Improved Lucas-Kanade optical flow implementation
        val windowSize = 7 // Increased window size for better stability
        var sumIx = 0.0
        var sumIy = 0.0
        var sumIt = 0.0
        var sumIx2 = 0.0
        var sumIy2 = 0.0
        var sumIxIy = 0.0
        var sumIxIt = 0.0
        var sumIyIt = 0.0
        var validPixels = 0
        
        // Calculate spatial and temporal gradients
        for (dy in -windowSize..windowSize) {
            for (dx in -windowSize..windowSize) {
                val px = x + dx
                val py = y + dy
                
                if (px > 0 && px < width - 1 && py > 0 && py < height - 1) {
                    val idx = py * width + px
                    
                    // Spatial gradients (Ix, Iy) using Sobel operator for better accuracy
                    val ix = (currGray[idx + 1] - currGray[idx - 1]) / 2.0
                    val iy = (currGray[idx + width] - currGray[idx - width]) / 2.0
                    
                    // Temporal gradient (It)
                    val it = currGray[idx] - prevGray[idx]
                    
                    // Check if gradients are strong enough
                    val gradientMagnitude = sqrt(ix * ix + iy * iy)
                    if (gradientMagnitude > MIN_GRADIENT_MAGNITUDE) {
                        // Accumulate sums for least squares solution
                        sumIx += ix
                        sumIy += iy
                        sumIt += it
                        sumIx2 += ix * ix
                        sumIy2 += iy * iy
                        sumIxIy += ix * iy
                        sumIxIt += ix * it
                        sumIyIt += iy * it
                        validPixels++
                    }
                }
            }
        }
        
        // Only calculate flow if we have enough valid pixels
        if (validPixels < 10) {
            return FlowVector(x.toFloat(), y.toFloat(), 0f, 0f)
        }
        
        // Solve for optical flow using least squares
        val det = sumIx2 * sumIy2 - sumIxIy * sumIxIy
        val u: Double
        val v: Double
        
        if (abs(det) > 1e-8) { // Increased threshold for numerical stability
            u = (sumIy2 * sumIxIt - sumIxIy * sumIyIt) / det
            v = (sumIx2 * sumIyIt - sumIxIy * sumIxIt) / det
            
            // Apply outlier rejection
            if (abs(u) > MAX_FLOW_VELOCITY || abs(v) > MAX_FLOW_VELOCITY) {
                return FlowVector(x.toFloat(), y.toFloat(), 0f, 0f)
            }
        } else {
            u = 0.0
            v = 0.0
        }
        
        return FlowVector(x.toFloat(), y.toFloat(), u.toFloat(), v.toFloat())
    }

    private fun createOverlayBitmap(originalBitmap: Bitmap, flowVectors: List<FlowVector>): Bitmap {
        val overlayBitmap = originalBitmap.copy(originalBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)
        
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = ARROW_THICKNESS
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        // Draw flow vectors as arrows
        for (flow in flowVectors) {
            val magnitude = sqrt(flow.u * flow.u + flow.v * flow.v)
            val normalizedMagnitude = (magnitude / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f)
            
            // Color based on magnitude (red for high flow, green for low flow)
            val color = when {
                normalizedMagnitude > 0.7f -> Color.RED
                normalizedMagnitude > 0.4f -> Color.YELLOW
                else -> Color.GREEN
            }
            paint.color = color
            
            // Draw arrow
            drawArrow(canvas, paint, flow.x, flow.y, flow.u, flow.v, magnitude)
        }
        
        return overlayBitmap
    }

    private fun drawArrow(canvas: Canvas, paint: Paint, x: Float, y: Float, u: Float, v: Float, magnitude: Float) {
        val arrowLength = (ARROW_LENGTH * (magnitude / MAX_FLOW_MAGNITUDE)).coerceAtLeast(3f)
        
        // Normalize direction vector
        val length = sqrt(u * u + v * v)
        if (length < 0.1f) return
        
        val dx = u / length * arrowLength
        val dy = v / length * arrowLength
        
        // Draw arrow line
        canvas.drawLine(x, y, x + dx, y + dy, paint)
        
        // Draw arrow head
        val angle = atan2(dy, dx)
        val headLength = arrowLength * 0.25f // Smaller arrow head
        
        val headX1 = x + dx - headLength * cos(angle - 0.5f)
        val headY1 = y + dy - headLength * sin(angle - 0.5f)
        val headX2 = x + dx - headLength * cos(angle + 0.5f)
        val headY2 = y + dy - headLength * sin(angle + 0.5f)
        
        canvas.drawLine(x + dx, y + dy, headX1, headY1, paint)
        canvas.drawLine(x + dx, y + dy, headX2, headY2, paint)
    }

    fun clear() {
        previousFrame = null
        previousGray = null
        currentGray = null
        Log.d(TAG, "Optical flow processor cleared")
    }

    data class FlowVector(
        val x: Float,  // Position x
        val y: Float,  // Position y
        val u: Float,  // Flow in x direction
        val v: Float   // Flow in y direction
    ) {
        val magnitude: Float
            get() = sqrt(u * u + v * v)
    }
} 