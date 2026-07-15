package ab.inque.visiontechai

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.tan

/**
 * Pinhole camera ray → world → equirectangular pixel mapping.
 * Mirrors [sphere_jni.cpp] conventions for unit testing without JNI.
 */
object SphereProjectionMath {

    data class EquirectCoord(val x: Int, val y: Int)

    /** Device pinhole ray for frame pixel (fx, fy). Camera looks along −Z. */
    fun framePixelToRay(
        fx: Int,
        fy: Int,
        frameW: Int,
        frameH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion = LensDistortion.NONE
    ): DoubleArray {
        val halfFovH = Math.toRadians(fovH.toDouble() * 0.5)
        val halfFovV = Math.toRadians(fovV.toDouble() * 0.5)
        val tanH = tan(halfFovH)
        val tanV = tan(halfFovV)
        var nx = ((fx.toDouble() / (frameW - 1).coerceAtLeast(1)) - 0.5) * 2.0 * tanH
        var ny = (0.5 - fy.toDouble() / (frameH - 1).coerceAtLeast(1)) * 2.0 * tanV
        if (distortion != LensDistortion.NONE) {
            val undist = LensDistortionMath.undistortNormalized(nx, ny, distortion)
            nx = undist[0]
            ny = undist[1]
        }
        val len = kotlin.math.sqrt(nx * nx + ny * ny + 1.0)
        return doubleArrayOf(nx / len, ny / len, -1.0 / len)
    }

    /** Earth-frame direction → equirect pixel. X=East, Y=North, Z=Up. */
    fun worldToEquirect(wx: Double, wy: Double, wz: Double, eqW: Int, eqH: Int): EquirectCoord {
        val lon = atan2(wx, wy)
        val lat = asin(wz.coerceIn(-1.0, 1.0))
        val eqX = (((lon / PI + 1.0) * 0.5 * eqW).toInt() + eqW) % eqW
        val eqY = ((0.5 - lat / PI) * eqH).toInt().coerceIn(0, eqH - 1)
        return EquirectCoord(eqX, eqY)
    }

    fun projectFramePixel(
        fx: Int,
        fy: Int,
        frameW: Int,
        frameH: Int,
        fovH: Float,
        fovV: Float,
        quaternion: FloatArray,
        eqW: Int,
        eqH: Int,
        distortion: LensDistortion = LensDistortion.NONE
    ): EquirectCoord {
        val ray = framePixelToRay(fx, fy, frameW, frameH, fovH, fovV, distortion)
        val world = QuaternionMath.rotateVector(quaternion, ray[0], ray[1], ray[2])
        return worldToEquirect(world[0], world[1], world[2], eqW, eqH)
    }
}
