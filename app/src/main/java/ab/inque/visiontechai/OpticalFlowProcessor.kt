package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.*

class OpticalFlowProcessor(private val context: Context) {
    companion object {
        private const val TAG = "OpticalFlowProcessor"
        private const val DOWNSCALE_FACTOR = 8
        private const val MIN_FLOW_MAGNITUDE = 1.0f
        private const val MAX_FLOW_MAGNITUDE = 20.0f
        private const val MIN_GRADIENT_MAGNITUDE = 3.0f
        private const val MAX_FLOW_VELOCITY = 20.0f
        private const val GREY_LEVEL = 128
        private const val SMALL_WINDOW_SIZE = 3
    }

    private var previousGray: FloatArray? = null
    private var previousSmallW: Int = 0
    private var previousSmallH: Int = 0

    fun processOpticalFlow(currentFrame: Bitmap): Bitmap {
        try {
            val w = currentFrame.width
            val h = currentFrame.height
            Log.d(TAG, "Processing optical flow for ${w}x${h}")

            val smallW = (w / DOWNSCALE_FACTOR).coerceAtLeast(8)
            val smallH = (h / DOWNSCALE_FACTOR).coerceAtLeast(8)
            val smallBitmap = Bitmap.createScaledBitmap(currentFrame, smallW, smallH, true)
            val currentGray = convertToGrayscale(smallBitmap)
            if (smallBitmap != currentFrame) smallBitmap.recycle()

            val flowGrid: Array<FloatArray>? = if (previousGray != null && previousSmallW == smallW && previousSmallH == smallH) {
                calculateOpticalFlowGrid(previousGray!!, currentGray, smallW, smallH)
            } else {
                Log.d(TAG, "No previous frame available, skipping flow calculation")
                null
            }

            previousGray = currentGray
            previousSmallW = smallW
            previousSmallH = smallH

            return if (flowGrid != null) {
                createSquaresBitmap(flowGrid, smallW, smallH, w, h)
            } else {
                createGreyBitmap(w, h)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Optical flow processing failed: ${e.message}")
            e.printStackTrace()
            return createGreyBitmap(currentFrame.width, currentFrame.height)
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

    /** Returns u and v grids for small image (flow at each cell). */
    private fun calculateOpticalFlowGrid(
        prevGray: FloatArray,
        currGray: FloatArray,
        width: Int,
        height: Int
    ): Array<FloatArray> {
        val uGrid = Array(height) { FloatArray(width) }
        val vGrid = Array(height) { FloatArray(width) }
        val margin = SMALL_WINDOW_SIZE + 1
        var validCount = 0
        for (y in margin until height - margin) {
            for (x in margin until width - margin) {
                val flow = calculateFlowAtPoint(prevGray, currGray, x, y, width, height)
                uGrid[y][x] = flow.u
                vGrid[y][x] = flow.v
                if (isValidFlow(flow)) validCount++
            }
        }
        val uFlat = FloatArray(width * height)
        val vFlat = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                uFlat[idx] = uGrid[y][x]
                vFlat[idx] = vGrid[y][x]
            }
        }
        Log.d(TAG, "Calculated flow grid ${width}x${height}, $validCount valid cells")
        return arrayOf(uFlat, vFlat)
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
        val windowSize = SMALL_WINDOW_SIZE
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

    /** Creates a solid grey bitmap (used when no previous frame or as base). */
    private fun createGreyBitmap(width: Int, height: Int): Bitmap {
        val grey = Color.rgb(GREY_LEVEL, GREY_LEVEL, GREY_LEVEL)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(grey)
        return bmp
    }

    /**
     * Build low-res bitmap (one pixel per flow cell), then upscale to output size so flow shows as squares.
     * Direction colors: red=left, blue=right, green=top, black=bottom; magnitude scales intensity.
     */
    private fun createSquaresBitmap(
        flowGrid: Array<FloatArray>,
        smallW: Int,
        smallH: Int,
        outW: Int,
        outH: Int
    ): Bitmap {
        val uFlat = flowGrid[0]
        val vFlat = flowGrid[1]
        val smallPixels = IntArray(smallW * smallH)
        val strength = 90f
        for (y in 0 until smallH) {
            for (x in 0 until smallW) {
                val idx = y * smallW + x
                val u = uFlat[idx]
                val v = vFlat[idx]
                val mag = sqrt(u * u + v * v)
                val t = (mag / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f)
                val leftContrib = if (u < 0) t * (-u / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f) else 0f
                val rightContrib = if (u > 0) t * (u / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f) else 0f
                val topContrib = if (v < 0) t * (-v / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f) else 0f
                val bottomContrib = if (v > 0) t * (v / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f) else 0f
                val r = (GREY_LEVEL + (leftContrib * strength).toInt() - (bottomContrib * strength).toInt()).coerceIn(0, 255)
                val g = (GREY_LEVEL + (topContrib * strength).toInt() - (bottomContrib * strength).toInt()).coerceIn(0, 255)
                val b = (GREY_LEVEL + (rightContrib * strength).toInt() - (bottomContrib * strength).toInt()).coerceIn(0, 255)
                smallPixels[idx] = Color.rgb(r, g, b)
            }
        }
        val smallBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        smallBmp.setPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        val outBmp = Bitmap.createScaledBitmap(smallBmp, outW, outH, false)
        if (smallBmp != outBmp) smallBmp.recycle()
        return outBmp
    }

    fun clear() {
        previousGray = null
        previousSmallW = 0
        previousSmallH = 0
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