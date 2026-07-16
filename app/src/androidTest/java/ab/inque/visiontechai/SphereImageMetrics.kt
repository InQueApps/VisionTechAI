package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Image-quality metrics for sphere stitch E2E tests.
 * Detects "muddy" output: low sharpness, crushed contrast, desaturation.
 */
object SphereImageMetrics {

    private const val DARK_FALLBACK = 0x1A1A2E

    data class Region(val x0: Int, val y0: Int, val w: Int, val h: Int)

    fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Variance of the discrete Laplacian — higher means sharper detail. */
    fun laplacianVariance(bmp: Bitmap, region: Region): Double =
        laplacianVariance(bmp, region.x0, region.y0, region.w, region.h)

    fun laplacianVariance(
        bmp: Bitmap,
        x0: Int = 0,
        y0: Int = 0,
        w: Int = bmp.width,
        h: Int = bmp.height
    ): Double {
        val xStart = max(1, x0)
        val yStart = max(1, y0)
        val xEnd = min(bmp.width - 1, x0 + w)
        val yEnd = min(bmp.height - 1, y0 + h)
        if (xEnd <= xStart + 1 || yEnd <= yStart + 1) return 0.0

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val p = bmp.getPixel(x, y)
                if (!isContentPixel(p)) continue
                val c = luminance(p)
                val lap = 4.0 * c -
                    luminance(bmp.getPixel(x - 1, y)) -
                    luminance(bmp.getPixel(x + 1, y)) -
                    luminance(bmp.getPixel(x, y - 1)) -
                    luminance(bmp.getPixel(x, y + 1))
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    fun meanLocalContrast(bmp: Bitmap, region: Region, radius: Int = 3): Double =
        meanLocalContrast(bmp, region.x0, region.y0, region.w, region.h, radius)

    fun meanLocalContrast(
        bmp: Bitmap,
        x0: Int = 0,
        y0: Int = 0,
        w: Int = bmp.width,
        h: Int = bmp.height,
        radius: Int = 3
    ): Double {
        val xStart = max(radius, x0)
        val yStart = max(radius, y0)
        val xEnd = min(bmp.width - radius, x0 + w)
        val yEnd = min(bmp.height - radius, y0 + h)
        if (xEnd <= xStart || yEnd <= yStart) return 0.0

        var total = 0.0
        var count = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val center = bmp.getPixel(x, y)
                if (!isContentPixel(center)) continue
                var minL = Float.MAX_VALUE
                var maxL = Float.MIN_VALUE
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val p = bmp.getPixel(x + dx, y + dy)
                        if (!isContentPixel(p)) continue
                        val l = luminance(p)
                        minL = min(minL, l)
                        maxL = max(maxL, l)
                    }
                }
                if (maxL > minL) {
                    total += (maxL - minL)
                    count++
                }
            }
        }
        return if (count > 0) total / count else 0.0
    }

    fun meanSaturation(bmp: Bitmap, region: Region): Double =
        meanSaturation(bmp, region.x0, region.y0, region.w, region.h)

    fun meanSaturation(
        bmp: Bitmap,
        x0: Int = 0,
        y0: Int = 0,
        w: Int = bmp.width,
        h: Int = bmp.height
    ): Double {
        val xStart = max(0, x0)
        val yStart = max(0, y0)
        val xEnd = min(bmp.width, x0 + w)
        val yEnd = min(bmp.height, y0 + h)

        var total = 0.0
        var count = 0
        val hsv = FloatArray(3)
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val p = bmp.getPixel(x, y)
                if (!isContentPixel(p)) continue
                Color.colorToHSV(p, hsv)
                total += hsv[1]
                count++
            }
        }
        return if (count > 0) total / count else 0.0
    }

    fun sharpnessRetentionRatio(before: Double, after: Double): Double {
        if (before <= 1e-6) return if (after <= 1e-6) 1.0 else 0.0
        return after / before
    }

    fun findContentRegion(bmp: Bitmap, margin: Int = 8): Region? {
        var minX = bmp.width
        var maxX = -1
        var minY = bmp.height
        var maxY = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (!isContentPixel(bmp.getPixel(x, y))) continue
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return null
        val x0 = min(bmp.width - 1, minX + margin)
        val y0 = min(bmp.height - 1, minY + margin)
        val x1 = max(x0 + 1, maxX - margin)
        val y1 = max(y0 + 1, maxY - margin)
        return Region(x0, y0, x1 - x0, y1 - y0)
    }

    fun requireContentRegion(bmp: Bitmap, label: String): Region {
        val region = findContentRegion(bmp)
        checkNotNull(region) { "$label: no stitched content found in bitmap" }
        return region
    }

    fun centerRegion(bmp: Bitmap, frac: Float = 0.5f): Region {
        val cw = (bmp.width * frac).toInt()
        val ch = (bmp.height * frac).toInt()
        val x0 = (bmp.width - cw) / 2
        val y0 = (bmp.height - ch) / 2
        return Region(x0, y0, cw, ch)
    }

    fun formatQuality(label: String, bmp: Bitmap, region: Region): String {
        val lap = laplacianVariance(bmp, region)
        val contrast = meanLocalContrast(bmp, region)
        val sat = meanSaturation(bmp, region)
        return "$label: lapVar=${"%.1f".format(lap)} contrast=${"%.1f".format(contrast)} sat=${"%.3f".format(sat)}"
    }

    private fun isContentPixel(pixel: Int): Boolean {
        if ((pixel ushr 24) == 0) return false
        val rgb = pixel and 0x00FFFFFF
        return rgb != DARK_FALLBACK
    }
}
