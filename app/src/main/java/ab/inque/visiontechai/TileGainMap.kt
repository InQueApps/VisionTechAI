package ab.inque.visiontechai

/**
 * 8×8 per-tile exposure gain for overlap blending (mirrors native splat logic).
 */
object TileGainMap {

    const val TILES = 8
    const val MIN_GAIN = 0.5f
    const val MAX_GAIN = 2.0f

    data class Rgb(val r: Float, val g: Float, val b: Float)

    fun computeGains(
        samples: List<Pair<Int, Rgb>>,
        existing: List<Pair<Int, Rgb>>,
        frameW: Int,
        frameH: Int
    ): FloatArray {
        val sumExR = FloatArray(TILES * TILES)
        val sumExG = FloatArray(TILES * TILES)
        val sumExB = FloatArray(TILES * TILES)
        val sumNewR = FloatArray(TILES * TILES)
        val sumNewG = FloatArray(TILES * TILES)
        val sumNewB = FloatArray(TILES * TILES)
        val count = IntArray(TILES * TILES)

        for (i in samples.indices) {
            val (pixel, newRgb) = samples[i]
            val exRgb = existing.getOrNull(i)?.second ?: continue
            val tx = tileX(pixel, frameW)
            val ty = tileY(pixel, frameH)
            val t = ty * TILES + tx
            sumExR[t] += exRgb.r
            sumExG[t] += exRgb.g
            sumExB[t] += exRgb.b
            sumNewR[t] += newRgb.r
            sumNewG[t] += newRgb.g
            sumNewB[t] += newRgb.b
            count[t]++
        }

        val gains = FloatArray(TILES * TILES * 3) { 1f }
        for (t in 0 until TILES * TILES) {
            if (count[t] < 2) continue
            val i = t * 3
            gains[i] = clampGain(sumExR[t] / sumNewR[t])
            gains[i + 1] = clampGain(sumExG[t] / sumNewG[t])
            gains[i + 2] = clampGain(sumExB[t] / sumNewB[t])
        }
        return gains
    }

    fun sampleBilinear(gains: FloatArray, fx: Int, fy: Int, frameW: Int, frameH: Int, channel: Int): Float {
        val u = fx.toFloat() / frameW.coerceAtLeast(1) * TILES
        val v = fy.toFloat() / frameH.coerceAtLeast(1) * TILES
        val x0 = u.toInt().coerceIn(0, TILES - 1)
        val y0 = v.toInt().coerceIn(0, TILES - 1)
        val x1 = (x0 + 1).coerceAtMost(TILES - 1)
        val y1 = (y0 + 1).coerceAtMost(TILES - 1)
        val tx = u - x0
        val ty = v - y0
        fun at(tx: Int, ty: Int) = gains[(ty * TILES + tx) * 3 + channel]
        val v00 = at(x0, y0)
        val v10 = at(x1, y0)
        val v01 = at(x0, y1)
        val v11 = at(x1, y1)
        return (1 - tx) * (1 - ty) * v00 + tx * (1 - ty) * v10 +
            (1 - tx) * ty * v01 + tx * ty * v11
    }

    fun tileX(fx: Int, frameW: Int): Int =
        ((fx.toLong() * TILES) / frameW.coerceAtLeast(1)).toInt().coerceIn(0, TILES - 1)

    fun tileY(fy: Int, frameH: Int): Int =
        ((fy.toLong() * TILES) / frameH.coerceAtLeast(1)).toInt().coerceIn(0, TILES - 1)

    fun clampGain(v: Float): Float = v.coerceIn(MIN_GAIN, MAX_GAIN)
}
