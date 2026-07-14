package ab.inque.visiontechai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * HD scale (upsample/downsample) using bicubic + unsharp only (no ML).
 * Core implemented in C++ (ResampleNative) when available for speed.
 */
class HDImageProcessor(private val context: Context) {

    fun processImage(bitmap: Bitmap, sliderValue: Int): Bitmap {
        try {
            val scale = HdScaleMapper.sliderToScale(sliderValue)
            if (scale == 1.0f) return bitmap
            return if (scale < 1.0f) {
                ImageResampleUtils.bicubicDownsample(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(bitmap.width)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(bitmap.height)
                val up = ImageResampleUtils.bicubicUpsample(bitmap, targetW, targetH)
                val radius = when {
                    scale >= 3.0f -> 3
                    scale >= 2.0f -> 2
                    else -> 1
                }
                val amount = (0.3f + (scale - 1f) * 0.25f).coerceIn(0.3f, 1.0f)
                var result = ImageResampleUtils.unsharpMask(up, radius = radius, amount = amount)
                if (scale >= 2.5f) {
                    result = ImageResampleUtils.unsharpMask(result, radius = 1, amount = 0.25f)
                }
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "HD processing failed: ${e.message}")
            return bitmap
        }
    }

    companion object {
        private const val TAG = "HDImageProcessor"
    }
}
