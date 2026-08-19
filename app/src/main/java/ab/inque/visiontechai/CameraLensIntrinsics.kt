package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import ab.inque.robotics.panorama.SphereNative
import kotlin.math.atan
import kotlin.math.tan

/**
 * I3: seed sphere session distortion from Camera2 [CameraCharacteristics.LENS_DISTORTION]
 * when the device exposes radial coefficients.
 */
object CameraLensIntrinsics {

    private const val TAG = "CameraLensIntrinsics"

    /**
     * Brown–Conrady k1–p2 from back camera when available; null if unsupported.
     */
    fun fromBackCamera(context: Context): LensDistortion? =
        readDistortion(context, cameraId = "0")

    fun readDistortion(context: Context, cameraId: String): LensDistortion? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val coeffs = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_DISTORTION)
            if (coeffs == null || coeffs.isEmpty()) return null
            val k1 = coeffs[0]
            if (!k1.isFinite() || kotlin.math.abs(k1) < 1e-6f) return null
            val distortion = LensDistortion(
                k1 = k1,
                k2 = if (coeffs.size > 1) coeffs[1] else 0f,
                p1 = if (coeffs.size > 2) coeffs[2] else 0f,
                p2 = if (coeffs.size > 3) coeffs[3] else 0f
            )
            Log.i(TAG, "Camera2 LENS_DISTORTION k1=${distortion.k1} k2=${distortion.k2}")
            distortion
        } catch (e: Exception) {
            Log.w(TAG, "LENS_DISTORTION unavailable: ${e.message}")
            null
        }
    }

    /** Stored profile → Camera2 → device table → NONE. */
    fun resolveSessionDistortion(
        context: Context,
        store: LensDistortionStore
    ): LensDistortion {
        val stored = store.get()
        if (stored != LensDistortion.NONE) return stored
        fromBackCamera(context)?.let { return it }
        return store.deviceDefault()
    }

    /**
     * I3: seed native focal scale from Camera2 physical sensor + focal length vs nominal FOV.
     */
    fun readFocalScaleSeed(context: Context, nominalFovHDeg: Float, cameraId: String = "0"): Float {
        if (nominalFovHDeg <= 1f) return 1f
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focalLengths == null || focalLengths.isEmpty() || sensorSize == null) return 1f
            val focalMm = focalLengths[0]
            if (focalMm <= 0f || sensorSize.width <= 0f) return 1f
            val cameraFovH = Math.toDegrees(
                (2.0 * atan((sensorSize.width / (2.0 * focalMm)).toDouble()))
            ).toFloat()
            val nominalTan = tan(Math.toRadians(nominalFovHDeg.toDouble() / 2.0)).toFloat()
            val cameraTan = tan(Math.toRadians(cameraFovH.toDouble() / 2.0)).toFloat()
            if (nominalTan <= 1e-4f) 1f else (cameraTan / nominalTan).coerceIn(0.92f, 1.08f)
        } catch (e: Exception) {
            Log.w(TAG, "Focal scale seed unavailable: ${e.message}")
            1f
        }
    }

    fun applySessionFocalScaleSeed(context: Context, nominalFovHDeg: Float) {
        if (!SphereNative.isAvailable()) return
        val scale = readFocalScaleSeed(context, nominalFovHDeg)
        if (SphereNative.setSessionFocalScale(scale)) {
            Log.i(TAG, "Session focal scale seed=$scale (nominalFovH=$nominalFovHDeg)")
        }
    }
}
