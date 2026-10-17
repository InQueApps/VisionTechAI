package ab.inque.visiontechai

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Equirect export size that preserves the angular resolution of the stitch input frames.
 *
 * A W×H pinhole frame covering fovH×fovV maps to a 2:1 sphere of
 * `(W / fovH) * 360` by `(H / fovV) * 180`. Live capture still uses a small
 * preview buffer; only the DONE/SAVE export uses this size.
 */
object SphereExportSize {

    data class Size(val width: Int, val height: Int)

    const val MIN_WIDTH = 1024
    const val MAX_WIDTH = 8192
    const val ALIGN = 16

    fun fromFrame(
        frameW: Int,
        frameH: Int,
        fovH: Float,
        fovV: Float,
        maxWidth: Int = MAX_WIDTH,
        minWidth: Int = MIN_WIDTH
    ): Size {
        if (frameW <= 0 || frameH <= 0) {
            return Size(SphereCaptureController.EQ_W, SphereCaptureController.EQ_H)
        }
        val hFov = fovH.coerceAtLeast(1f)
        val vFov = fovV.coerceAtLeast(1f)
        val fromHorizontal = frameW * 360f / hFov
        val fromVertical = frameH * 180f / vFov * 2f
        var width = max(fromHorizontal, fromVertical).roundToInt()
        width = width.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        width = (width / ALIGN) * ALIGN
        if (width < minWidth) width = minWidth
        if (width % 2 != 0) width -= 1
        return Size(width, width / 2)
    }

    /**
     * Cap 8192×4096 float compose buffers (~512 MB) on devices with a large heap;
     * fall back to 4096 or 2048 when the JVM heap cannot hold them.
     */
    fun maxWidthForDevice(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Int {
        return when {
            maxMemoryBytes < 384L * 1024 * 1024 -> 2048
            maxMemoryBytes < 768L * 1024 * 1024 -> SphereCaptureController.EQ_W
            else -> MAX_WIDTH
        }
    }
}
