package ab.inque.robotics.math

/**
 * Brown–Conrady lens distortion (normalized image plane).
 */
data class LensDistortion(
    val k1: Float = 0f,
    val k2: Float = 0f,
    val p1: Float = 0f,
    val p2: Float = 0f
) {
    companion object {
        val NONE = LensDistortion()
    }
}

object LensDistortionMath {

    /** Inverse distortion: distorted normalized coords → undistorted. */
    fun undistortNormalized(
        x: Double,
        y: Double,
        distortion: LensDistortion,
        iterations: Int = 5
    ): DoubleArray {
        if (distortion == LensDistortion.NONE) return doubleArrayOf(x, y)
        var ux = x
        var uy = y
        val k1 = distortion.k1.toDouble()
        val k2 = distortion.k2.toDouble()
        val p1 = distortion.p1.toDouble()
        val p2 = distortion.p2.toDouble()
        repeat(iterations) {
            val r2 = ux * ux + uy * uy
            val radial = 1.0 + k1 * r2 + k2 * r2 * r2
            val dx = 2.0 * p1 * ux * uy + p2 * (r2 + 2.0 * ux * ux)
            val dy = p1 * (r2 + 2.0 * uy * uy) + 2.0 * p2 * ux * uy
            ux = (x - dx) / radial
            uy = (y - dy) / radial
        }
        return doubleArrayOf(ux, uy)
    }
}
