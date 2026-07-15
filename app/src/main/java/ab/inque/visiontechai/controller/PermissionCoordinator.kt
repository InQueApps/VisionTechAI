package ab.inque.visiontechai.controller

import ab.inque.visiontechai.PermissionDenialMessage
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ab.inque.visiontechai.R

/**
 * Handles camera/media permission requests and rationale dialogs.
 */
class PermissionCoordinator(
    private val activity: AppCompatActivity,
    private val onAllGranted: () -> Unit,
    private val onMinimumGranted: () -> Unit,
    private val requestLauncher: ActivityResultLauncher<Array<String>>
) {
    fun onPermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            onAllGranted()
            return
        }
        val denied = permissions.filterValues { !it }.keys
        Log.w(TAG, "Permissions denied: $denied")
        Toast.makeText(activity, PermissionDenialMessage.forDenied(denied), Toast.LENGTH_LONG).show()
        showExplanationDialog()
    }

    fun checkAndRequest() {
        when {
            allGranted() -> {
                Log.d(TAG, "All permissions already granted")
                onAllGranted()
            }
            hasMinimum() -> {
                Log.d(TAG, "Minimum permissions granted")
                onMinimumGranted()
                Toast.makeText(
                    activity,
                    "Some features may be limited due to missing permissions",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                val toRequest = REQUIRED_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (toRequest.isNotEmpty()) {
                    showRationaleDialog(toRequest.toTypedArray())
                } else {
                    showExplanationDialog()
                }
            }
        }
    }

    fun allGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMinimum(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        requestLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun showExplanationDialog() {
        AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setTitle("Permissions Required")
            .setMessage(
                "This app needs camera and media permissions to function properly:\n\n" +
                    "• Camera: To capture photos and videos\n" +
                    "• Audio: To record video with sound\n" +
                    "• Media: To save your photos and videos\n\n" +
                    "Please grant these permissions to use the app."
            )
            .setPositiveButton("Grant Permissions") { _, _ -> requestPermissions() }
            .setNeutralButton("Open Settings") { _, _ ->
                activity.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", activity.packageName, null)
                    }
                )
            }
            .setNegativeButton("Exit") { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }

    private fun showRationaleDialog(permissions: Array<String>) {
        AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setTitle("Permissions Needed")
            .setMessage(
                "This app needs the following permissions to work:\n\n" +
                    "• Camera: To take photos and record videos\n" +
                    "• Audio: To record sound in videos\n" +
                    "• Media: To save your photos and videos\n\n" +
                    "These permissions are essential for the app to function."
            )
            .setPositiveButton("Grant Permissions") { _, _ -> requestPermissions() }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(
                    activity,
                    "App cannot function without required permissions",
                    Toast.LENGTH_LONG
                ).show()
                activity.finish()
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val TAG = "PermissionCoordinator"

        val REQUIRED_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
}
