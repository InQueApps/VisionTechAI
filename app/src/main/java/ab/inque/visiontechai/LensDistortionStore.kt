package ab.inque.visiontechai

import android.content.Context
import android.os.Build

/**
 * Persistent per-device lens distortion for sphere stitching.
 * User overrides in SharedPreferences take precedence over [DEVICE_PROFILES].
 */
class LensDistortionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): LensDistortion = if (prefs.getBoolean(KEY_CUSTOM, false)) {
        LensDistortion(
            k1 = prefs.getFloat(KEY_K1, 0f),
            k2 = prefs.getFloat(KEY_K2, 0f),
            p1 = prefs.getFloat(KEY_P1, 0f),
            p2 = prefs.getFloat(KEY_P2, 0f)
        )
    } else {
        deviceDefault()
    }

    fun set(distortion: LensDistortion) {
        prefs.edit()
            .putBoolean(KEY_CUSTOM, true)
            .putFloat(KEY_K1, distortion.k1)
            .putFloat(KEY_K2, distortion.k2)
            .putFloat(KEY_P1, distortion.p1)
            .putFloat(KEY_P2, distortion.p2)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    fun deviceDefault(): LensDistortion =
        DEVICE_PROFILES[Build.MODEL] ?: LensDistortion.NONE

    companion object {
        private const val PREFS_NAME = "sphere_lens_distortion"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_K1 = "k1"
        private const val KEY_K2 = "k2"
        private const val KEY_P1 = "p1"
        private const val KEY_P2 = "p2"

        /** Approximate wide-angle barrel correction for common devices (k1 only). */
        val DEVICE_PROFILES: Map<String, LensDistortion> = mapOf(
            "Pixel 6" to LensDistortion(k1 = -0.08f),
            "Pixel 6 Pro" to LensDistortion(k1 = -0.08f),
            "Pixel 7" to LensDistortion(k1 = -0.07f),
            "Pixel 7 Pro" to LensDistortion(k1 = -0.07f),
            "Pixel 8" to LensDistortion(k1 = -0.06f),
            "Pixel 8 Pro" to LensDistortion(k1 = -0.06f),
            "SM-S911B" to LensDistortion(k1 = -0.09f),
            "SM-S918B" to LensDistortion(k1 = -0.09f)
        )
    }
}
