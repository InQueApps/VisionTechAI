package ab.inque.visiontechai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * HD scale (upsample/downsample) using bicubic + unsharp only (no ML).
 * Core implemented in C++ (ResampleNative) when available for speed.
 */
class HDImageProcessor(private val context: Context) {

    companion object {
        private const val TAG = "HDImageProcessor"
        private const val MIN_SCALE = 0.01f
        private const val MAX_SCALE = 4.0f
        private const val CENTER_SCALE = 1.0f
    }

    fun processImage(bitmap: Bitmap, sliderValue: Int): Bitmap {
        try {
            val scale = convertSliderToScale(sliderValue)
            if (scale == CENTER_SCALE) return bitmap
            return if (scale < CENTER_SCALE) {
                ImageResampleUtils.bicubicDownsample(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(bitmap.width)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(bitmap.height)
                val up = ImageResampleUtils.bicubicUpsample(bitmap, targetW, targetH)
                ImageResampleUtils.unsharpMask(up, radius = 1, amount = 0.35f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "HD processing failed: ${e.message}")
            return bitmap
        }
    }

    private fun convertSliderToScale(sliderValue: Int): Float {
        return when {
            sliderValue < 50 -> {
                val normalized = sliderValue / 50f
                CENTER_SCALE - (CENTER_SCALE - MIN_SCALE) * (1f - normalized)
            }
            sliderValue > 50 -> {
                val normalized = (sliderValue - 50) / 50f
                CENTER_SCALE + (MAX_SCALE - CENTER_SCALE) * normalized
            }
            else -> CENTER_SCALE
        }
    }
}
