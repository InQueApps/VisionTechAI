package ab.inque.visiontechai

import ab.inque.robotics.video.VideoNative
import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy

object ImageUtils {
    private const val TAG = "ImageUtils"

    /** Rotate bitmap to upright display orientation; swaps dimensions for 90°/270°. */
    fun rotateToUpright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Convert YUV ImageProxy to RGB Bitmap using each plane's row/pixel stride.
     * The Y plane alone is not NV21, so YuvImage on planes[0] is never used.
     */
    fun yuvToBitmap(image: ImageProxy): Bitmap? {
        return try {
            tryManualYuvConversion(image)
        } catch (e: Exception) {
            Log.e(TAG, "YUV to bitmap failed: ${e.message}")
            null
        }
    }

    private fun rotateAndRecycle(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val rotated = rotateToUpright(bitmap, rotation)
        if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return rotated
    }

    /**
     * YUV_420_888 -> RGB using correct rowStride/pixelStride.
     * Clamps to plane dimensions so we never read past buffer (avoids black bar on right).
     */
    private fun tryManualYuvConversion(image: ImageProxy): Bitmap? {
        try {
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            yBuffer.rewind()
            uBuffer.rewind()
            vBuffer.rewind()
            val yArray = ByteArray(yBuffer.remaining())
            val uArray = ByteArray(uBuffer.remaining())
            val vArray = ByteArray(vBuffer.remaining())
            yBuffer.get(yArray)
            uBuffer.get(uArray)
            vBuffer.get(vArray)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            var pixelIndex = 0
            for (y in 0 until height) {
                val uvRow = y / 2
                for (x in 0 until width) {
                    val yIndex = y * yRowStride + x * yPixelStride
                    val yValue = if (yIndex in yArray.indices) yArray[yIndex].toInt() and 0xFF else 0
                    val uvCol = x / 2
                    val uIndex = uvRow * uRowStride + uvCol * uPixelStride
                    val vIndex = uvRow * vRowStride + uvCol * vPixelStride
                    val uValue = if (uIndex in uArray.indices) uArray[uIndex].toInt() and 0xFF else 128
                    val vValue = if (vIndex in vArray.indices) vArray[vIndex].toInt() and 0xFF else 128
                    val r = (yValue + 1.402 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val g = (yValue - 0.344 * (uValue - 128) - 0.714 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val b = (yValue + 1.772 * (uValue - 128)).toInt().coerceIn(0, 255)
                    pixels[pixelIndex++] = Color.rgb(r, g, b)
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return rotateAndRecycle(bitmap, rotation)
        } catch (e: Exception) {
            Log.e(TAG, "Manual YUV conversion failed: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    fun flipBitmapHorizontally(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** Single-pass RGB histograms (one getPixels, no extra channel arrays). */
    fun calculateHistograms(bitmap: Bitmap): Triple<IntArray, IntArray, IntArray> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        for (i in pixels.indices) {
            r[Color.red(pixels[i])]++
            g[Color.green(pixels[i])]++
            b[Color.blue(pixels[i])]++
        }
        return Triple(r, g, b)
    }

    private fun calculateCDF(histogram: IntArray): FloatArray {
        val cdf = FloatArray(256)
        cdf[0] = histogram[0].toFloat()
        for (i in 1 until 256) cdf[i] = cdf[i - 1] + histogram[i]
        return cdf
    }

    /**
     * Histogram equalization with intensity blend (0 = no change, 1 = full equalization).
     */
    fun histogramEqualizationRGB(bitmap: Bitmap, intensity: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val redHistogram = IntArray(256)
        val greenHistogram = IntArray(256)
        val blueHistogram = IntArray(256)
        for (i in pixels.indices) {
            redHistogram[Color.red(pixels[i])]++
            greenHistogram[Color.green(pixels[i])]++
            blueHistogram[Color.blue(pixels[i])]++
        }
        val redCdf = calculateCDF(redHistogram)
        val greenCdf = calculateCDF(greenHistogram)
        val blueCdf = calculateCDF(blueHistogram)
        val totalPixels = pixels.size.toFloat()
        val redCdfMin = redCdf.firstOrNull { it > 0 } ?: 0f
        val greenCdfMin = greenCdf.firstOrNull { it > 0 } ?: 0f
        val blueCdfMin = blueCdf.firstOrNull { it > 0 } ?: 0f
        val equalizedPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            equalizedPixels[i] = Color.rgb(
                applyEqualizationWithIntensity(r, redCdf, totalPixels, redCdfMin, intensity),
                applyEqualizationWithIntensity(g, greenCdf, totalPixels, greenCdfMin, intensity),
                applyEqualizationWithIntensity(b, blueCdf, totalPixels, blueCdfMin, intensity)
            )
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(equalizedPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun applyEqualizationWithIntensity(
        originalValue: Int,
        cdf: FloatArray,
        totalPixels: Float,
        cdfMin: Float,
        intensity: Float
    ): Int {
        if (totalPixels <= 0f) return originalValue
        val normalizedCdf = if (totalPixels - cdfMin > 0) {
            (cdf[originalValue] - cdfMin) / (totalPixels - cdfMin)
        } else 0f
        val equalizedValue = (normalizedCdf * 255).toInt()
        val blended = (originalValue * (1 - intensity) + equalizedValue * intensity).toInt()
        return blended.coerceIn(0, 255)
    }

    /**
     * Convert ARGB bitmap to I420 (YUV 4:2:0 planar: Y, U, V).
     * Used for feeding MediaCodec video encoder. Caller must pass a ByteArray of size width*height*3/2.
     */
    fun bitmapToI420(bitmap: Bitmap, outI420: ByteArray) {
        val w = bitmap.width
        val h = bitmap.height
        require(outI420.size >= w * h * 3 / 2) { "I420 buffer too small" }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (VideoNative.isAvailable() && VideoNative.argbToI420(pixels, w, h, outI420)) {
            return
        }
        val ySize = w * h
        val uvSize = (w / 2) * (h / 2)
        var yIdx = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val p = pixels[row * w + col]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8).coerceIn(0, 255)
                outI420[yIdx++] = y.toByte()
            }
        }
        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                val p = pixels[row * w + col]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8).coerceIn(0, 255)
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8).coerceIn(0, 255)
                val uvIdx = (row / 2) * (w / 2) + (col / 2)
                outI420[ySize + uvIdx] = u.toByte()
                outI420[ySize + uvSize + uvIdx] = v.toByte()
            }
        }
    }

    /**
     * Apply RGB channel gains (1.0 = no change).
     */
    fun applyColorAdjustment(bitmap: Bitmap, params: ProcessorParams): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val adjusted = IntArray(pixels.size)
        for (i in pixels.indices) {
            val r = (Color.red(pixels[i]) * params.redAdjustment).toInt().coerceIn(0, 255)
            val g = (Color.green(pixels[i]) * params.greenAdjustment).toInt().coerceIn(0, 255)
            val b = (Color.blue(pixels[i]) * params.blueAdjustment).toInt().coerceIn(0, 255)
            adjusted[i] = Color.argb(Color.alpha(pixels[i]), r, g, b)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(adjusted, 0, width, 0, 0, width, height)
        return result
    }
}
