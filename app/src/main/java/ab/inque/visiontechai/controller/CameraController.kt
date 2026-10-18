package ab.inque.visiontechai.controller

import ab.inque.visiontechai.CameraFovCalculator
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.atomic.AtomicInteger
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import kotlin.math.abs

/**
 * Binds CameraX preview, capture, video, and image analysis use cases.
 */
@OptIn(ExperimentalCamera2Interop::class)
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

    @Volatile
    var isCameraBound: Boolean = false
        private set

    @Volatile
    private var sphereAnalysisPriority = false
    private val bindGeneration = AtomicInteger(0)
    private val exposureLock = Any()
    private val exposureSamples = ArrayDeque<ExposureSample>()

    /**
     * Sphere capture unbinds photo/video so ImageAnalysis can actually get ~1440p.
     * CameraX otherwise shares the stream and falls back to ~640px, which makes the
     * stitched 360 look pixelated.
     */
    fun setSphereAnalysisPriority(enabled: Boolean) {
        if (sphereAnalysisPriority == enabled) return
        sphereAnalysisPriority = enabled
        startCamera()
    }

    /**
     * Camera AE value for an ImageProxy timestamp: ISO × t × boost / f².
     * Returns 0 if metadata has not arrived yet.
     */
    fun exposureEvFor(timestampNs: Long): Float {
        synchronized(exposureLock) {
            if (exposureSamples.isEmpty()) return 0f
            if (timestampNs <= 0L) return exposureSamples.last().ev
            var best = exposureSamples.last()
            var bestDt = abs(best.timestampNs - timestampNs)
            for (sample in exposureSamples) {
                val dt = abs(sample.timestampNs - timestampNs)
                if (dt < bestDt) {
                    best = sample
                    bestDt = dt
                }
            }
            return if (bestDt <= EXPOSURE_MATCH_NS) best.ev else exposureSamples.last().ev
        }
    }

    fun startCamera() {
        val generation = bindGeneration.incrementAndGet()
        val analysisPriority = sphereAnalysisPriority
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            if (generation != bindGeneration.get()) return@addListener
            val cameraProvider = cameraProviderFuture.get()
            previewWidth = previewView.width
            previewHeight = previewView.height

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysisSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        ANALYSIS_TARGET,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .setResolutionFilter { supported, _ ->
                    supported.sortedByDescending { size ->
                        size.width.toLong() * size.height
                    }
                }
                .build()
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(analysisSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            Camera2Interop.Extender(analysisBuilder)
                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        recordExposure(result)
                    }
                })
            val imageAnalyzer = analysisBuilder.build()
                .also { it.setAnalyzer(cameraExecutor, createAnalyzer()) }

            try {
                cameraProvider.unbindAll()
                isCameraBound = false
                if (analysisPriority) {
                    imageCapture = null
                    videoCapture = null
                    cameraProvider.bindToLifecycle(
                        activity,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                    Log.i(TAG, "Bound preview+analysis for 360 capture (${ANALYSIS_TARGET})")
                } else {
                    imageCapture = ImageCapture.Builder().build()
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    cameraProvider.bindToLifecycle(
                        activity,
                        cameraSelector,
                        preview,
                        imageCapture,
                        videoCapture,
                        imageAnalyzer
                    )
                }
                if (generation != bindGeneration.get()) return@addListener
                isCameraBound = true
                detectCameraFov()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                imageCapture = null
                videoCapture = null
                isCameraBound = false
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

    private fun recordExposure(result: TotalCaptureResult) {
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        if (iso <= 0 || exposureNs <= 0L) return
        val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: 1.8f
        val f = aperture.coerceAtLeast(0.5f)
        val ev = iso.toFloat() * (boost / 100f) * (exposureNs / 1_000_000_000f) / (f * f)
        synchronized(exposureLock) {
            if (exposureSamples.size >= EXPOSURE_HISTORY) exposureSamples.removeFirst()
            exposureSamples.addLast(ExposureSample(timestampNs, ev))
        }
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
                val (fovH, fovV) = CameraFovCalculator.fromSensor(
                    sensorSize.width,
                    sensorSize.height,
                    focalLengths[0]
                )
                Log.i(TAG, "Camera FOV: ${fovH}° x ${fovV}°")
                onFovDetected(fovH, fovV)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not detect camera FOV: ${e.message}")
        }
    }

    private data class ExposureSample(val timestampNs: Long, val ev: Float)

    companion object {
        private const val TAG = "CameraController"
        /** Prefer ~1440p 4:3 so sphere center-square crops keep ~1440 px, not CameraX's 640 default. */
        private val ANALYSIS_TARGET = Size(1920, 1440)
        private const val EXPOSURE_HISTORY = 64
        private const val EXPOSURE_MATCH_NS = 100_000_000L
    }
}
