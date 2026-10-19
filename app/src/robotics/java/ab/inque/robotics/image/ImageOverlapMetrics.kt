package ab.inque.robotics.image

import kotlin.math.sqrt

/** Image overlap / registration metrics on raw ARGB pixel buffers (no Bitmap). */
object ImageOverlapMetrics {

    data class Region(val x0: Int, val y0: Int, val w: Int, val h: Int)

    private const val DARK_FALLBACK = 0x1A1A2E

    fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    fun isContentPixel(pixel: Int): Boolean {
        if ((pixel ushr 24) == 0) return false
        val rgb = pixel and 0x00FFFFFF
        return rgb != DARK_FALLBACK
    }

    fun normalizedCorrelation(
        pixelsA: IntArray,
        pixelsB: IntArray,
        width: Int,
        height: Int,
        region: Region
    ): Double {
        val xEnd = (region.x0 + region.w).coerceAtMost(width)
        val yEnd = (region.y0 + region.h).coerceAtMost(height)
        var sumA = 0.0
        var sumB = 0.0
        var sumSqA = 0.0
        var sumSqB = 0.0
        var sumAB = 0.0
        var count = 0
        for (y in region.y0 until yEnd) {
            val row = y * width
            for (x in region.x0 until xEnd) {
                val idx = row + x
                val pa = pixelsA[idx]
                val pb = pixelsB[idx]
                if (!isContentPixel(pa) || !isContentPixel(pb)) continue
                val a = luminance(pa).toDouble()
                val b = luminance(pb).toDouble()
                sumA += a
                sumB += b
                sumSqA += a * a
                sumSqB += b * b
                sumAB += a * b
                count++
            }
        }
        if (count < 16) return 0.0
        val meanA = sumA / count
        val meanB = sumB / count
        val varA = sumSqA / count - meanA * meanA
        val varB = sumSqB / count - meanB * meanB
        if (varA <= 1e-6 || varB <= 1e-6) return 1.0
        val cov = sumAB / count - meanA * meanB
        val corr = cov / sqrt(varA * varB)
        return ((corr + 1.0) * 0.5).coerceIn(0.0, 1.0)
    }

    fun ghostDelta(before: Double, after: Double): Double = after - before

    fun warpAgreementPx(fastTx: Float, fastTy: Float, refineTx: Float, refineTy: Float): Float =
        kotlin.math.abs(fastTx - refineTx) + kotlin.math.abs(fastTy - refineTy)
}
