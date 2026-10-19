package ab.inque.robotics.panorama

import ab.inque.robotics.math.QuaternionMath
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Maps device orientation quaternions to equirect look angles used by
 * [SphereNative.getRectilinearBitmap] (world: X=East, Y=North, Z=Up).
 */
object SphereLookOrientation {

    /**
     * Azimuth/elevation (radians) for a rectilinear view that matches the
     * camera forward of [q] (device center ray = (0, 0, -1)).
     */
    fun quatToAzEl(q: FloatArray): Pair<Float, Float> {
        require(q.size >= 4)
        val n = QuaternionMath.normalize(q)
        val wxwywz = QuaternionMath.rotateVector(n, 0.0, 0.0, -1.0)
        val azimuth = atan2(wxwywz[0], wxwywz[1]).toFloat()
        val elevation = asin(wxwywz[2].coerceIn(-1.0, 1.0)).toFloat()
        return azimuth to elevation
    }

    /** Relative rotation from [reference] to [current], both [x,y,z,w]. */
    fun relativeRotation(reference: FloatArray, current: FloatArray): FloatArray {
        val invRef = QuaternionMath.conjugate(QuaternionMath.normalize(reference))
        return QuaternionMath.multiply(invRef, QuaternionMath.normalize(current))
    }
}
