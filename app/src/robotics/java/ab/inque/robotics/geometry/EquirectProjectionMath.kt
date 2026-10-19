package ab.inque.robotics.geometry

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.math.LensDistortionMath
import ab.inque.robotics.math.QuaternionMath
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Pinhole camera ray ↔ world ↔ equirectangular pixel mapping.
 * Convention: camera looks along −Z; earth frame X=East, Y=North, Z=Up.
 */
object EquirectProjectionMath {

    data class EquirectCoord(val x: Int, val y: Int)

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

    fun worldToEquirect(wx: Double, wy: Double, wz: Double, eqW: Int, eqH: Int): EquirectCoord {
        val lon = atan2(wx, wy)
        val lat = asin(wz.coerceIn(-1.0, 1.0))
        val eqX = (((lon / PI + 1.0) * 0.5 * eqW).toInt() + eqW) % eqW
        val eqY = ((0.5 - lat / PI) * eqH).toInt().coerceIn(0, eqH - 1)
        return EquirectCoord(eqX, eqY)
    }

    fun equirectPixelToWorldRay(eqXf: Double, eqYf: Double, eqW: Int, eqH: Int): DoubleArray {
        val lon = (eqXf / eqW * 2.0 - 1.0) * PI
        val lat = (0.5 - eqYf / eqH) * PI
        val clat = kotlin.math.cos(lat)
        return doubleArrayOf(
            clat * kotlin.math.sin(lon),
            clat * kotlin.math.cos(lon),
            kotlin.math.sin(lat)
        )
    }

    fun worldRayToFramePixel(
        wx: Double, wy: Double, wz: Double,
        quaternion: FloatArray,
        frameW: Int, frameH: Int,
        fovH: Float, fovV: Float
    ): Pair<Float, Float>? {
        val cam = QuaternionMath.rotateVector(
            floatArrayOf(-quaternion[0], -quaternion[1], -quaternion[2], quaternion[3]),
            wx, wy, wz
        )
        val crx = cam[0]
        val cry = cam[1]
        val crz = cam[2]
        if (crz >= -1e-4) return null
        val halfFovH = Math.toRadians(fovH.toDouble() * 0.5)
        val halfFovV = Math.toRadians(fovV.toDouble() * 0.5)
        val tanH = tan(halfFovH)
        val tanV = tan(halfFovV)
        val nx = crx / (-crz)
        val ny = cry / (-crz)
        val fx = (nx / (2.0 * tanH) + 0.5) * (frameW - 1)
        val fy = (0.5 - ny / (2.0 * tanV)) * (frameH - 1)
        if (fx < 0.0 || fx > frameW - 1 || fy < 0.0 || fy > frameH - 1) return null
        return fx.toFloat() to fy.toFloat()
    }

    fun roundTripFramePixel(
        fx: Int, fy: Int, frameW: Int, frameH: Int,
        fovH: Float, fovV: Float, quaternion: FloatArray
    ): Pair<Float, Float>? {
        val ray = framePixelToRay(fx, fy, frameW, frameH, fovH, fovV)
        val world = QuaternionMath.rotateVector(quaternion, ray[0], ray[1], ray[2])
        return worldRayToFramePixel(world[0], world[1], world[2], quaternion, frameW, frameH, fovH, fovV)
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
