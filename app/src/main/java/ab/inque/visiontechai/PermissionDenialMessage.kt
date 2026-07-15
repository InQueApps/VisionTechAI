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
        denied.any { it.contains("MEDIA") } ->
            "Media access permission is required to save photos and videos."
        else -> "Required permissions were not granted. App cannot function properly."
    }
}
