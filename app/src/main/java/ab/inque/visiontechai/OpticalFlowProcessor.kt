package ab.inque.visiontechai

import ab.inque.robotics.vision.flow.OpticalFlowNative


import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.*

class OpticalFlowProcessor(private val context: Context) {
    companion object {
        private const val TAG = "OpticalFlowProcessor"
        private const val MIN_FLOW_MAGNITUDE = 1.0f
        private const val MAX_FLOW_MAGNITUDE = 20.0f
        private const val MIN_GRADIENT_MAGNITUDE = 3.0f
        private const val MAX_FLOW_VELOCITY = 20.0f
        private const val SMALL_WINDOW_SIZE = 3
    }

    private var previousGray: FloatArray? = null
    private var previousSmallW: Int = 0
    private var previousSmallH: Int = 0

    fun processOpticalFlow(currentFrame: Bitmap, params: ProcessorParams): Bitmap {
        try {
            val native_ = OpticalFlowNative.processOpticalFlow(
                currentFrame,
                params.opticalFlowPixelation,
                params.opticalFlowIntensity,
                params.opticalFlowSensitivity
            )
            if (native_ != null) return native_
        } catch (e: Exception) {
            Log.w(TAG, "Native optical flow failed, using Kotlin fallback: ${e.message}")
        }
        return processOpticalFlowKotlin(currentFrame, params)
    }

    private fun processOpticalFlowKotlin(currentFrame: Bitmap, params: ProcessorParams): Bitmap {
        try {
            val w = currentFrame.width
            val h = currentFrame.height
            val downscale = params.opticalFlowPixelation.coerceIn(2, 16)
            val intensity = params.opticalFlowIntensity.coerceIn(0f, 1f)
            Log.d(TAG, "Processing optical flow for ${w}x${h} (px=$downscale, int=${"%.0f".format(intensity * 100)}%)")

            val smallW = (w / downscale).coerceAtLeast(8)
            val smallH = (h / downscale).coerceAtLeast(8)
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

            val greyBase = toGreyscaleBitmap(currentFrame)

            if (flowGrid != null && intensity > 0f) {
                val overlay = createHSVFlowOverlay(flowGrid, smallW, smallH, w, h, intensity)
                val canvas = Canvas(greyBase)
                canvas.drawBitmap(overlay, 0f, 0f, null)
                overlay.recycle()
            }

            return greyBase
        } catch (e: Exception) {
            Log.e(TAG, "Optical flow processing failed: ${e.message}")
            e.printStackTrace()
            return toGreyscaleBitmap(currentFrame)
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

    private fun toGreyscaleBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * HSV color-wheel flow overlay: hue = direction angle, brightness/alpha = magnitude.
     * Pixels with motion below the threshold are fully transparent so the greyscale
     * camera image shows through. [intensity] (0–1) scales the overlay alpha.
     */
    private fun createHSVFlowOverlay(
        flowGrid: Array<FloatArray>,
        smallW: Int,
        smallH: Int,
        outW: Int,
        outH: Int,
        intensity: Float
    ): Bitmap {
        val uFlat = flowGrid[0]
        val vFlat = flowGrid[1]
        val smallPixels = IntArray(smallW * smallH)
        val hsv = floatArrayOf(0f, 1f, 1f)
        val alphaMin = (100 * intensity).toInt()
        val alphaMax = (255 * intensity).toInt()

        for (y in 0 until smallH) {
            for (x in 0 until smallW) {
                val idx = y * smallW + x
                val u = uFlat[idx]
                val v = vFlat[idx]
                val mag = sqrt(u * u + v * v)

                if (mag < MIN_FLOW_MAGNITUDE) {
                    smallPixels[idx] = Color.TRANSPARENT
                    continue
                }

                val normMag = (mag / MAX_FLOW_MAGNITUDE).coerceIn(0f, 1f)
                val angle = atan2(v, u)
                hsv[0] = ((Math.toDegrees(angle.toDouble()) + 360.0) % 360.0).toFloat()
                hsv[1] = 0.85f + 0.15f * normMag
                hsv[2] = 0.7f + 0.3f * normMag
                val rgb = Color.HSVToColor(hsv)

                val alpha = (alphaMin + (alphaMax - alphaMin) * normMag).toInt().coerceIn(0, 255)
                smallPixels[idx] = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            }
        }

        val smallBmp = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        smallBmp.setPixels(smallPixels, 0, smallW, 0, 0, smallW, smallH)
        val outBmp = Bitmap.createScaledBitmap(smallBmp, outW, outH, true)
        if (smallBmp != outBmp) smallBmp.recycle()
        return outBmp
    }

    fun clear() {
        previousGray = null
        previousSmallW = 0
        previousSmallH = 0
        try { OpticalFlowNative.clear() } catch (_: Exception) {}
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
