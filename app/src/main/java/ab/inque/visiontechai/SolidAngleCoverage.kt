package ab.inque.visiontechai

/**
 * Solid-angle coverage from a coarse equirect weight grid (testable without JNI).
 */
object SolidAngleCoverage {

    const val GRID_W = 32
    const val GRID_H = 16
    const val WEIGHT_EPSILON = 0.05f

    fun computeFraction(weights: FloatArray, eqW: Int, eqH: Int): Float {
        if (weights.isEmpty() || eqW <= 0 || eqH <= 0) return 0f
        var filled = 0
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val x0 = gx * eqW / GRID_W
                val x1 = (gx + 1) * eqW / GRID_W
                val y0 = gy * eqH / GRID_H
                val y1 = (gy + 1) * eqH / GRID_H
                var maxW = 0f
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        maxW = maxOf(maxW, weights[y * eqW + x])
                    }
                }
                if (maxW > WEIGHT_EPSILON) filled++
            }
        }
        return filled.toFloat() / (GRID_W * GRID_H)
    }

    fun buildHoleMaskArgb(weights: FloatArray, eqW: Int, eqH: Int, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                val gx = ox * GRID_W / outW
                val gy = oy * GRID_H / outH
                val x0 = gx * eqW / GRID_W
                val x1 = (gx + 1) * eqW / GRID_W
                val y0 = gy * eqH / GRID_H
                val y1 = (gy + 1) * eqH / GRID_H
                var maxW = 0f
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        maxW = maxOf(maxW, weights[y * eqW + x])
                    }
                }
                out[oy * outW + ox] = if (maxW > WEIGHT_EPSILON) 0x00000000 else 0x88FF0000.toInt()
            }
        }
        return out
    }
}
