package ab.inque.visiontechai.controller

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService

/**
 * Binds CameraX preview, capture, video, and image analysis use cases.
 */
class CameraController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val cameraExecutor: ExecutorService,
    private val createAnalyzer: () -> ImageAnalysis.Analyzer,
    private val onFovDetected: (horizontalDeg: Float, verticalDeg: Float) -> Unit
) {
    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set

    var previewWidth: Int = 0
        private set
    var previewHeight: Int = 0
        private set

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            previewWidth = previewView.width
            previewHeight = previewView.height

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, createAnalyzer()) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture,
                    imageAnalyzer
                )
                detectCameraFov()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun detectCameraFov() {
        try {
            val cameraManager =
                activity.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "0" else "1"
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val focalLengths = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                val fovH = (2.0 * Math.toDegrees(
                    kotlin.math.atan((sensorSize.width / (2.0 * focalLengths[0])).toDouble())
                )).toFloat()
                val fovV = (2.0 * Math.toDegrees(
                    kotlin.math.atan((sensorSize.height / (2.0 * focalLengths[0])).toDouble())
                )).toFloat()
                Log.i(TAG, "Camera FOV: ${fovH}° x ${fovV}°")
                onFovDetected(fovH, fovV)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not detect camera FOV: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
