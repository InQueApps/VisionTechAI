package ab.inque.visiontechai.controller

import ab.inque.visiontechai.OrientationTracker
import ab.inque.visiontechai.SphereCaptureController
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.databinding.ActivityMainBinding
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ab.inque.visiontechai.Constants
import ab.inque.visiontechai.SavedMediaTracker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages 360° sphere capture lifecycle and equirectangular export.
 */
class SphereSessionController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val orientationTracker: OrientationTracker,
    private val setVisionMode: (VisionMode) -> Unit,
    private val getCameraFovH: () -> Float,
    private val getCameraFovV: () -> Float
) {
    var controller: SphereCaptureController? = null
        private set

    @Volatile
    var captureNextFrame: Boolean = false

    fun enter() {
        setVisionMode(VisionMode.SPHERE)
        orientationTracker.start()
        controller = SphereCaptureController(
            orientationTracker,
            onCoverageUpdate = { coverage ->
                activity.runOnUiThread {
                    binding.sphereCoverageText.text = "Coverage: ${(coverage * 100).toInt()}%"
                }
            },
            onPreviewReady = {
                activity.runOnUiThread {
                    binding.sphereCaptureOverlay.visibility = View.GONE
                    binding.sphereViewerOverlay.visibility = View.VISIBLE
                    binding.sphereViewerView.setLookDirection(0f, 0f, 90f)
                }
            },
            onFrameWarning = { warning ->
                activity.runOnUiThread {
                    val msg = when (warning) {
                        SphereCaptureController.FrameWarning.NO_OVERLAP ->
                            "No overlap — move back toward previous frames"
                        SphereCaptureController.FrameWarning.EXCESSIVE_SHIFT ->
                            "Slow down — too much movement between frames"
                        SphereCaptureController.FrameWarning.ALIGNMENT_FAILED ->
                            "Frame skipped — could not align with panorama"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
        val isPortrait = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val effectiveFovH = if (isPortrait) getCameraFovV() else getCameraFovH()
        val effectiveFovV = if (isPortrait) getCameraFovH() else getCameraFovV()
        controller?.startSession(effectiveFovH, effectiveFovV)
        binding.sphereCaptureOverlay.visibility = View.VISIBLE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereCoverageText.text = "Coverage: 0%"
        Toast.makeText(activity, "Sphere mode — scan your surroundings", Toast.LENGTH_SHORT).show()
    }

    fun exit() {
        controller?.resetSession()
        controller = null
        orientationTracker.stop()
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        setVisionMode(VisionMode.NONE)
    }

    fun cleanupOnDestroy() {
        orientationTracker.stop()
        controller?.release()
        controller = null
    }

    fun onSphereCleanupFromModeSwitch() {
        controller?.resetSession()
        controller = null
        orientationTracker.stop()
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
    }

    fun handleRecordButton(isPhotoMode: Boolean, onRecordIconChange: (Int) -> Unit) {
        val ctrl = controller ?: return
        if (ctrl.state != SphereCaptureController.State.CAPTURING) return
        if (isPhotoMode) {
            captureNextFrame = true
            Toast.makeText(activity, "Frame added to sphere", Toast.LENGTH_SHORT).show()
        } else if (ctrl.isContinuousCapturing) {
            ctrl.stopContinuousCapture()
            onRecordIconChange(ab.inque.visiontechai.R.drawable.ic_record)
            Toast.makeText(activity, "Continuous capture stopped", Toast.LENGTH_SHORT).show()
        } else {
            ctrl.startContinuousCapture()
            onRecordIconChange(ab.inque.visiontechai.R.drawable.ic_record)
            Toast.makeText(activity, "Continuous capture started — pan slowly", Toast.LENGTH_SHORT).show()
        }
    }

    fun finishCapture() {
        controller?.finishCapture()
    }

    fun saveEquirectangular() {
        val equirect = controller?.getFullEquirect()
        if (equirect == null) {
            Toast.makeText(activity, "No sphere data to save", Toast.LENGTH_SHORT).show()
            return
        }
        saveEquirectangularBitmap(equirect)
    }

    private fun saveEquirectangularBitmap(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val name = "PrismAI_SPHERE_$timestamp.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Constants.GALLERY_IMAGE_RELATIVE_PATH)
                }
            }
            val jpegBytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegBytes)
            val xmpData = buildXmpMetadata(bitmap.width, bitmap.height)
            val finalData = injectXmpIntoJpeg(jpegBytes.toByteArray(), xmpData)
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { savedUri ->
                activity.contentResolver.openOutputStream(savedUri)?.use { it.write(finalData) }
                SavedMediaTracker.addImage(activity, savedUri)
                activity.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, savedUri)
                )
                Toast.makeText(activity, "360° photo saved", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Sphere photo saved: $savedUri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sphere: ${e.message}")
            Toast.makeText(activity, "Failed to save sphere photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildXmpMetadata(width: Int, height: Int): ByteArray {
        val xmp = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
    GPano:UsePanoramaViewer="True"
    GPano:ProjectionType="equirectangular"
    GPano:FullPanoWidthPixels="$width"
    GPano:FullPanoHeightPixels="$height"
    GPano:CroppedAreaLeftPixels="0"
    GPano:CroppedAreaTopPixels="0"
    GPano:CroppedAreaImageWidthPixels="$width"
    GPano:CroppedAreaImageHeightPixels="$height"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
        return xmp.toByteArray(Charsets.UTF_8)
    }

    private fun injectXmpIntoJpeg(jpegData: ByteArray, xmpData: ByteArray): ByteArray {
        val namespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val payloadLen = namespace.size + xmpData.size
        val segmentLen = payloadLen + 2
        val result = ByteArrayOutputStream(jpegData.size + segmentLen + 4)
        result.write(jpegData, 0, 2)
        result.write(0xFF)
        result.write(0xE1)
        result.write((segmentLen shr 8) and 0xFF)
        result.write(segmentLen and 0xFF)
        result.write(namespace)
        result.write(xmpData)
        result.write(jpegData, 2, jpegData.size - 2)
        return result.toByteArray()
    }

    companion object {
        private const val TAG = "SphereSession"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
