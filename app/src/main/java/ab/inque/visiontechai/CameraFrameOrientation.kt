package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath

/**
 * Maps Android rotation-vector / IMU pose to the upright bitmap passed to the stitcher.
 *
 * CameraX [rotationDegrees] is the clockwise rotation applied to sensor pixels in
 * [ImageUtils.yuvToBitmap]. The stitcher must use the same camera frame as those pixels.
 */
object CameraFrameOrientation {

    /**
     * @param imuQuaternion device orientation [x,y,z,w] (after extrinsic offset if any)
     * @param rotationDegrees CameraX clockwise rotation applied to the bitmap (0, 90, 180, 270)
     */
    fun quaternionForUprightBitmap(
        imuQuaternion: FloatArray,
        rotationDegrees: Int
    ): FloatArray {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0) return imuQuaternion.copyOf()
        val offset = QuaternionMath.fromAxisAngle(
            0.0,
            0.0,
            1.0,
            Math.toRadians(-rot.toDouble())
        )
        return QuaternionMath.normalize(QuaternionMath.multiply(imuQuaternion, offset))
    }

    /** Portrait capture when manifest omits rotation (legacy sessions). */
    fun inferRotationDegrees(fovH: Float, fovV: Float, manifestRotation: Int?): Int {
        if (manifestRotation != null) return manifestRotation
        return if (fovV > fovH) 90 else 0
    }
}
