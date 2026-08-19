package ab.inque.visiontechai

import android.graphics.Bitmap
import kotlin.math.atan
import kotlin.math.tan

/**
 * Prepares live camera frames for sphere stitch by using only the center square
 * region (best sharpness / lowest radial distortion vs full sensor frame).
 */
object SphereStitchFramePrep {

    data class Result(
        val bitmap: Bitmap,
        /** True when [bitmap] is a new crop and should be recycled after the worker copies it. */
        val cropped: Boolean,
        val fovH: Float,
        val fovV: Float
    )

    /** Center-crop to min(width, height); returns [source] when already square. */
    fun cropCenterSquare(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        require(w > 0 && h > 0)
        val side = minOf(w, h)
        if (w == side && h == side) return source
        val left = (w - side) / 2
        val top = (h - side) / 2
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    /**
     * Center-crop to min(width, height) and compute the angular FOV of that region.
     */
    fun centerSquare(
        source: Bitmap,
        fullFovH: Float,
        fullFovV: Float
    ): Result {
        val w = source.width
        val h = source.height
        require(w > 0 && h > 0)
        val side = minOf(w, h)
        val (fovH, fovV) = fovForCenterSquare(fullFovH, fullFovV, w, h)
        if (w == side && h == side) {
            return Result(source, cropped = false, fovH = fovH, fovV = fovV)
        }
        return Result(
            cropCenterSquare(source),
            cropped = true,
            fovH = fovH,
            fovV = fovV
        )
    }

    /** Pinhole FOV for a center square of side min(W,H) inside a full W×H frame. */
    fun fovForCenterSquare(
        fullFovH: Float,
        fullFovV: Float,
        fullW: Int,
        fullH: Int
    ): Pair<Float, Float> {
        require(fullW > 0 && fullH > 0)
        val side = minOf(fullW, fullH)
        val tanH = tan(Math.toRadians(fullFovH.toDouble() / 2.0)) * side / fullW
        val tanV = tan(Math.toRadians(fullFovV.toDouble() / 2.0)) * side / fullH
        val fovH = Math.toDegrees(2.0 * atan(tanH)).toFloat()
        val fovV = Math.toDegrees(2.0 * atan(tanV)).toFloat()
        return fovH to fovV
    }
}
