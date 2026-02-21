package ab.inque.visiontechai

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.ui.HistogramView
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Size
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isRecording = false
    private var isPhotoMode = true
    private var activeRecording: Recording? = null
    private var processedVideoRecorder: ProcessedVideoRecorder? = null
    private val visionState = VisionState()
    private lateinit var visionPipeline: VisionAnalysisPipeline
    private val frameProcessors: Map<VisionMode, FrameProcessor> by lazy { buildFrameProcessors() }
    private lateinit var objectDetectionProcessor: ObjectDetectionProcessor
    private lateinit var edgeDetectionProcessor: EdgeDetectionProcessor
    private lateinit var opticalFlowProcessor: OpticalFlowProcessor
    private lateinit var imageSegmentationProcessor: ImageSegmentationProcessor
    private lateinit var depthEstimationProcessor: DepthEstimationProcessor
    private lateinit var hdImageProcessor: HDImageProcessor
    private lateinit var objectDetectionOverlay: ObjectDetectionOverlay
    private lateinit var cameraExecutor: ExecutorService
    private var previewWidth = 0
    private var previewHeight = 0
    private lateinit var nightVisionImageView: ImageView
    private lateinit var edgeDetectionImageView: ImageView
    private lateinit var opticalFlowImageView: ImageView
    private lateinit var colorAdjustmentImageView: ImageView
    private lateinit var textTranslationImageView: ImageView
    private lateinit var segmentationImageView: ImageView
    private lateinit var depthFieldImageView: ImageView
    private lateinit var hdImageView: ImageView
    private lateinit var histogramView: HistogramView
    private lateinit var fpsView: TextView
    private var segmentationNullLogCounter = 0
    private lateinit var orientationTracker: OrientationTracker
    private var sphereController: SphereCaptureController? = null
    private var cameraFovH = 65f
    private var cameraFovV = 50f
    @Volatile private var sphereCaptureNextFrame = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted, starting camera")
            startCamera()
        } else {
            // Check which permissions were denied
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }
            Log.w(TAG, "Permissions denied: $deniedPermissions")
            
            // Show specific message based on denied permissions
            val message = when {
                deniedPermissions.contains(Manifest.permission.CAMERA) -> 
                    "Camera permission is required for this app to function."
                deniedPermissions.contains(Manifest.permission.RECORD_AUDIO) -> 
                    "Audio recording permission is required for video recording."
                deniedPermissions.any { it.contains("MEDIA") } -> 
                    "Media access permission is required to save photos and videos."
                else -> "Required permissions were not granted. App cannot function properly."
            }
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            // Always show explanation dialog and try again
            showPermissionExplanationDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Create night vision overlay ImageView
        nightVisionImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create edge detection overlay ImageView
        edgeDetectionImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create Optical Flow overlay ImageView
        opticalFlowImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create color adjustment overlay ImageView
        colorAdjustmentImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create text translation overlay ImageView
        textTranslationImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create segmentation overlay ImageView
        segmentationImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id // Don't cover the controls
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create depth field overlay ImageView
        depthFieldImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create HD overlay ImageView (scale only, separate mode)
        hdImageView = ImageView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Create histogram view
        histogramView = HistogramView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(200, 150).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(16, 16, 0, 0) // Position in top left corner
            }
            visibility = View.VISIBLE // Always visible
        }

        // Create FPS view
        fpsView = TextView(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(0, 16, 16, 0) // Position in top right corner
            }
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.button_info_background)
            setPadding(16, 8, 16, 8)
            text = "FPS: 0"
            visibility = View.VISIBLE // Always visible
        }

        // Add the views to the layout in proper z-order
        val parentLayout = binding.viewFinder.parent as androidx.constraintlayout.widget.ConstraintLayout

        // Add overlay ImageViews to the layout
        parentLayout.addView(nightVisionImageView)
        parentLayout.addView(edgeDetectionImageView)
        parentLayout.addView(opticalFlowImageView)
        parentLayout.addView(colorAdjustmentImageView)
        parentLayout.addView(textTranslationImageView)
        parentLayout.addView(segmentationImageView)
        parentLayout.addView(depthFieldImageView)
        parentLayout.addView(hdImageView)
        parentLayout.addView(histogramView)
        parentLayout.addView(fpsView)

        // Create object detection overlay before adding it to the layout
        objectDetectionOverlay = ObjectDetectionOverlay(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id
            }
            visibility = View.GONE
        }
        
        // Add object detection overlay
        parentLayout.addView(objectDetectionOverlay)

        // Ensure controls container stays on top (use Constants for elevation)
        binding.controlsContainer.elevation = Constants.ELEVATION_CONTROLS
        histogramView.elevation = Constants.ELEVATION_HUD
        fpsView.elevation = Constants.ELEVATION_HUD
        objectDetectionOverlay.elevation = Constants.ELEVATION_OVERLAY
        edgeDetectionImageView.elevation = Constants.ELEVATION_EFFECT_OVERLAY
        hdImageView.elevation = Constants.ELEVATION_EFFECT_OVERLAY
        nightVisionImageView.elevation = Constants.ELEVATION_NIGHT_VISION

        // Check permissions immediately when app starts
        checkAndRequestPermissions()

        binding.photoButton.setOnClickListener {
            setMode(true)
        }
        binding.videoButton.setOnClickListener {
            setMode(false)
        }
        binding.nightVisionButton.setOnClickListener {
            if (visionState.mode == VisionMode.NIGHT_VISION) {
                setVisionMode(VisionMode.NONE)
            } else {
                setVisionMode(VisionMode.NIGHT_VISION)
                Toast.makeText(this, "Night Vision Enabled", Toast.LENGTH_SHORT).show()
            }
        }
        binding.imgButton.setOnClickListener {
            when (visionState.mode) {
                VisionMode.EDGE_DETECTION, VisionMode.COLOR_ADJUSTMENT, VisionMode.HD -> setVisionMode(VisionMode.NONE)
                else -> {
                    if (visionState.mode != VisionMode.NONE) setVisionMode(VisionMode.NONE)
                    toggleCategoryOverlay(VisionMode.Category.IMG)
                    setMainButtonActive(binding.imgUnderline, binding.imgOverlayRow.visibility == View.VISIBLE)
                }
            }
        }
        binding.imgEDButton.setOnClickListener {
            binding.imgOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.EDGE_DETECTION)
            Toast.makeText(this, "Edge Detection Enabled", Toast.LENGTH_SHORT).show()
        }
        binding.imgCOLButton.setOnClickListener {
            binding.imgOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.COLOR_ADJUSTMENT)
            Toast.makeText(this, "Color Adjustment Enabled", Toast.LENGTH_SHORT).show()
        }
        binding.imgHDButton.setOnClickListener {
            binding.imgOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.HD)
            Toast.makeText(this, "HD scale enabled", Toast.LENGTH_SHORT).show()
        }
        binding.robButton.setOnClickListener {
            when (visionState.mode) {
                VisionMode.OPTICAL_FLOW, VisionMode.SEGMENTATION, VisionMode.DEPTH_FIELD, VisionMode.OBJECT_DETECTION -> setVisionMode(VisionMode.NONE)
                else -> {
                    if (visionState.mode != VisionMode.NONE) setVisionMode(VisionMode.NONE)
                    toggleCategoryOverlay(VisionMode.Category.ROB)
                    setMainButtonActive(binding.robUnderline, binding.robOverlayRow.visibility == View.VISIBLE)
                }
            }
        }
        binding.robOFButton.setOnClickListener {
            binding.robOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.OPTICAL_FLOW)
            opticalFlowProcessor.clear()
            Toast.makeText(this, "Optical Flow Enabled", Toast.LENGTH_SHORT).show()
        }
        binding.robSEGButton.setOnClickListener {
            binding.robOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.SEGMENTATION)
            imageSegmentationProcessor.clear()
            Toast.makeText(this, "Image Segmentation Enabled", Toast.LENGTH_SHORT).show()
        }
        binding.robDFButton.setOnClickListener {
            binding.robOverlayRow.visibility = View.GONE
            setVisionMode(VisionMode.DEPTH_FIELD)
            Toast.makeText(this, "Depth Field Enabled", Toast.LENGTH_SHORT).show()
        }
        binding.txtButton.setOnClickListener {
            if (visionState.mode == VisionMode.TEXT_TRANSLATION) {
                setVisionMode(VisionMode.NONE)
            } else {
                setVisionMode(VisionMode.TEXT_TRANSLATION)
                Toast.makeText(this, "Text Translation Enabled", Toast.LENGTH_SHORT).show()
            }
        }
        binding.sphereButton.setOnClickListener {
            if (visionState.mode == VisionMode.SPHERE) {
                exitSphereMode()
            } else {
                enterSphereMode()
            }
        }
        binding.sphereDoneButton.setOnClickListener {
            sphereController?.finishCapture()
        }
        binding.sphereSaveButton.setOnClickListener {
            saveSphereImage()
        }
        binding.sphereCloseButton.setOnClickListener {
            exitSphereMode()
        }
        binding.sliderArrow.setOnClickListener {
            toggleSlider()
        }
        binding.cameraSwitchButton.setOnClickListener {
            switchCamera()
        }
        binding.galleryButton.setOnClickListener {
            startActivity(android.content.Intent(this, SavedMediaActivity::class.java))
        }
        binding.infoButton.setOnClickListener {
            toggleInfoPanel()
        }
        binding.contactUsButton.setOnClickListener {
            openContactEmail()
        }
        binding.recordButton.setOnClickListener {
            if (visionState.mode == VisionMode.SPHERE) {
                handleSphereRecordButton()
                return@setOnClickListener
            }
            if (isPhotoMode) {
                takePhoto()
            } else {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
        }

        // Set up sliders to update visionState.params
        binding.nightVisionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) visionState.params = visionState.params.copy(nightVisionIntensity = progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.edgeDetectionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) visionState.params = visionState.params.copy(edgeDetectionSensitivity = progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // HD scale for IMG: 0–100 → 50 = 1.0x, <50 = downsample, >50 = upsample
        binding.hdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(hdScaleSliderValue = progress)
                    val scaleStr = when {
                        progress < 50 -> String.format("%.2fx", 1f - (50 - progress) * 0.0198f) // ~0.01 at 0
                        progress > 50 -> String.format("%.1fx", 1f + (progress - 50) * 0.06f)   // 4x at 100
                        else -> "1.0x"
                    }
                    binding.hdScaleValueText.text = "Scale: $scaleStr"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.redSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(redAdjustment = progress / 100f)
                    binding.redValueText.text = "${progress}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.greenSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(greenAdjustment = progress / 100f)
                    binding.greenValueText.text = "${progress}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.blueSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(blueAdjustment = progress / 100f)
                    binding.blueValueText.text = "${progress}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Segmentation: segments 3–10 (progress 0–7), refinement 3–15 (progress 0–12)
        binding.segmentationClustersSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val k = (progress + 3).coerceIn(3, 10)
                    visionState.params = visionState.params.copy(segmentationNumClusters = k)
                    binding.segmentationClustersValueText.text = "$k"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.segmentationIterationsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val it = (progress + 3).coerceIn(3, 15)
                    visionState.params = visionState.params.copy(segmentationIterations = it)
                    binding.segmentationIterationsValueText.text = "$it"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Depth field: focus 0–100% (far to near)
        binding.depthFieldFocusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(depthFieldFocus = progress / 100f)
                    binding.depthFieldFocusValueText.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Optical flow: pixelation 2–16 (progress 0–14)
        binding.opticalFlowPixelationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val px = (progress + 2).coerceIn(2, 16)
                    visionState.params = visionState.params.copy(opticalFlowPixelation = px)
                    binding.opticalFlowPixelationValueText.text = "$px"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Optical flow: intensity 0–100%
        binding.opticalFlowIntensitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(opticalFlowIntensity = progress / 100f)
                    binding.opticalFlowIntensityValueText.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Translation: spinners show only downloaded language packs
        var downloadedLangs = listOf<TranslationLang>()

        fun refreshTranslationSpinners() {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    runOnUiThread {
                        val codes = models.mapNotNull { it.language }.toSet()
                        downloadedLangs = TranslationLanguages.ALL.filter { it.mlKitCode in codes }
                        val names = if (downloadedLangs.isEmpty()) listOf("(no packs installed)")
                                    else downloadedLangs.map { it.displayName }
                        val adapter = android.widget.ArrayAdapter(
                            this, R.layout.spinner_selected_item, names
                        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
                        val prevFrom = visionState.params.translationSourceLanguageCode
                        val prevTo = visionState.params.translationTargetLanguageCode
                        binding.translationFromSpinner.adapter = adapter
                        binding.translationToSpinner.adapter = adapter
                        val fromIdx = downloadedLangs.indexOfFirst { it.code == prevFrom }.coerceAtLeast(0)
                        val toIdx = downloadedLangs.indexOfFirst { it.code == prevTo }.let { if (it < 0 && downloadedLangs.size > 1) 1 else it.coerceAtLeast(0) }
                        binding.translationFromSpinner.setSelection(fromIdx, false)
                        binding.translationToSpinner.setSelection(toIdx, false)
                    }
                }
        }

        fun selectedLangCode(spinner: android.widget.Spinner): String? {
            val pos = spinner.selectedItemPosition
            return downloadedLangs.getOrNull(pos)?.code
        }

        val updateTranslationParams = {
            val fromCode = selectedLangCode(binding.translationFromSpinner)
            val toCode = selectedLangCode(binding.translationToSpinner)
            val effectiveTarget = if (fromCode != null && toCode != null && fromCode != toCode) toCode else null
            visionState.params = visionState.params.copy(
                translationSourceLanguageCode = fromCode,
                translationTargetLanguageCode = effectiveTarget
            )
        }

        val translationListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTranslationParams()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.translationFromSpinner.onItemSelectedListener = translationListener
        binding.translationToSpinner.onItemSelectedListener = translationListener

        refreshTranslationSpinners()

        binding.translationTextScaleSlider.progress = 20
        binding.translationTextScaleValueText.text = "1.0x"
        binding.translationBgAlphaSlider.progress = 100
        binding.translationBgAlphaValueText.text = "100%"

        binding.translationTextScaleSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 0.5f + progress * 0.025f
                binding.translationTextScaleValueText.text = String.format("%.1fx", scale)
                visionState.params = visionState.params.copy(translationTextScale = scale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.translationBgAlphaSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f
                binding.translationBgAlphaValueText.text = "${progress}%"
                visionState.params = visionState.params.copy(translationBackgroundAlpha = alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.translationConfidenceSlider.progress = 0
        binding.translationConfidenceValueText.text = "0%"
        binding.translationConfidenceSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val conf = progress / 100f
                binding.translationConfidenceValueText.text = "${progress}%"
                visionState.params = visionState.params.copy(translationMinConfidence = conf)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.translationMinLengthSlider.progress = 0
        binding.translationMinLengthValueText.text = "0"
        binding.translationMinLengthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.translationMinLengthValueText.text = "$progress"
                visionState.params = visionState.params.copy(translationMinLength = progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.translationSaveTxtButton.setOnClickListener {
            saveTranslationsToTxt()
        }
        binding.translationSavePdfButton.setOnClickListener {
            saveTranslationsToPdf()
        }

        var downloadProgressTimer: java.util.Timer? = null
        var activeDownloadModel: TranslateRemoteModel? = null
        var downloadCancelled = false

        fun showDownloadProgress(langName: String) {
            downloadCancelled = false
            binding.translationDownloadProgressContainer.visibility = View.VISIBLE
            binding.translationDownloadCancelButton.visibility = View.VISIBLE
            binding.translationDownloadStatusText.text = "Downloading $langName…"
            binding.translationDownloadProgressBar.isIndeterminate = false
            binding.translationDownloadProgressBar.max = 100
            binding.translationDownloadProgressBar.progress = 0
            downloadProgressTimer?.cancel()
            downloadProgressTimer = java.util.Timer().apply {
                var simulated = 0
                scheduleAtFixedRate(object : java.util.TimerTask() {
                    override fun run() {
                        simulated += when {
                            simulated < 30 -> 4
                            simulated < 60 -> 3
                            simulated < 80 -> 2
                            simulated < 90 -> 1
                            else -> 0
                        }
                        simulated = simulated.coerceAtMost(92)
                        runOnUiThread {
                            binding.translationDownloadProgressBar.progress = simulated
                            binding.translationDownloadStatusText.text = "Downloading $langName… $simulated%"
                        }
                    }
                }, 300, 400)
            }
        }

        fun finishDownloadProgress(langName: String, success: Boolean) {
            downloadProgressTimer?.cancel()
            downloadProgressTimer = null
            activeDownloadModel = null
            binding.translationDownloadCancelButton.visibility = View.GONE
            binding.translationDownloadProgressBar.progress = if (success) 100 else 0
            binding.translationDownloadStatusText.text = if (success) "$langName — done" else "$langName — failed"
            val delay = if (success) 1500L else 2500L
            binding.translationDownloadProgressBar.postDelayed({
                binding.translationDownloadProgressContainer.visibility = View.GONE
            }, delay)
        }

        binding.translationDownloadCancelButton.setOnClickListener {
            downloadCancelled = true
            downloadProgressTimer?.cancel()
            downloadProgressTimer = null
            val model = activeDownloadModel
            activeDownloadModel = null
            binding.translationDownloadCancelButton.visibility = View.GONE
            binding.translationDownloadStatusText.text = "Cancelling…"
            if (model != null) {
                RemoteModelManager.getInstance().deleteDownloadedModel(model)
                    .addOnCompleteListener {
                        runOnUiThread {
                            binding.translationDownloadProgressContainer.visibility = View.GONE
                            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
                            refreshTranslationSpinners()
                        }
                    }
            } else {
                binding.translationDownloadProgressContainer.visibility = View.GONE
            }
        }

        fun refreshLangPackList() {
            val manager = RemoteModelManager.getInstance()
            manager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    runOnUiThread {
                        val downloadedCodes = models.mapNotNull { it.language }.toSet()
                        val allLangs = TranslationLanguages.ALL
                        val accentColor = resources.getColor(R.color.accent_bright, theme)
                        val mutedColor = resources.getColor(R.color.text_secondary, theme)

                        val listAdapter = object : android.widget.BaseAdapter() {
                            override fun getCount() = allLangs.size
                            override fun getItem(pos: Int) = allLangs[pos]
                            override fun getItemId(pos: Int) = pos.toLong()
                            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                                val row = convertView ?: layoutInflater.inflate(R.layout.item_language_pack, parent, false)
                                val lang = allLangs[pos]
                                val isDl = lang.mlKitCode in downloadedCodes
                                row.findViewById<android.widget.TextView>(R.id.langStatusIcon).text = if (isDl) "✓" else ""
                                row.findViewById<android.widget.TextView>(R.id.langName).text = lang.displayName
                                val action = row.findViewById<android.widget.TextView>(R.id.langAction)
                                if (isDl) {
                                    action.text = "DELETE"
                                    action.setTextColor(mutedColor)
                                    val bg = android.graphics.drawable.GradientDrawable().apply {
                                        setColor(android.graphics.Color.TRANSPARENT)
                                        cornerRadius = 16f
                                        setStroke(1, mutedColor)
                                    }
                                    action.background = bg
                                } else {
                                    action.text = "GET"
                                    action.setTextColor(accentColor)
                                    val bg = android.graphics.drawable.GradientDrawable().apply {
                                        setColor(resources.getColor(R.color.glassy, theme))
                                        cornerRadius = 16f
                                        setStroke(1, accentColor)
                                    }
                                    action.background = bg
                                }

                                action.setOnClickListener {
                                    if (isDl) {
                                        AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                                            .setTitle("Delete ${lang.displayName}?")
                                            .setMessage("Remove this language pack. You can re-download it anytime.")
                                            .setPositiveButton("Delete") { _, _ ->
                                                val model = TranslateRemoteModel.Builder(lang.mlKitCode).build()
                                                manager.deleteDownloadedModel(model)
                                                    .addOnSuccessListener {
                                                        runOnUiThread {
                                                            Toast.makeText(this@MainActivity, "${lang.displayName} removed", Toast.LENGTH_SHORT).show()
                                                            refreshTranslationSpinners()
                                                            refreshLangPackList()
                                                        }
                                                    }
                                                    .addOnFailureListener { e ->
                                                        runOnUiThread {
                                                            Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                            }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    } else {
                                        val model = TranslateRemoteModel.Builder(lang.mlKitCode).build()
                                        activeDownloadModel = model
                                        showDownloadProgress(lang.displayName)
                                        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
                                        manager.download(model, conditions)
                                            .addOnSuccessListener {
                                                runOnUiThread {
                                                    if (!downloadCancelled) {
                                                        finishDownloadProgress(lang.displayName, true)
                                                        refreshTranslationSpinners()
                                                        refreshLangPackList()
                                                    }
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                runOnUiThread {
                                                    if (!downloadCancelled) {
                                                        finishDownloadProgress(lang.displayName, false)
                                                        Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                    }
                                }

                                return row
                            }
                        }

                        binding.langPackListView.adapter = listAdapter
                    }
                }
        }

        refreshLangPackList()

        binding.translationLangSettingsButton.setOnClickListener {
            refreshLangPackList()
            showLangSettingsPanel()
        }

        // Initialize params based on defaults
        updateTranslationParams()

        setMode(true)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize Object Detection processor
        objectDetectionProcessor = ObjectDetectionProcessor(this)
        
        // Initialize Edge Detection processor
        edgeDetectionProcessor = EdgeDetectionProcessor()
        
        // Initialize Optical Flow processor
        opticalFlowProcessor = OpticalFlowProcessor(this)
        
        // Initialize Image Segmentation processor
        imageSegmentationProcessor = ImageSegmentationProcessor(this)

        // Initialize Depth Estimation (MiDaS) processor for depth-of-field
        depthEstimationProcessor = DepthEstimationProcessor(this)

        // HD up/downsampling for IMG modes (Edge, Color)
        hdImageProcessor = HDImageProcessor(this)

        orientationTracker = OrientationTracker(this)

        visionPipeline = VisionAnalysisPipeline(
            getState = { visionState },
            processors = frameProcessors,
            objectDetectionProcessor = objectDetectionProcessor,
            applyFlip = { b -> if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) ImageUtils.flipBitmapHorizontally(b) else b }
        )

        // Ensure info button is always on top
        binding.infoButton.bringToFront()
    }

    private fun buildFrameProcessors(): Map<VisionMode, FrameProcessor> = mapOf(
        VisionMode.NIGHT_VISION to NightVisionFrameProcessor(),
        VisionMode.EDGE_DETECTION to EdgeDetectionFrameProcessor(edgeDetectionProcessor),
        VisionMode.COLOR_ADJUSTMENT to ColorAdjustmentFrameProcessor(),
        VisionMode.HD to HDFrameProcessor(hdImageProcessor),
        VisionMode.OPTICAL_FLOW to OpticalFlowFrameProcessor(opticalFlowProcessor),
        VisionMode.SEGMENTATION to SegmentationFrameProcessor(imageSegmentationProcessor),
        VisionMode.DEPTH_FIELD to DepthFieldFrameProcessor(depthEstimationProcessor),
        VisionMode.TEXT_TRANSLATION to TextTranslationFrameProcessor(this)
    )

    private fun toggleSlider() {
        visionState.isSliderExpanded = !visionState.isSliderExpanded
        if (visionState.isSliderExpanded) {
            binding.sliderArrow.setImageResource(R.drawable.ic_arrow_up)
            showActiveSlider()
        } else {
            binding.sliderArrow.setImageResource(R.drawable.ic_arrow_down)
            binding.nightVisionSliderContainer.visibility = View.GONE
            binding.edgeDetectionSliderContainer.visibility = View.GONE
            binding.colorSliderContainer.visibility = View.GONE
            binding.hdSliderContainer.visibility = View.GONE
            binding.segmentationSliderContainer.visibility = View.GONE
            binding.depthFieldSliderContainer.visibility = View.GONE
            binding.translationOptionsContainer.visibility = View.GONE
            binding.langSettingsPanel.visibility = View.GONE
        }
    }

    private fun setVisionMode(mode: VisionMode) {
        val prev = visionState.mode
        if (prev == VisionMode.SPHERE && mode != VisionMode.SPHERE) {
            sphereController?.resetSession()
            sphereController = null
            orientationTracker.stop()
            binding.sphereCaptureOverlay.visibility = View.GONE
            binding.sphereViewerOverlay.visibility = View.GONE
            binding.sphereViewerView.cleanup()
        }
        visionState.mode = mode
        updateVisionModeUI()
    }

    private fun setMainButtonActive(underline: View, active: Boolean) {
        underline.setBackgroundColor(if (active) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
    }

    private fun setSubButtonActive(btn: com.google.android.material.button.MaterialButton, active: Boolean) {
        btn.setTextColor(getColor(if (active) android.R.color.white else R.color.text_secondary))
    }

    private fun updateVisionModeUI() {
        val m = visionState.mode
        // Reset all main button underlines
        setMainButtonActive(binding.nvUnderline, false)
        setMainButtonActive(binding.imgUnderline, false)
        setMainButtonActive(binding.robUnderline, false)
        setMainButtonActive(binding.txtUnderline, false)
        setMainButtonActive(binding.sphereUnderline, false)
        // Reset all sub-mode buttons to inactive
        setSubButtonActive(binding.imgEDButton, false)
        setSubButtonActive(binding.imgCOLButton, false)
        setSubButtonActive(binding.imgHDButton, false)
        setSubButtonActive(binding.robOFButton, false)
        setSubButtonActive(binding.robSEGButton, false)
        setSubButtonActive(binding.robDFButton, false)
        // Hide all sliders and overlays
        binding.nightVisionSliderContainer.visibility = View.GONE
        binding.edgeDetectionSliderContainer.visibility = View.GONE
        binding.colorSliderContainer.visibility = View.GONE
        binding.hdSliderContainer.visibility = View.GONE
        binding.segmentationSliderContainer.visibility = View.GONE
        binding.depthFieldSliderContainer.visibility = View.GONE
        binding.opticalFlowSliderContainer.visibility = View.GONE
        binding.translationOptionsContainer.visibility = View.GONE
        binding.langSettingsPanel.visibility = View.GONE
        binding.infoButton.visibility = View.VISIBLE
        objectDetectionOverlay.visibility = View.GONE
        nightVisionImageView.visibility = View.GONE
        edgeDetectionImageView.visibility = View.GONE
        opticalFlowImageView.visibility = View.GONE
        colorAdjustmentImageView.visibility = View.GONE
        textTranslationImageView.visibility = View.GONE
        segmentationImageView.visibility = View.GONE
        depthFieldImageView.visibility = View.GONE
        hdImageView.visibility = View.GONE
        binding.imgOverlayRow.visibility = View.GONE
        binding.robOverlayRow.visibility = View.GONE
        // If this mode has sliders, auto-expand the slider panel so they are visible
        if (m.hasSlider) {
            visionState.isSliderExpanded = true
            binding.sliderArrow.setImageResource(R.drawable.ic_arrow_up)
        }
        // Highlight active category and show active overlay/slider
        when (m) {
            VisionMode.NONE -> { }
            VisionMode.NIGHT_VISION -> {
                setMainButtonActive(binding.nvUnderline, true)
                nightVisionImageView.visibility = View.VISIBLE
                showActiveSlider()
            }
            VisionMode.EDGE_DETECTION -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgEDButton, true)
                edgeDetectionImageView.visibility = View.VISIBLE
                showActiveSlider()
            }
            VisionMode.COLOR_ADJUSTMENT -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgCOLButton, true)
                colorAdjustmentImageView.visibility = View.VISIBLE
                showActiveSlider()
            }
            VisionMode.HD -> {
                setMainButtonActive(binding.imgUnderline, true)
                setSubButtonActive(binding.imgHDButton, true)
                hdImageView.visibility = View.VISIBLE
                binding.hdSlider.progress = visionState.params.hdScaleSliderValue
                val p = visionState.params.hdScaleSliderValue
                binding.hdScaleValueText.text = "Scale: " + when {
                    p < 50 -> String.format("%.2fx", 1f - (50 - p) * 0.0198f)
                    p > 50 -> String.format("%.1fx", 1f + (p - 50) * 0.06f)
                    else -> "1.0x"
                }
                showActiveSlider()
            }
            VisionMode.OPTICAL_FLOW -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robOFButton, true)
                opticalFlowImageView.visibility = View.VISIBLE
                opticalFlowProcessor.clear()
                binding.opticalFlowPixelationSlider.progress = (visionState.params.opticalFlowPixelation - 2).coerceIn(0, 14)
                binding.opticalFlowPixelationValueText.text = "${visionState.params.opticalFlowPixelation}"
                binding.opticalFlowIntensitySlider.progress = (visionState.params.opticalFlowIntensity * 100).toInt().coerceIn(0, 100)
                binding.opticalFlowIntensityValueText.text = "${(visionState.params.opticalFlowIntensity * 100).toInt()}%"
                showActiveSlider()
            }
            VisionMode.SEGMENTATION -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robSEGButton, true)
                segmentationImageView.visibility = View.VISIBLE
                binding.segmentationClustersSlider.progress = (visionState.params.segmentationNumClusters - 3).coerceIn(0, 7)
                binding.segmentationClustersValueText.text = "${visionState.params.segmentationNumClusters}"
                binding.segmentationIterationsSlider.progress = (visionState.params.segmentationIterations - 3).coerceIn(0, 12)
                binding.segmentationIterationsValueText.text = "${visionState.params.segmentationIterations}"
                showActiveSlider()
            }
            VisionMode.DEPTH_FIELD -> {
                setMainButtonActive(binding.robUnderline, true)
                setSubButtonActive(binding.robDFButton, true)
                depthFieldImageView.visibility = View.VISIBLE
                binding.depthFieldFocusSlider.progress = (visionState.params.depthFieldFocus * 100).toInt().coerceIn(0, 100)
                binding.depthFieldFocusValueText.text = "${(visionState.params.depthFieldFocus * 100).toInt()}%"
                showActiveSlider()
            }
            VisionMode.OBJECT_DETECTION -> {
                setMainButtonActive(binding.robUnderline, true)
                objectDetectionOverlay.visibility = View.VISIBLE
            }
            VisionMode.TEXT_TRANSLATION -> {
                setMainButtonActive(binding.txtUnderline, true)
                textTranslationImageView.visibility = View.VISIBLE
                showActiveSlider()
            }
            VisionMode.SPHERE -> {
                setMainButtonActive(binding.sphereUnderline, true)
            }
        }
        bringHudToFront()
    }

    private fun showActiveSlider() {
        if (!visionState.isSliderExpanded) return
        when (visionState.mode) {
            VisionMode.NIGHT_VISION -> binding.nightVisionSliderContainer.visibility = View.VISIBLE
            VisionMode.EDGE_DETECTION -> binding.edgeDetectionSliderContainer.visibility = View.VISIBLE
            VisionMode.COLOR_ADJUSTMENT -> binding.colorSliderContainer.visibility = View.VISIBLE
            VisionMode.HD -> binding.hdSliderContainer.visibility = View.VISIBLE
            VisionMode.OPTICAL_FLOW -> binding.opticalFlowSliderContainer.visibility = View.VISIBLE
            VisionMode.SEGMENTATION -> binding.segmentationSliderContainer.visibility = View.VISIBLE
            VisionMode.DEPTH_FIELD -> binding.depthFieldSliderContainer.visibility = View.VISIBLE
            VisionMode.TEXT_TRANSLATION -> binding.translationOptionsContainer.visibility = View.VISIBLE
            else -> { }
        }
    }

    private fun bringHudToFront() {
        binding.controlsContainer.bringToFront()
        histogramView.bringToFront()
        fpsView.bringToFront()
    }

    private fun toggleCategoryOverlay(category: VisionMode.Category) {
        val row = when (category) {
            VisionMode.Category.NV -> return
            VisionMode.Category.IMG -> binding.imgOverlayRow
            VisionMode.Category.ROB -> binding.robOverlayRow
            VisionMode.Category.TXT -> return
            VisionMode.Category.SPHERE -> return
            VisionMode.Category.NONE -> return
        }
        val visible = row.visibility == View.VISIBLE
        binding.imgOverlayRow.visibility = View.GONE
        binding.robOverlayRow.visibility = View.GONE
        setMainButtonActive(binding.imgUnderline, false)
        setMainButtonActive(binding.robUnderline, false)
        if (visible) return
        else {
            row.visibility = View.VISIBLE
            Handler(Looper.getMainLooper()).postDelayed({
                if (row.visibility == View.VISIBLE) {
                    row.visibility = View.GONE
                    setMainButtonActive(binding.imgUnderline, false)
                    setMainButtonActive(binding.robUnderline, false)
                }
            }, Constants.OVERLAY_AUTO_HIDE_MS)
        }
    }

    private fun setMode(photo: Boolean) {
        isPhotoMode = photo
        val greenBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.glassy))
        val redBg = ColorStateList.valueOf(Color.RED)
        val whiteIcon = ColorStateList.valueOf(Color.WHITE)
        if (photo) {
            binding.photoButton.setBackgroundTintList(redBg)
            binding.photoButton.imageTintList = whiteIcon
            binding.videoButton.setBackgroundTintList(greenBg)
            binding.videoButton.imageTintList = whiteIcon
            binding.recordButton.setImageResource(R.drawable.ic_record)
        } else {
            binding.photoButton.setBackgroundTintList(greenBg)
            binding.photoButton.imageTintList = whiteIcon
            binding.videoButton.setBackgroundTintList(redBg)
            binding.videoButton.imageTintList = whiteIcon
            binding.recordButton.setImageResource(R.drawable.ic_record)
        }
    }

    private fun takePhoto() {
        // When a vision overlay is active, capture that bitmap. Otherwise use ImageCapture (real camera capture).
        val processedBitmap = captureCurrentScreenContent()
        if (processedBitmap != null) {
            saveProcessedBitmapToGallery(processedBitmap)
        } else {
            // No overlay or preview can't be captured (e.g. no filters) -> use ImageCapture for a real photo
            takeProcessedPhoto()
        }
    }

    private fun captureCurrentScreenContent(): Bitmap? {
        try {
            val processedBitmap = when (visionState.mode) {
                VisionMode.EDGE_DETECTION -> {
                    val drawable = edgeDetectionImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.OPTICAL_FLOW -> {
                    val drawable = opticalFlowImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.NIGHT_VISION -> {
                    val drawable = nightVisionImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.COLOR_ADJUSTMENT -> {
                    val drawable = colorAdjustmentImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.SEGMENTATION -> {
                    val drawable = segmentationImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.DEPTH_FIELD -> {
                    val drawable = depthFieldImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.TEXT_TRANSLATION -> {
                    val drawable = textTranslationImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.HD -> {
                    val drawable = hdImageView.drawable
                    if (drawable is BitmapDrawable) drawable.bitmap.copy(drawable.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else null
                }
                VisionMode.SPHERE -> null // Sphere mode handles its own save flow
                else -> {
                    // No filters active: PreviewView draws camera to a Surface, not to Canvas, so draw(canvas) would be black.
                    // Caller will use ImageCapture (takeProcessedPhoto) for a real photo instead.
                    null
                }
            }
            
            return processedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen content: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun saveTranslationsToTxt() {
        val processor = frameProcessors[VisionMode.TEXT_TRANSLATION] as? TextTranslationFrameProcessor
        if (processor == null) {
            Toast.makeText(this, "Translation processor not available", Toast.LENGTH_SHORT).show()
            return
        }
        val translations = processor.getTranslationSnapshot()
        if (translations.isEmpty()) {
            Toast.makeText(this, "No translations to save yet", Toast.LENGTH_SHORT).show()
            return
        }

        val fromLang = visionState.params.translationSourceLanguageCode ?: "?"
        val toLang = visionState.params.translationTargetLanguageCode ?: "?"

        val sb = StringBuilder()
        sb.appendLine("PRISM AI Translation — $fromLang → $toLang")
        sb.appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis())}")
        sb.appendLine("=" .repeat(50))
        sb.appendLine()
        for ((i, pair) in translations.withIndex()) {
            sb.appendLine("[${i + 1}]")
            sb.appendLine("  Original:    ${pair.first}")
            sb.appendLine("  Translated:  ${pair.second}")
            sb.appendLine()
        }

        try {
            val name = Constants.MEDIA_FILENAME_PREFIX + "TRANS_" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, Constants.GALLERY_DOCUMENT_RELATIVE_PATH)
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                uri?.let { savedUri ->
                    contentResolver.openOutputStream(savedUri)?.use { stream ->
                        stream.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                    SavedMediaTracker.addDocument(this, savedUri)
                    Toast.makeText(this, "Translation saved: $name", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Translation text saved: $savedUri")
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS), "PrismAI")
                dir.mkdirs()
                val file = java.io.File(dir, name)
                file.writeText(sb.toString(), Charsets.UTF_8)
                val uri = Uri.fromFile(file)
                SavedMediaTracker.addDocument(this, uri)
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                Toast.makeText(this, "Translation saved: $name", Toast.LENGTH_SHORT).show()
            }
            processor.clearTranslationCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save translations: ${e.message}", e)
            Toast.makeText(this, "Failed to save translations", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveTranslationsToPdf() {
        val processor = frameProcessors[VisionMode.TEXT_TRANSLATION] as? TextTranslationFrameProcessor
        if (processor == null) {
            Toast.makeText(this, "Translation processor not available", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = processor.getFullTranslationSnapshot()
        val frame = processor.getLatestFrame()
        if (entries.isEmpty() || frame == null) {
            Toast.makeText(this, "No translations or frame available", Toast.LENGTH_SHORT).show()
            return
        }

        val fromLang = visionState.params.translationSourceLanguageCode ?: "?"
        val toLang = visionState.params.translationTargetLanguageCode ?: "?"

        Thread {
            try {
                val pdfDoc = android.graphics.pdf.PdfDocument()
                val pageW = 595  // A4 width in points (72dpi)
                val pageH = 842  // A4 height in points

                val margin = 36f
                val usableW = pageW - 2 * margin

                val titlePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK; textSize = 16f; isFakeBoldText = true; isAntiAlias = true
                }
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY; textSize = 10f; isFakeBoldText = true; isAntiAlias = true
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK; textSize = 10f; isAntiAlias = true
                }

                // --- Page 1: Full image with overlay ---
                val p1Info = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                val page1 = pdfDoc.startPage(p1Info)
                val c1 = page1.canvas

                c1.drawText("PRISM AI Translation — $fromLang → $toLang", margin, margin + 16f, titlePaint)
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(System.currentTimeMillis())
                c1.drawText(dateStr, margin, margin + 32f, labelPaint)

                val imgTop = margin + 48f
                val scale = usableW / frame.width.toFloat()
                val scaledH = frame.height * scale
                val imgRect = android.graphics.RectF(margin, imgTop, margin + usableW, imgTop + scaledH.coerceAtMost(pageH - imgTop - margin))

                c1.drawBitmap(frame, null, imgRect, null)

                val overlayPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE; isAntiAlias = true
                    setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
                }
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(160, 0, 0, 0)
                    style = android.graphics.Paint.Style.FILL
                }
                for (entry in entries) {
                    val cx = margin + (entry.bounds.exactCenterX() * scale)
                    val cy = imgTop + (entry.bounds.exactCenterY() * scale)
                    val targetH = entry.bounds.height() * scale
                    val targetW = entry.bounds.width() * scale

                    overlayPaint.textSize = (targetH * 0.7f).coerceIn(4f, 14f)
                    val measuredW = overlayPaint.measureText(entry.translated)
                    if (measuredW > targetW && measuredW > 0f) {
                        overlayPaint.textSize *= (targetW / measuredW)
                    }
                    overlayPaint.textSize = overlayPaint.textSize.coerceIn(3f, 14f)

                    val fm = overlayPaint.fontMetrics
                    val tH = fm.descent - fm.ascent
                    val tW = overlayPaint.measureText(entry.translated)
                    val pad = 2f
                    c1.save()
                    c1.rotate(entry.angleDegrees, cx, cy)
                    c1.drawRect(cx - tW / 2f - pad, cy - tH / 2f - pad, cx + tW / 2f + pad, cy + tH / 2f + pad, bgPaint)
                    c1.drawText(entry.translated, cx - tW / 2f, cy - fm.ascent - tH / 2f, overlayPaint)
                    c1.restore()
                }
                pdfDoc.finishPage(page1)

                // --- Page 2+: Cropped regions with original + translated ---
                var pageNum = 2
                var curY = margin
                var curPage: android.graphics.pdf.PdfDocument.Page? = null
                var curCanvas: android.graphics.Canvas? = null

                fun ensurePage(): android.graphics.Canvas {
                    if (curPage == null || curY > pageH - margin - 60f) {
                        curPage?.let { pdfDoc.finishPage(it) }
                        val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum++).create()
                        curPage = pdfDoc.startPage(info)
                        curCanvas = curPage!!.canvas
                        curY = margin
                    }
                    return curCanvas!!
                }

                for ((i, entry) in entries.withIndex()) {
                    val bl = entry.bounds.left.coerceIn(0, frame.width - 1)
                    val bt = entry.bounds.top.coerceIn(0, frame.height - 1)
                    val br = entry.bounds.right.coerceIn(bl + 1, frame.width)
                    val bb = entry.bounds.bottom.coerceIn(bt + 1, frame.height)
                    val cropped = android.graphics.Bitmap.createBitmap(frame, bl, bt, br - bl, bb - bt)

                    val cropScale = (usableW * 0.8f) / cropped.width.toFloat()
                    val cropH = cropped.height * cropScale
                    val cropW = cropped.width * cropScale
                    val origTextH = measureWrappedTextHeight(entry.original, usableW, textPaint)
                    val transTextH = measureWrappedTextHeight(entry.translated, usableW, textPaint)
                    val neededH = cropH + 14f + origTextH + 14f + transTextH + 20f

                    if (curY + neededH > pageH - margin) {
                        ensurePage()
                    }
                    val canvas = ensurePage()
                    drawEntryOnPage(canvas, i, entry, cropped, cropScale, cropH, cropW, margin, usableW, curY, labelPaint, textPaint)
                    curY += neededH + 16f
                    cropped.recycle()
                }
                curPage?.let { pdfDoc.finishPage(it) }

                // Write PDF to a temp file first, then copy to MediaStore
                val tempFile = java.io.File(cacheDir, "temp_translation.pdf")
                java.io.FileOutputStream(tempFile).use { pdfDoc.writeTo(it) }
                pdfDoc.close()

                val name = Constants.MEDIA_FILENAME_PREFIX + "PDF_" +
                    SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".pdf"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.RELATIVE_PATH, Constants.GALLERY_DOCUMENT_RELATIVE_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    uri?.let { savedUri ->
                        contentResolver.openOutputStream(savedUri)?.use { out ->
                            tempFile.inputStream().use { it.copyTo(out) }
                        }
                        val done = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(savedUri, done, null, null)
                        SavedMediaTracker.addPdf(this, savedUri)
                        runOnUiThread { Toast.makeText(this, "PDF saved: $name", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS), "PrismAI")
                    dir.mkdirs()
                    val dest = java.io.File(dir, name)
                    tempFile.copyTo(dest, overwrite = true)
                    val uri = Uri.fromFile(dest)
                    SavedMediaTracker.addPdf(this, uri)
                    sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    runOnUiThread { Toast.makeText(this, "PDF saved: $name", Toast.LENGTH_SHORT).show() }
                }
                tempFile.delete()
                frame.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save PDF: ${e.message}", e)
                runOnUiThread { Toast.makeText(this, "Failed to save PDF", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun drawEntryOnPage(
        canvas: android.graphics.Canvas,
        index: Int,
        entry: TextTranslationFrameProcessor.TranslationEntry,
        cropped: android.graphics.Bitmap,
        cropScale: Float,
        cropH: Float,
        cropW: Float,
        margin: Float,
        usableW: Float,
        y: Float,
        labelPaint: android.graphics.Paint,
        textPaint: android.graphics.Paint
    ) {
        val dst = android.graphics.RectF(margin, y, margin + cropW, y + cropH)
        canvas.drawBitmap(cropped, null, dst, null)

        var curY = y + cropH + 14f
        canvas.drawText("[${index + 1}] Original:", margin, curY, labelPaint)
        curY += 12f
        drawWrappedText(canvas, entry.original, margin, curY, usableW, textPaint)
        curY += measureWrappedTextHeight(entry.original, usableW, textPaint) + 10f

        canvas.drawText("Translated:", margin, curY, labelPaint)
        curY += 12f
        drawWrappedText(canvas, entry.translated, margin, curY, usableW, textPaint)
    }

    private fun measureWrappedTextHeight(
        text: String,
        maxWidth: Float,
        paint: android.graphics.Paint
    ): Float {
        if (maxWidth <= 0f || text.isEmpty()) return paint.fontMetrics.let { it.descent - it.ascent }
        val lineH = paint.fontMetrics.let { it.descent - it.ascent + it.leading }
        var lines = 0
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            start += count
            lines++
        }
        return (lines.coerceAtLeast(1)) * lineH
    }

    private fun drawWrappedText(
        canvas: android.graphics.Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        paint: android.graphics.Paint
    ) {
        if (maxWidth <= 0f) { canvas.drawText(text, x, startY, paint); return }
        var y = startY
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            canvas.drawText(text, start, start + count, x, y, paint)
            start += count
            y += paint.fontMetrics.let { it.descent - it.ascent + it.leading }
        }
    }

    private fun saveProcessedBitmapToGallery(bitmap: Bitmap) {
        try {
            val name = Constants.MEDIA_FILENAME_PREFIX + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Constants.GALLERY_IMAGE_RELATIVE_PATH)
                }
            }

            // Convert bitmap to JPEG bytes
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val imageBytes = outputStream.toByteArray()

            // Save to gallery
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { savedUri ->
                contentResolver.openOutputStream(savedUri)?.use { stream ->
                    stream.write(imageBytes)
                }
                SavedMediaTracker.addImage(this, savedUri)
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, savedUri))
                val msg = "Photo saved to gallery"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Processed photo saved: $savedUri")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save processed bitmap: ${e.message}")
            Toast.makeText(baseContext, "Failed to save photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takeOriginalPhoto() {
        val imageCapture = imageCapture ?: return
        
        // Always save processed image since we removed HD functionality
        Log.d(TAG, "Saving processed camera image")
        takeProcessedPhoto()
    }
    
    private fun takeRawPhoto() {
        val imageCapture = imageCapture ?: return
        val name = Constants.MEDIA_FILENAME_PREFIX + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Constants.GALLERY_IMAGE_RELATIVE_PATH)
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Raw photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Raw photo saved to gallery"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Raw photo capture succeeded: ${output.savedUri}")
                    output.savedUri?.let { uri ->
                        SavedMediaTracker.addImage(this@MainActivity, uri)
                        sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    }
                }
            }
        )
    }
    
    private fun takeProcessedPhoto() {
        val imageCapture = imageCapture ?: return
        val name = Constants.MEDIA_FILENAME_PREFIX + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Constants.GALLERY_IMAGE_RELATIVE_PATH)
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Processed photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Processed photo saved to gallery"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Processed photo capture succeeded: ${output.savedUri}")
                    output.savedUri?.let { uri ->
                        SavedMediaTracker.addImage(this@MainActivity, uri)
                        sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    }
                }
            }
        )
    }

    private fun startRecording() {
        val name = Constants.MEDIA_FILENAME_PREFIX + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Constants.GALLERY_VIDEO_RELATIVE_PATH)
            }
        }
        val recordWithFilter = visionState.mode != VisionMode.NONE && visionState.mode != VisionMode.OBJECT_DETECTION
        if (recordWithFilter) {
            // Record processed frames to temp file, then copy to MediaStore. Set isRecording only when encoder is ready.
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: cacheDir
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val tempFile = File(moviesDir, "prismai_rec_${System.currentTimeMillis()}.mp4")
            val recorder = ProcessedVideoRecorder(
                contentResolver = contentResolver,
                contentValues = contentValues,
                width = PROCESSED_VIDEO_WIDTH,
                height = PROCESSED_VIDEO_HEIGHT,
                tempFile = tempFile
            )
            processedVideoRecorder = recorder
            recorder.start(
                onReady = {
                    runOnUiThread {
                        isRecording = true
                        binding.recordButton.setImageResource(android.R.drawable.ic_media_pause)
                        Log.d(TAG, "Processed video recording started")
                    }
                },
                onFailed = {
                    runOnUiThread {
                        processedVideoRecorder = null
                        binding.recordButton.setImageResource(R.drawable.ic_record)
                        Toast.makeText(this, "Recording failed to start", Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            // No filter: use camera VideoCapture (raw stream, with audio)
            val videoCapture = videoCapture ?: return
            val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()
            isRecording = true
            binding.recordButton.setImageResource(android.R.drawable.ic_media_pause)
            activeRecording = videoCapture.output
                .prepareRecording(this, mediaStoreOutputOptions)
                .apply {
                    if (androidx.core.content.PermissionChecker.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == androidx.core.content.PermissionChecker.PERMISSION_GRANTED
                    ) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Video recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "Video capture ends with error: ${event.error}")
                            } else {
                                val msg = "Video saved to gallery"
                                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                                Log.d(TAG, "Video capture succeeded: ${event.outputResults.outputUri}")
                                event.outputResults.outputUri?.let { uri ->
                                    SavedMediaTracker.addVideo(this@MainActivity, uri)
                                    sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                                }
                            }
                            isRecording = false
                            binding.recordButton.setImageResource(R.drawable.ic_record)
                            activeRecording = null
                        }
                    }
                }
        }
    }

    private fun stopRecording() {
        val processed = processedVideoRecorder
        if (processed != null) {
            processedVideoRecorder = null
            processed.stop { uri ->
                runOnUiThread {
                    isRecording = false
                    binding.recordButton.setImageResource(R.drawable.ic_record)
                    if (uri != null) {
                        Toast.makeText(baseContext, "Video saved to gallery", Toast.LENGTH_SHORT).show()
                        SavedMediaTracker.addVideo(this@MainActivity, uri)
                        sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    } else {
                        Toast.makeText(baseContext, "Video not saved (no frames or error). Try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            activeRecording?.stop()
            isRecording = false
            binding.recordButton.setImageResource(R.drawable.ic_record)
        }
        activeRecording = null
    }

    private fun toggleInfoPanel() {
        if (binding.langSettingsPanel.visibility == View.VISIBLE) {
            hideLangSettingsPanel()
            return
        }
        val isVisible = binding.infoPanel.visibility == View.VISIBLE
        if (isVisible) {
            hideInfoPanel()
        } else {
            showInfoPanel()
        }
    }

    private fun showInfoPanel() {
        binding.infoPanel.visibility = View.VISIBLE
        binding.infoPanel.bringToFront()
        histogramView.visibility = View.VISIBLE
        fpsView.visibility = View.VISIBLE
        binding.controlsContainer.visibility = View.VISIBLE
        binding.infoButton.bringToFront()
        histogramView.bringToFront()
        fpsView.bringToFront()
        binding.controlsContainer.bringToFront()
        binding.infoButton.text = "✕"
    }

    private fun hideInfoPanel() {
        binding.infoPanel.visibility = View.GONE
        binding.infoButton.text = "ℹ"
    }

    private fun showLangSettingsPanel() {
        binding.langSettingsPanel.visibility = View.VISIBLE
        binding.langSettingsPanel.bringToFront()
        binding.controlsContainer.bringToFront()
        binding.infoButton.text = "✕"
        binding.infoButton.bringToFront()
    }

    private fun hideLangSettingsPanel() {
        binding.langSettingsPanel.visibility = View.GONE
        binding.infoButton.text = "ℹ"
    }

    private fun openContactEmail() {
        // To: inqueapps@gmail.com; Subject only. From is the user's email (not set).
        val uri = Uri.parse("mailto:inqueapps@gmail.com").buildUpon()
            .appendQueryParameter("subject", "PRISM AI feedback")
            .build()
        val intent = Intent(Intent.ACTION_SENDTO).apply { data = uri }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.contact_us_chooser)))
        } catch (e: Exception) {
            Toast.makeText(this, "No email app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchCamera() {
        if (isRecording) stopRecording()
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Get preview dimensions
            previewWidth = binding.viewFinder.width
            previewHeight = binding.viewFinder.height
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            
            // No setTargetResolution: use same FOV as preview so effects match normal view (not zoomed)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, visionPipeline.createAnalyzer { result ->
                        runOnUiThread {
                            result.fps?.let { fps -> fpsView.text = "FPS: %.1f".format(fps) }
                            result.histograms?.let { (r, g, b) -> histogramView.updateHistograms(r, g, b) }
                            result.detections?.let { dets ->
                                objectDetectionOverlay.setResults(dets, result.imageHeight, result.imageWidth)
                            }
                            if (visionState.mode == VisionMode.SEGMENTATION && result.processedBitmap == null) {
                                if (segmentationNullLogCounter++ % 45 == 0) {
                                    Log.w(TAG, "Segmentation: no bitmap from pipeline (native may have failed or lib not loaded)")
                                }
                            }
                            if (visionState.mode == VisionMode.SPHERE && sphereController?.state == SphereCaptureController.State.CAPTURING) {
                                result.rawBitmap?.let { raw ->
                                    if (sphereCaptureNextFrame) {
                                        sphereCaptureNextFrame = false
                                        sphereController?.captureFrame(raw)
                                    }
                                    sphereController?.feedContinuousFrame(raw)
                                    val coverage = SphereNative.getCoverage()
                                    binding.sphereCoverageText.text = "Coverage: ${(coverage * 100).toInt()}%"
                                    sphereController?.getPreviewBitmap()?.let { preview ->
                                        binding.spherePreviewImage.setImageBitmap(preview)
                                    }
                                }
                            }
                            result.processedBitmap?.let { bmp ->
                                when (visionState.mode) {
                                    VisionMode.NIGHT_VISION -> nightVisionImageView.setImageBitmap(bmp)
                                    VisionMode.EDGE_DETECTION -> edgeDetectionImageView.setImageBitmap(bmp)
                                    VisionMode.OPTICAL_FLOW -> opticalFlowImageView.setImageBitmap(bmp)
                                    VisionMode.COLOR_ADJUSTMENT -> colorAdjustmentImageView.setImageBitmap(bmp)
                                    VisionMode.HD -> hdImageView.setImageBitmap(bmp)
                                    VisionMode.TEXT_TRANSLATION -> textTranslationImageView.setImageBitmap(bmp)
                                    VisionMode.SEGMENTATION -> {
                                        segmentationImageView.setImageBitmap(bmp)
                                        segmentationImageView.bringToFront()
                                    }
                                    VisionMode.DEPTH_FIELD -> {
                                        depthFieldImageView.setImageBitmap(bmp)
                                        depthFieldImageView.bringToFront()
                                    }
                                    else -> { }
                                }
                                if (isRecording) processedVideoRecorder?.addFrame(bmp)
                            }
                        }
                    })
                }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
                )
                // Detect camera FOV for sphere projection via Camera2 API
                try {
                    val cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val cameraId = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "0" else "1"
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    val focalLengths = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorSize = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                        cameraFovH = (2.0 * Math.toDegrees(kotlin.math.atan((sensorSize.width / (2.0 * focalLengths[0])).toDouble()))).toFloat()
                        cameraFovV = (2.0 * Math.toDegrees(kotlin.math.atan((sensorSize.height / (2.0 * focalLengths[0])).toDouble()))).toFloat()
                        Log.i(TAG, "Camera FOV: ${cameraFovH}° x ${cameraFovV}°")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not detect camera FOV, using defaults: ${e.message}")
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        Log.d(TAG, "Requesting permissions: ${REQUIRED_PERMISSIONS.joinToString()}")
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMinimumRequiredPermissions(): Boolean {
        // At minimum, we need camera permission
        return ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionExplanationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Permissions Required")
            .setMessage("This app needs camera and media permissions to function properly:\n\n" +
                    "• Camera: To capture photos and videos\n" +
                    "• Audio: To record video with sound\n" +
                    "• Media: To save your photos and videos\n\n" +
                    "Please grant these permissions to use the app.")
            .setPositiveButton("Grant Permissions") { _, _ ->
                // Always try to request permissions again
                requestPermissions()
            }
            .setNeutralButton("Open Settings") { _, _ ->
                // Open app settings
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Permissions Needed")
            .setMessage("This app needs the following permissions to work:\n\n" +
                    "• Camera: To take photos and record videos\n" +
                    "• Audio: To record sound in videos\n" +
                    "• Media: To save your photos and videos\n\n" +
                    "These permissions are essential for the app to function.")
            .setPositiveButton("Grant Permissions") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "App cannot function without required permissions", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "Checking permissions...")
        
        if (allPermissionsGranted()) {
            Log.d(TAG, "All permissions already granted, starting camera")
            startCamera()
        } else if (hasMinimumRequiredPermissions()) {
            // At least camera permission is granted, start with limited functionality
            Log.d(TAG, "Minimum permissions granted, starting camera with limited functionality")
            startCamera()
            
            // Show a toast about limited functionality
            Toast.makeText(this, "Some features may be limited due to missing permissions", Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "No permissions granted, requesting permissions")
            
            // Always request permissions, regardless of previous denials
            val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
                
                // Always show rationale first to explain why permissions are needed
                showPermissionRationaleDialog(permissionsToRequest.toTypedArray())
            } else {
                Log.w(TAG, "No permissions to request but not all granted")
                showPermissionExplanationDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Always check permissions when resuming, even if previously denied
        if (allPermissionsGranted() && imageCapture == null) {
            Log.d(TAG, "Permissions granted on resume, starting camera")
            startCamera()
        } else if (hasMinimumRequiredPermissions() && imageCapture == null) {
            // At least camera permission is granted, start with limited functionality
            Log.d(TAG, "Minimum permissions granted on resume, starting camera with limited functionality")
            startCamera()
        } else if (imageCapture == null) {
            // No permissions granted, try requesting them again
            Log.d(TAG, "No permissions granted on resume, checking permissions again")
            checkAndRequestPermissions()
        }
    }

    private fun enterSphereMode() {
        setVisionMode(VisionMode.SPHERE)
        orientationTracker.start()
        sphereController = SphereCaptureController(
            orientationTracker,
            onCoverageUpdate = { coverage ->
                runOnUiThread {
                    binding.sphereCoverageText.text = "Coverage: ${(coverage * 100).toInt()}%"
                }
            },
            onPreviewReady = { equirect ->
                runOnUiThread {
                    binding.sphereCaptureOverlay.visibility = View.GONE
                    binding.sphereViewerOverlay.visibility = View.VISIBLE
                    binding.sphereViewerView.setLookDirection(0f, 0f, 90f)
                }
            },
            onFrameWarning = { warning ->
                runOnUiThread {
                    when (warning) {
                        SphereCaptureController.FrameWarning.NO_OVERLAP ->
                            Toast.makeText(this, "No overlap — move back toward previous frames", Toast.LENGTH_SHORT).show()
                        SphereCaptureController.FrameWarning.EXCESSIVE_SHIFT ->
                            Toast.makeText(this, "Slow down — too much movement between frames", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        // cameraFovH/V are computed from the sensor's physical width/height (landscape).
        // When the phone is in portrait, the frame bitmap is rotated 90° so its horizontal
        // axis corresponds to the sensor's HEIGHT dimension and vice versa — swap FOV values.
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val effectiveFovH = if (isPortrait) cameraFovV else cameraFovH
        val effectiveFovV = if (isPortrait) cameraFovH else cameraFovV
        Log.i(TAG, "Sphere session: portrait=$isPortrait, effectiveFOV=${effectiveFovH}° x ${effectiveFovV}°")
        sphereController?.startSession(effectiveFovH, effectiveFovV)
        binding.sphereCaptureOverlay.visibility = View.VISIBLE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereCoverageText.text = "Coverage: 0%"
        Toast.makeText(this, "Sphere mode — scan your surroundings", Toast.LENGTH_SHORT).show()
    }

    private fun exitSphereMode() {
        sphereController?.resetSession()
        sphereController = null
        orientationTracker.stop()
        binding.sphereCaptureOverlay.visibility = View.GONE
        binding.sphereViewerOverlay.visibility = View.GONE
        binding.sphereViewerView.cleanup()
        setVisionMode(VisionMode.NONE)
    }

    private fun handleSphereRecordButton() {
        val ctrl = sphereController ?: return
        if (ctrl.state != SphereCaptureController.State.CAPTURING) return

        if (isPhotoMode) {
            sphereCaptureNextFrame = true
            Toast.makeText(this, "Frame added to sphere", Toast.LENGTH_SHORT).show()
        } else {
            if (ctrl.isContinuousCapturing) {
                ctrl.stopContinuousCapture()
                binding.recordButton.setImageResource(R.drawable.ic_record)
                Toast.makeText(this, "Continuous capture stopped", Toast.LENGTH_SHORT).show()
            } else {
                ctrl.startContinuousCapture()
                binding.recordButton.setImageResource(R.drawable.ic_record)
                Toast.makeText(this, "Continuous capture started — pan slowly", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSphereImage() {
        val equirect = sphereController?.getFullEquirect()
        if (equirect == null) {
            Toast.makeText(this, "No sphere data to save", Toast.LENGTH_SHORT).show()
            return
        }
        saveSphereEquirectangular(equirect)
    }

    private fun saveSphereEquirectangular(bitmap: Bitmap) {
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
            val imageData = jpegBytes.toByteArray()

            // Inject XMP 360 metadata for Google Photos and other 360 viewers
            val xmpData = buildXmpMetadata(bitmap.width, bitmap.height)
            val finalData = injectXmpIntoJpeg(imageData, xmpData)

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { savedUri ->
                contentResolver.openOutputStream(savedUri)?.use { stream ->
                    stream.write(finalData)
                }
                SavedMediaTracker.addImage(this, savedUri)
                sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, savedUri))
                Toast.makeText(this, "360° photo saved", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Sphere photo saved: $savedUri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sphere: ${e.message}")
            Toast.makeText(this, "Failed to save sphere photo", Toast.LENGTH_SHORT).show()
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
        // XMP is stored in an APP1 segment: FF E1 [length] "http://ns.adobe.com/xap/1.0/\0" + xmp
        val namespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val payloadLen = namespace.size + xmpData.size
        val segmentLen = payloadLen + 2 // +2 for the length field itself

        val result = ByteArrayOutputStream(jpegData.size + segmentLen + 4)
        // Write SOI marker (first 2 bytes of JPEG: FF D8)
        result.write(jpegData, 0, 2)
        // Write APP1 marker
        result.write(0xFF)
        result.write(0xE1)
        result.write((segmentLen shr 8) and 0xFF)
        result.write(segmentLen and 0xFF)
        result.write(namespace)
        result.write(xmpData)
        // Write rest of JPEG
        result.write(jpegData, 2, jpegData.size - 2)
        return result.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        orientationTracker.stop()
        sphereController?.release()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PROCESSED_VIDEO_WIDTH = 1280
        private const val PROCESSED_VIDEO_HEIGHT = 720
        private const val USE_SIMPLE_YUV_CONVERSION = true // Set to false to use complex conversion
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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