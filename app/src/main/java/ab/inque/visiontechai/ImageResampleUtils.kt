package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * High-quality resampling and sharpening for HD (algorithm-only).
 * Uses native C++ when available for speed; Kotlin fallback otherwise.
 */
object ImageResampleUtils {

    fun bicubicUpsample(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val inPixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(inPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val outPixels = try {
            ResampleNative.bicubicResample(inPixels, bitmap.width, bitmap.height, newWidth, newHeight)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
        if (outPixels != null) {
            val out = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            out.setPixels(outPixels, 0, newWidth, 0, 0, newWidth, newHeight)
            return out
        }
        return bicubicUpsampleKotlin(bitmap, newWidth, newHeight)
    }

    fun bicubicDownsample(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        return bicubicUpsample(bitmap, newWidth, newHeight)
    }

    fun unsharpMask(bitmap: Bitmap, radius: Int = 1, amount: Float = 0.35f): Bitmap {
        if (radius < 1 || amount <= 0f) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        try {
            ResampleNative.unsharpMask(pixels, w, h, radius, amount)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        } catch (_: UnsatisfiedLinkError) {
            return unsharpMaskKotlin(bitmap, radius, amount)
        }
    }

    // --- Kotlin fallback ---

    private fun cubicWeight(x: Double): Double {
        val ax = abs(x)
        return when {
            ax <= 1 -> 1.5 * ax * ax * ax - 2.5 * ax * ax + 1
            ax <= 2 -> -0.5 * ax * ax * ax + 2.5 * ax * ax - 4 * ax + 2
            else -> 0.0
        }
    }

    private fun bicubicSample(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val cx = x.toInt().coerceIn(0, width - 1)
        val cy = y.toInt().coerceIn(0, height - 1)
        var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
        for (jy in -1..2) {
            for (jx in -1..2) {
                val px = (cx + jx).coerceIn(0, width - 1)
                val py = (cy + jy).coerceIn(0, height - 1)
                val w = cubicWeight((x - (cx + jx)).toDouble()) * cubicWeight((y - (cy + jy)).toDouble())
                val p = pixels[py * width + px]
                r += Color.red(p) * w; g += Color.green(p) * w; b += Color.blue(p) * w
                wSum += w
            }
        }
        if (abs(wSum) < 1e-9) return pixels[cy * width + cx]
        return Color.rgb(
            (r / wSum).toInt().coerceIn(0, 255),
            (g / wSum).toInt().coerceIn(0, 255),
            (b / wSum).toInt().coerceIn(0, 255)
        )
    }

    private fun bicubicUpsampleKotlin(bitmap: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val inputPixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(inputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val scaleX = (bitmap.width - 1).toFloat() / (newWidth - 1).coerceAtLeast(1)
        val scaleY = (bitmap.height - 1).toFloat() / (newHeight - 1).coerceAtLeast(1)
        val outputPixels = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                outputPixels[y * newWidth + x] = bicubicSample(
                    inputPixels, bitmap.width, bitmap.height, x * scaleX, y * scaleY
                )
            }
        }
        return Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, newWidth, 0, 0, newWidth, newHeight)
        }
    }

    private fun unsharpMaskKotlin(bitmap: Bitmap, radius: Int, amount: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = radius.coerceAtMost(3)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0; var n = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val p = pixels[ny * w + nx]
                    sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p)
                    n++
                }
                val br = sumR / n; val bg = sumG / n; val bb = sumB / n
                val p = pixels[y * w + x]
                val or = Color.red(p); val og = Color.green(p); val ob = Color.blue(p)
                out[y * w + x] = Color.rgb(
                    (or + (or - br) * amount).toInt().coerceIn(0, 255),
                    (og + (og - bg) * amount).toInt().coerceIn(0, 255),
                    (ob + (ob - bb) * amount).toInt().coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }
}
