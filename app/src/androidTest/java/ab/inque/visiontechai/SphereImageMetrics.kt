package ab.inque.visiontechai

import ab.inque.robotics.image.ImageOverlapMetrics


import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
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

    fun equirectOverlapCorrelation(bmpA: Bitmap, bmpB: Bitmap, region: Region): Double {
        val w = bmpA.width
        val h = bmpA.height
        val pixelsA = IntArray(w * h)
        val pixelsB = IntArray(w * h)
        bmpA.getPixels(pixelsA, 0, w, 0, 0, w, h)
        bmpB.getPixels(pixelsB, 0, w, 0, 0, w, h)
        return ImageOverlapMetrics.normalizedCorrelation(
            pixelsA, pixelsB, w, h,
            ImageOverlapMetrics.Region(region.x0, region.y0, region.w, region.h)
        )
    }

    fun fastRefineGhostDelta(ghostFast: Double, ghostRefined: Double): Double =
        ImageOverlapMetrics.ghostDelta(ghostFast, ghostRefined)

    /**
     * Fraction of strong edge pixels that have a parallel duplicate edge within [minShift..maxShift] px.
     * Requires matching edge polarity and similar flanking luminance (double-image ghosting).
     */
    fun ghostingScore(
        bmp: Bitmap,
        region: Region,
        minShift: Int = 3,
        maxShift: Int = 12,
        edgeGradThreshold: Float = 35f
    ): Double = ghostingScore(
        bmp, region.x0, region.y0, region.w, region.h,
        minShift, maxShift, edgeGradThreshold
    )

    fun ghostingScore(
        bmp: Bitmap,
        x0: Int = 0,
        y0: Int = 0,
        w: Int = bmp.width,
        h: Int = bmp.height,
        minShift: Int = 3,
        maxShift: Int = 12,
        edgeGradThreshold: Float = 35f
    ): Double {
        val xStart = max(3, x0)
        val yStart = max(1, y0)
        val xEnd = min(bmp.width - maxShift - 3, x0 + w)
        val yEnd = min(bmp.height - 1, y0 + h)
        if (xEnd <= xStart + maxShift || yEnd <= yStart + 1) return 0.0

        var edgeCount = 0
        var ghostCount = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                if (!isContentPixel(bmp.getPixel(x, y))) continue
                val g1 = horizontalGrad(bmp, x, y)
                if (abs(g1) < edgeGradThreshold) continue
                edgeCount++

                var foundGhost = false
                for (dx in minShift..maxShift) {
                    val x2 = x + dx
                    if (!isContentPixel(bmp.getPixel(x2, y))) continue
                    val g2 = horizontalGrad(bmp, x2, y)
                    if (abs(g2) < edgeGradThreshold) continue
                    if (g1 * g2 < 0f) continue
                    val l1L = luminance(bmp.getPixel(x - 2, y))
                    val l1R = luminance(bmp.getPixel(x + 2, y))
                    val l2L = luminance(bmp.getPixel(x2 - 2, y))
                    val l2R = luminance(bmp.getPixel(x2 + 2, y))
                    if (abs(l1L - l2L) < 32f && abs(l1R - l2R) < 32f) {
                        ghostCount++
                        foundGhost = true
                        break
                    }
                }
                if (!foundGhost) {
                    for (dy in -2..2) {
                        if (dy == 0) continue
                        val y2 = y + dy
                        if (y2 < yStart || y2 >= yEnd) continue
                        for (dx in minShift..maxShift) {
                            val x2 = x + dx
                            if (!isContentPixel(bmp.getPixel(x2, y2))) continue
                            val g2 = horizontalGrad(bmp, x2, y2)
                            if (abs(g2) < edgeGradThreshold) continue
                            if (g1 * g2 < 0f) continue
                            val l1L = luminance(bmp.getPixel(x - 2, y))
                            val l1R = luminance(bmp.getPixel(x + 2, y))
                            val l2L = luminance(bmp.getPixel(x2 - 2, y2))
                            val l2R = luminance(bmp.getPixel(x2 + 2, y2))
                            if (abs(l1L - l2L) < 32f && abs(l1R - l2R) < 32f) {
                                ghostCount++
                                foundGhost = true
                                break
                            }
                        }
                        if (foundGhost) break
                    }
                }
            }
        }
        return if (edgeCount > 0) ghostCount.toDouble() / edgeCount else 0.0
    }

    private fun horizontalGrad(bmp: Bitmap, x: Int, y: Int): Float =
        luminance(bmp.getPixel(x + 1, y)) - luminance(bmp.getPixel(x - 1, y))

    /**
     * Ghost score restricted to a known misalignment offset (e.g. pixel shift in overlap tests).
     * Ignores unrelated periodic edges that confuse the broad [ghostingScore].
     */
    fun ghostingScoreAtOffset(
        bmp: Bitmap,
        region: Region,
        expectedDx: Int,
        tolerance: Int = 1,
        edgeGradThreshold: Float = 35f
    ): Double {
        val minShift = (expectedDx - tolerance).coerceAtLeast(1)
        val maxShift = expectedDx + tolerance
        return ghostingScore(
            bmp, region.x0, region.y0, region.w, region.h,
            minShift, maxShift, edgeGradThreshold
        )
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

    /** Horizontal band through stitched content (overlap seam band). */
    fun overlapBandRegion(content: Region, bandHeightFraction: Float = 0.35f): Region {
        val bandH = (content.h * bandHeightFraction).toInt().coerceAtLeast(8)
        val y0 = content.y0 + (content.h - bandH) / 2
        return Region(content.x0, y0, content.w, bandH)
    }

    fun luminanceStdDev(bmp: Bitmap, region: Region): Double {
        val values = mutableListOf<Double>()
        val xEnd = min(bmp.width, region.x0 + region.w)
        val yEnd = min(bmp.height, region.y0 + region.h)
        for (y in region.y0 until yEnd) {
            for (x in region.x0 until xEnd) {
                val p = bmp.getPixel(x, y)
                if (!isContentPixel(p)) continue
                values.add(luminance(p).toDouble())
            }
        }
        if (values.size < 4) return 0.0
        val mean = values.sum() / values.size
        val varSum = values.sumOf { (it - mean) * (it - mean) }
        return kotlin.math.sqrt(varSum / values.size)
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
