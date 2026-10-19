package ab.inque.visiontechai

import android.Manifest

/**
 * User-facing message when runtime permissions are denied.
 */
object PermissionDenialMessage {

    fun forDenied(denied: Set<String>): String = when {
        denied.contains(Manifest.permission.CAMERA) ->
            "Camera permission is required for this app to function."
        denied.contains(Manifest.permission.RECORD_AUDIO) ->
            "Audio recording permission is required for video recording."
        denied.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE) ->
            "Storage permission is required to save photos and videos on this device."
        else -> "Required permissions were not granted. App cannot function properly."
    }
}
