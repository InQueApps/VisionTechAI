package ab.inque.visiontechai.sphere.debug

import ab.inque.robotics.image.ImageOverlapMetrics
import ab.inque.visiontechai.SphereRefinePolicy
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Image-quality metrics for debug offline replay (mirrors androidTest [SphereImageMetrics]).
 */
object SphereStitchQualityEvaluator {

    data class Region(val x0: Int, val y0: Int, val w: Int, val h: Int)

    private const val DARK_FALLBACK = 0x1A1A2E

    fun evaluateFinish(
        fastEquirect: Bitmap,
        finishEquirect: Bitmap,
        ssdBefore: Float,
        ssdAfter: Float
    ): SphereDebugQualityReportFields {
        val content = requireContentRegion(fastEquirect)
        val band = overlapBandRegion(content)
        val ghostFast = ghostingScore(fastEquirect, band)
        val ghostFinish = ghostingScore(finishEquirect, band)
        val ghostDelta = ImageOverlapMetrics.ghostDelta(ghostFast, ghostFinish)
        val bandCorr = normalizedCorrelation(fastEquirect, finishEquirect, band)
        val sharpness = laplacianVariance(finishEquirect, content)
        return SphereDebugQualityReportFields(
            ghostFast = ghostFast,
            ghostFinish = ghostFinish,
            finishGhostDelta = ghostDelta,
            finishOverlapBandCorrelation = bandCorr,
            finishSharpness = sharpness,
            ssdBeforeGlobal = ssdBefore,
            ssdAfterGlobal = ssdAfter,
            passesFinishGhostGate = ghostDelta <= SphereRefinePolicy.maxFinishExportGhostDelta(),
            passesBandCorrelationGate = bandCorr >= SphereRefinePolicy.minFinishOverlapBandCorrelation()
        )
    }

    fun evaluatePreview(fastEquirect: Bitmap, previewEquirect: Bitmap): PreviewFields {
        val region = requireContentRegion(fastEquirect)
        val ghostFast = ghostingScore(fastEquirect, region)
        val ghostPreview = ghostingScore(previewEquirect, region)
        val corr = normalizedCorrelation(fastEquirect, previewEquirect, region)
        return PreviewFields(
            ghostDelta = ImageOverlapMetrics.ghostDelta(ghostFast, ghostPreview),
            correlation = corr
        )
    }

    data class SphereDebugQualityReportFields(
        val ghostFast: Double,
        val ghostFinish: Double,
        val finishGhostDelta: Double,
        val finishOverlapBandCorrelation: Double,
        val finishSharpness: Double,
        val ssdBeforeGlobal: Float,
        val ssdAfterGlobal: Float,
        val passesFinishGhostGate: Boolean,
        val passesBandCorrelationGate: Boolean
    )

    data class PreviewFields(val ghostDelta: Double, val correlation: Double)

    fun laplacianVariance(bmp: Bitmap, region: Region): Double {
        val xStart = max(1, region.x0)
        val yStart = max(1, region.y0)
        val xEnd = min(bmp.width - 1, region.x0 + region.w)
        val yEnd = min(bmp.height - 1, region.y0 + region.h)
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

    fun ghostingScore(bmp: Bitmap, region: Region): Double {
        val minShift = 3
        val maxShift = 12
        val edgeGradThreshold = 35f
        val xStart = max(3, region.x0)
        val yStart = max(1, region.y0)
        val xEnd = min(bmp.width - maxShift - 3, region.x0 + region.w)
        val yEnd = min(bmp.height - 1, region.y0 + region.h)
        if (xEnd <= xStart + maxShift || yEnd <= yStart + 1) return 0.0
        var edgeCount = 0
        var ghostCount = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                if (!isContentPixel(bmp.getPixel(x, y))) continue
                val g1 = horizontalGrad(bmp, x, y)
                if (abs(g1) < edgeGradThreshold) continue
                edgeCount++
                var found = false
                for (dx in minShift..maxShift) {
                    val x2 = x + dx
                    if (!isContentPixel(bmp.getPixel(x2, y))) continue
                    val g2 = horizontalGrad(bmp, x2, y)
                    if (abs(g2) < edgeGradThreshold || g1 * g2 < 0f) continue
                    ghostCount++
                    found = true
                    break
                }
                if (!found) {
                    for (dy in -2..2) {
                        if (dy == 0) continue
                        val y2 = y + dy
                        if (y2 < yStart || y2 >= yEnd) continue
                        for (dx in minShift..maxShift) {
                            val x2 = x + dx
                            if (!isContentPixel(bmp.getPixel(x2, y2))) continue
                            val g2 = horizontalGrad(bmp, x2, y2)
                            if (abs(g2) < edgeGradThreshold || g1 * g2 < 0f) continue
                            ghostCount++
                            found = true
                            break
                        }
                        if (found) break
                    }
                }
            }
        }
        return if (edgeCount > 0) ghostCount.toDouble() / edgeCount else 0.0
    }

    fun normalizedCorrelation(a: Bitmap, b: Bitmap, region: Region): Double {
        val w = a.width
        val h = a.height
        val pixelsA = IntArray(w * h)
        val pixelsB = IntArray(w * h)
        a.getPixels(pixelsA, 0, w, 0, 0, w, h)
        b.getPixels(pixelsB, 0, w, 0, 0, w, h)
        return ImageOverlapMetrics.normalizedCorrelation(
            pixelsA, pixelsB, w, h,
            ImageOverlapMetrics.Region(region.x0, region.y0, region.w, region.h)
        )
    }

    fun requireContentRegion(bmp: Bitmap): Region =
        findContentRegion(bmp) ?: Region(0, 0, bmp.width, bmp.height)

    fun overlapBandRegion(content: Region, bandHeightFraction: Float = 0.35f): Region {
        val bandH = (content.h * bandHeightFraction).toInt().coerceAtLeast(8)
        val y0 = content.y0 + (content.h - bandH) / 2
        return Region(content.x0, y0, content.w, bandH)
    }

    private fun findContentRegion(bmp: Bitmap, margin: Int = 8): Region? {
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

    private fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun horizontalGrad(bmp: Bitmap, x: Int, y: Int): Float =
        luminance(bmp.getPixel(x + 1, y)) - luminance(bmp.getPixel(x - 1, y))

    private fun isContentPixel(pixel: Int): Boolean {
        if ((pixel ushr 24) == 0) return false
        return (pixel and 0x00FFFFFF) != DARK_FALLBACK
    }
}
