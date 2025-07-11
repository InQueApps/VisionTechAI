package ab.inque.visiontechai

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Size

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isRecording = false
    private var isPhotoMode = true
    private var isNightVisionEnabled = false
    private var isMLNightVisionEnabled = false
    private var isObjectDetectionEnabled = false
    private var isTextTranslationEnabled = false
    private var isEdgeDetectionEnabled = false
    private var isOpticalFlowEnabled = false
    private var isColorAdjustmentEnabled = false
    private var isSegmentationEnabled = false
    private var nightVisionIntensity = 0.5f // Default 50%
    private var mlNightVisionSensitivity = 0.7f // Default 70%
    private var edgeDetectionSensitivity = 0.5f // Default 50%
    private var redAdjustment = 1.0f // Default 100%
    private var greenAdjustment = 1.0f // Default 100%
    private var blueAdjustment = 1.0f // Default 100%
    private var activeRecording: Recording? = null
    private lateinit var mlNightVisionProcessor: MLNightVisionProcessor
    private lateinit var objectDetectionProcessor: ObjectDetectionProcessor
    private lateinit var edgeDetectionProcessor: EdgeDetectionProcessor
    private lateinit var opticalFlowProcessor: OpticalFlowProcessor
    private lateinit var imageSegmentationProcessor: ImageSegmentationProcessor
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
    private lateinit var histogramView: HistogramView
    private lateinit var fpsView: TextView
    private lateinit var dualCameraContainer: LinearLayout
    private lateinit var frontCameraView: androidx.camera.view.PreviewView
    private lateinit var rearCameraView: androidx.camera.view.PreviewView
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0f
    private var isDualCameraMode = false
    private var isSliderExpanded = false
    // Split-screen dual camera variables
    private var splitScreenHandler: Handler? = null
    private var splitScreenRunnable: Runnable? = null
    private var splitScreenBackBitmap: Bitmap? = null
    private var splitScreenFrontBitmap: Bitmap? = null
    private var splitScreenActiveCamera = CameraSelector.DEFAULT_BACK_CAMERA
    private var splitScreenCapturing = false
    private var splitScreenCameraProvider: ProcessCameraProvider? = null
    private var supportsTrueDualCamera = false

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
            setBackgroundColor(Color.BLACK)
            setPadding(8, 4, 8, 4)
            text = "FPS: 0"
            visibility = View.VISIBLE // Always visible
        }

        // Create dual camera container
        dualCameraContainer = LinearLayout(this).apply {
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = binding.controlsContainer.id
            }
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Create front camera view (top half)
        frontCameraView = androidx.camera.view.PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // Create rear camera view (bottom half)
        rearCameraView = androidx.camera.view.PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // Add camera views to dual camera container
        dualCameraContainer.addView(frontCameraView)
        dualCameraContainer.addView(rearCameraView)

        // Add the views to the layout in proper z-order
        val parentLayout = binding.viewFinder.parent as androidx.constraintlayout.widget.ConstraintLayout

        // Add overlay ImageViews to the layout
        parentLayout.addView(nightVisionImageView)
        parentLayout.addView(edgeDetectionImageView)
        parentLayout.addView(opticalFlowImageView)
        parentLayout.addView(colorAdjustmentImageView)
        parentLayout.addView(textTranslationImageView)
        parentLayout.addView(segmentationImageView)
        parentLayout.addView(histogramView)
        parentLayout.addView(fpsView)
        parentLayout.addView(dualCameraContainer)

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

        // Ensure controls container stays on top
        binding.controlsContainer.elevation = 1000f
        histogramView.elevation = 500f
        fpsView.elevation = 500f
        objectDetectionOverlay.elevation = 200f
        edgeDetectionImageView.elevation = 150f
        nightVisionImageView.elevation = 100f
        dualCameraContainer.elevation = 50f

        // Check permissions immediately when app starts
        checkAndRequestPermissions()

        binding.photoButton.setOnClickListener {
            setMode(true)
        }
        binding.videoButton.setOnClickListener {
            setMode(false)
        }
        binding.nightVisionButton.setOnClickListener {
            Log.d(TAG, "NV button clicked - isNightVisionEnabled: $isNightVisionEnabled, isMLNightVisionEnabled: $isMLNightVisionEnabled")
            // If any night vision mode is active, disable it
            if (isNightVisionEnabled || isMLNightVisionEnabled) {
                disableAllNightVision()
            } else {
                // Otherwise show the overlay to select a mode
                toggleNightVisionOverlay()
            }
        }
        binding.nvRegularButton.setOnClickListener {
            selectRegularNightVision()
        }
        binding.nvMLButton.setOnClickListener {
            selectMLNightVision()
        }
        binding.imgButton.setOnClickListener {
            Log.d(TAG, "IMG button clicked - isEdgeDetectionEnabled: $isEdgeDetectionEnabled, isOpticalFlowEnabled: $isOpticalFlowEnabled, isColorAdjustmentEnabled: $isColorAdjustmentEnabled, isSegmentationEnabled: $isSegmentationEnabled")
            // If any IMG mode is active, disable it
            if (isEdgeDetectionEnabled || isOpticalFlowEnabled || isColorAdjustmentEnabled || isSegmentationEnabled) {
                disableAllIMG()
            } else {
                // Otherwise show the overlay to select a mode
                toggleIMGOverlay()
            }
        }
        binding.imgEDButton.setOnClickListener {
            selectEdgeDetection()
        }
        binding.imgCOLButton.setOnClickListener {
            selectColorAdjustment()
        }
        binding.robButton.setOnClickListener {
            Log.d(TAG, "ROB button clicked - isOpticalFlowEnabled: $isOpticalFlowEnabled, isSegmentationEnabled: $isSegmentationEnabled")
            // If any ROB mode is active, disable it
            if (isOpticalFlowEnabled || isSegmentationEnabled) {
                disableAllROB()
            } else {
                // Otherwise show the overlay to select a mode
                toggleROBOverlay()
            }
        }
        binding.robOFButton.setOnClickListener {
            selectOpticalFlow()
        }
        binding.robSEGButton.setOnClickListener {
            selectSegmentation()
        }
        binding.sliderArrow.setOnClickListener {
            toggleSlider()
        }
        binding.cameraSwitchButton.setOnClickListener {
            switchCamera()
        }

        binding.infoButton.setOnClickListener {
            toggleInfoPanel()
        }
        binding.closeInfoButton.setOnClickListener {
            hideInfoPanel()
        }
        binding.recordButton.setOnClickListener {
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

        // Set up night vision slider
        binding.nightVisionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    nightVisionIntensity = progress / 100f
                    Log.d(TAG, "Night vision intensity: $nightVisionIntensity")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set up ML night vision slider
        binding.mlNightVisionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mlNightVisionSensitivity = progress / 100f
                    Log.d(TAG, "ML night vision sensitivity: $mlNightVisionSensitivity")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set up edge detection slider
        binding.edgeDetectionSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    edgeDetectionSensitivity = progress / 100f
                    Log.d(TAG, "Edge detection sensitivity: $edgeDetectionSensitivity")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })



        // Set up color adjustment sliders
        binding.redSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    redAdjustment = progress / 100f
                    binding.redValueText.text = "${progress}%"
                    Log.d(TAG, "Red adjustment: $redAdjustment")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.greenSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    greenAdjustment = progress / 100f
                    binding.greenValueText.text = "${progress}%"
                    Log.d(TAG, "Green adjustment: $greenAdjustment")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.blueSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    blueAdjustment = progress / 100f
                    binding.blueValueText.text = "${progress}%"
                    Log.d(TAG, "Blue adjustment: $blueAdjustment")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setMode(true)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize ML Night Vision processor
        mlNightVisionProcessor = MLNightVisionProcessor()

        // Initialize Object Detection processor
        objectDetectionProcessor = ObjectDetectionProcessor(this)
        
        // Initialize Edge Detection processor
        edgeDetectionProcessor = EdgeDetectionProcessor()
        
        // Initialize Optical Flow processor
        opticalFlowProcessor = OpticalFlowProcessor(this)
        
        // Initialize Image Segmentation processor
        imageSegmentationProcessor = ImageSegmentationProcessor(this)
        
        // Ensure info button is always on top
        binding.infoButton.bringToFront()
    }

    private fun toggleSlider() {
        isSliderExpanded = !isSliderExpanded
        if (isSliderExpanded) {
            binding.sliderArrow.setImageResource(R.drawable.ic_arrow_up)
            // Show the currently active slider
            when {
                isNightVisionEnabled -> binding.nightVisionSliderContainer.visibility = View.VISIBLE
                isMLNightVisionEnabled -> binding.mlNightVisionSliderContainer.visibility = View.VISIBLE
                isEdgeDetectionEnabled -> binding.edgeDetectionSliderContainer.visibility = View.VISIBLE
                isColorAdjustmentEnabled -> binding.colorSliderContainer.visibility = View.VISIBLE
                else -> binding.nightVisionSliderContainer.visibility = View.VISIBLE // Default
            }
        } else {
            binding.sliderArrow.setImageResource(R.drawable.ic_arrow_down)
            // Hide all sliders
            binding.nightVisionSliderContainer.visibility = View.GONE
            binding.mlNightVisionSliderContainer.visibility = View.GONE
            binding.edgeDetectionSliderContainer.visibility = View.GONE
            binding.colorSliderContainer.visibility = View.GONE
        }
    }

    private fun disableAllAlgorithms() {
        // Disable all algorithm flags
        isNightVisionEnabled = false
        isMLNightVisionEnabled = false
        isEdgeDetectionEnabled = false
        isOpticalFlowEnabled = false
        isColorAdjustmentEnabled = false
        isSegmentationEnabled = false
        
        // Reset all button states
        binding.nightVisionButton.setStrokeColorResource(android.R.color.white)
        binding.imgButton.setStrokeColorResource(android.R.color.white)
        binding.robButton.setStrokeColorResource(android.R.color.white)
        binding.nightVisionButton.strokeWidth = 1
        binding.imgButton.strokeWidth = 1
        binding.robButton.strokeWidth = 1
        
        // Hide all sliders
        binding.nightVisionSliderContainer.visibility = View.GONE
        binding.mlNightVisionSliderContainer.visibility = View.GONE
        binding.edgeDetectionSliderContainer.visibility = View.GONE
        binding.colorSliderContainer.visibility = View.GONE
        
        // Hide all overlays
        objectDetectionOverlay.visibility = View.GONE
        nightVisionImageView.visibility = View.GONE
        edgeDetectionImageView.visibility = View.GONE
        opticalFlowImageView.visibility = View.GONE
        colorAdjustmentImageView.visibility = View.GONE
        textTranslationImageView.visibility = View.GONE
        segmentationImageView.visibility = View.GONE
        
        // Hide all overlay rows
        binding.nightVisionOverlayRow.visibility = View.GONE
        binding.imgOverlayRow.visibility = View.GONE
        binding.robOverlayRow.visibility = View.GONE
    }

    private fun showActiveSlider() {
        if (isSliderExpanded) {
            // Hide all sliders first
            binding.nightVisionSliderContainer.visibility = View.GONE
            binding.mlNightVisionSliderContainer.visibility = View.GONE
            binding.edgeDetectionSliderContainer.visibility = View.GONE
            binding.colorSliderContainer.visibility = View.GONE
            
            // Show only the active slider
            when {
                isNightVisionEnabled -> binding.nightVisionSliderContainer.visibility = View.VISIBLE
                isMLNightVisionEnabled -> binding.mlNightVisionSliderContainer.visibility = View.VISIBLE
                isEdgeDetectionEnabled -> binding.edgeDetectionSliderContainer.visibility = View.VISIBLE
                isColorAdjustmentEnabled -> binding.colorSliderContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleNightVisionOverlay() {
        val isOverlayVisible = binding.nightVisionOverlayRow.visibility == View.VISIBLE
        if (isOverlayVisible) {
            // Hide overlay
            binding.nightVisionOverlayRow.visibility = View.GONE
        } else {
            // Show overlay
            binding.nightVisionOverlayRow.visibility = View.VISIBLE
            // Hide overlay after 3 seconds if no selection is made
            Handler(Looper.getMainLooper()).postDelayed({
                if (binding.nightVisionOverlayRow.visibility == View.VISIBLE) {
                    binding.nightVisionOverlayRow.visibility = View.GONE
                }
            }, 3000)
        }
    }

    private fun selectRegularNightVision() {
        // Hide overlay
        binding.nightVisionOverlayRow.visibility = View.GONE
        
        // Toggle regular night vision
        isNightVisionEnabled = !isNightVisionEnabled
        if (isNightVisionEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable night vision
            isNightVisionEnabled = true
            binding.nightVisionButton.setStrokeColorResource(android.R.color.holo_green_light)
            binding.nightVisionButton.strokeWidth = 3
            binding.nightVisionSliderContainer.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.VISIBLE
            nightVisionImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Night Vision Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.nightVisionButton.setStrokeColorResource(android.R.color.white)
            binding.nightVisionButton.strokeWidth = 1
            binding.nightVisionSliderContainer.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            nightVisionImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Night Vision Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectMLNightVision() {
        // Hide overlay
        binding.nightVisionOverlayRow.visibility = View.GONE
        
        // Toggle ML night vision
        isMLNightVisionEnabled = !isMLNightVisionEnabled
        if (isMLNightVisionEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable ML night vision
            isMLNightVisionEnabled = true
            binding.nightVisionButton.setStrokeColorResource(android.R.color.holo_blue_light)
            binding.nightVisionButton.strokeWidth = 3
            binding.mlNightVisionSliderContainer.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.VISIBLE
            nightVisionImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "ML Night Vision Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.nightVisionButton.setStrokeColorResource(android.R.color.white)
            binding.nightVisionButton.strokeWidth = 1
            binding.mlNightVisionSliderContainer.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            nightVisionImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "ML Night Vision Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleIMGOverlay() {
        val isOverlayVisible = binding.imgOverlayRow.visibility == View.VISIBLE
        if (isOverlayVisible) {
            // Hide overlay
            binding.imgOverlayRow.visibility = View.GONE
        } else {
            // Show overlay
            binding.imgOverlayRow.visibility = View.VISIBLE
            // Hide overlay after 3 seconds if no selection is made
            Handler(Looper.getMainLooper()).postDelayed({
                if (binding.imgOverlayRow.visibility == View.VISIBLE) {
                    binding.imgOverlayRow.visibility = View.GONE
                }
            }, 3000)
        }
    }

    private fun toggleROBOverlay() {
        val isOverlayVisible = binding.robOverlayRow.visibility == View.VISIBLE
        if (isOverlayVisible) {
            // Hide overlay
            binding.robOverlayRow.visibility = View.GONE
        } else {
            // Show overlay
            binding.robOverlayRow.visibility = View.VISIBLE
            // Hide overlay after 3 seconds if no selection is made
            Handler(Looper.getMainLooper()).postDelayed({
                if (binding.robOverlayRow.visibility == View.VISIBLE) {
                    binding.robOverlayRow.visibility = View.GONE
                }
            }, 3000)
        }
    }

    private fun disableAllNightVision() {
        // Disable all night vision modes
        isNightVisionEnabled = false
        isMLNightVisionEnabled = false
        
        // Reset button state
        binding.nightVisionButton.setStrokeColorResource(android.R.color.white)
        binding.nightVisionButton.strokeWidth = 1
        binding.nightVisionButton.text = "NV"
        
        // Hide overlays and sliders
        binding.nightVisionOverlayRow.visibility = View.GONE
        binding.nightVisionSliderContainer.visibility = View.GONE
        binding.mlNightVisionSliderContainer.visibility = View.GONE
        nightVisionImageView.visibility = View.GONE
        
        // Show normal camera view
        binding.viewFinder.visibility = View.VISIBLE
        
        Log.d(TAG, "All night vision modes disabled")
    }

    private fun disableAllIMG() {
        // Disable all IMG modes
        isEdgeDetectionEnabled = false
        isColorAdjustmentEnabled = false
        
        // Reset button state
        binding.imgButton.setStrokeColorResource(android.R.color.white)
        binding.imgButton.strokeWidth = 1
        binding.imgButton.text = "IMG"
        
        // Hide overlays and sliders
        binding.imgOverlayRow.visibility = View.GONE
        binding.edgeDetectionSliderContainer.visibility = View.GONE
        binding.colorSliderContainer.visibility = View.GONE
        edgeDetectionImageView.visibility = View.GONE
        colorAdjustmentImageView.visibility = View.GONE
        
        // Show normal camera view
        binding.viewFinder.visibility = View.VISIBLE
        
        Log.d(TAG, "All IMG modes disabled")
    }

    private fun disableAllROB() {
        // Disable all ROB modes
        isOpticalFlowEnabled = false
        isSegmentationEnabled = false
        
        // Reset button state
        binding.robButton.setStrokeColorResource(android.R.color.white)
        binding.robButton.strokeWidth = 1
        binding.robButton.text = "ROB"
        
        // Hide overlays
        binding.robOverlayRow.visibility = View.GONE
        opticalFlowImageView.visibility = View.GONE
        segmentationImageView.visibility = View.GONE
        
        // Show normal camera view
        binding.viewFinder.visibility = View.VISIBLE
        
        Log.d(TAG, "All ROB modes disabled")
    }

    private fun selectOpticalFlow() {
        // Hide overlay
        binding.robOverlayRow.visibility = View.GONE
        
        // Toggle Optical Flow
        isOpticalFlowEnabled = !isOpticalFlowEnabled
        if (isOpticalFlowEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable Optical Flow
            isOpticalFlowEnabled = true
            binding.robButton.setStrokeColorResource(android.R.color.holo_green_light)
            binding.robButton.strokeWidth = 3
            binding.imgButton.setStrokeColorResource(android.R.color.white)
            binding.imgButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            opticalFlowImageView.visibility = View.VISIBLE
            
            // Clear previous frame to start fresh
            opticalFlowProcessor.clear()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Optical Flow Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.robButton.setStrokeColorResource(android.R.color.white)
            binding.robButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            opticalFlowImageView.visibility = View.GONE
            
            // Clear optical flow processor
            opticalFlowProcessor.clear()
            
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Optical Flow Disabled", Toast.LENGTH_SHORT).show()
        }
    }



    private fun selectEdgeDetection() {
        // Hide overlay
        binding.imgOverlayRow.visibility = View.GONE
        
        // Toggle edge detection
        isEdgeDetectionEnabled = !isEdgeDetectionEnabled
        if (isEdgeDetectionEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable edge detection
            isEdgeDetectionEnabled = true
            binding.imgButton.setStrokeColorResource(android.R.color.holo_orange_light)
            binding.imgButton.strokeWidth = 3
            binding.edgeDetectionSliderContainer.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.VISIBLE
            edgeDetectionImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Edge Detection Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.imgButton.setStrokeColorResource(android.R.color.white)
            binding.imgButton.strokeWidth = 1
            binding.edgeDetectionSliderContainer.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            edgeDetectionImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Edge Detection Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectColorAdjustment() {
        // Hide overlay
        binding.imgOverlayRow.visibility = View.GONE
        
        // Toggle color adjustment
        isColorAdjustmentEnabled = !isColorAdjustmentEnabled
        if (isColorAdjustmentEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable color adjustment
            isColorAdjustmentEnabled = true
            binding.imgButton.setStrokeColorResource(android.R.color.holo_blue_light)
            binding.imgButton.strokeWidth = 3
            binding.colorSliderContainer.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.VISIBLE
            colorAdjustmentImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Color Adjustment Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.imgButton.setStrokeColorResource(android.R.color.white)
            binding.imgButton.strokeWidth = 1
            binding.colorSliderContainer.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            colorAdjustmentImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Color Adjustment Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectSegmentation() {
        // Hide overlay
        binding.robOverlayRow.visibility = View.GONE
        
        // Toggle segmentation
        isSegmentationEnabled = !isSegmentationEnabled
        if (isSegmentationEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable segmentation
            isSegmentationEnabled = true
            binding.robButton.setStrokeColorResource(android.R.color.holo_purple)
            binding.robButton.strokeWidth = 3
            binding.imgButton.setStrokeColorResource(android.R.color.white)
            binding.imgButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            segmentationImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Image Segmentation Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.robButton.setStrokeColorResource(android.R.color.white)
            binding.robButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            segmentationImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Image Segmentation Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectObjectDetection() {
        // Hide overlay
        binding.robOverlayRow.visibility = View.GONE
        
        // Toggle object detection
        isObjectDetectionEnabled = !isObjectDetectionEnabled
        if (isObjectDetectionEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable object detection
            isObjectDetectionEnabled = true
            binding.robButton.setStrokeColorResource(android.R.color.holo_red_light)
            binding.robButton.strokeWidth = 3
            binding.viewFinder.visibility = View.VISIBLE
            objectDetectionOverlay.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Object Detection Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.robButton.setStrokeColorResource(android.R.color.white)
            binding.robButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            objectDetectionOverlay.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Object Detection Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectTextTranslation() {
        // Hide overlay
        binding.robOverlayRow.visibility = View.GONE
        
        // Toggle text translation
        isTextTranslationEnabled = !isTextTranslationEnabled
        if (isTextTranslationEnabled) {
            // Disable all other algorithms
            disableAllAlgorithms()
            
            // Enable text translation
            isTextTranslationEnabled = true
            binding.robButton.setStrokeColorResource(android.R.color.holo_green_light)
            binding.robButton.strokeWidth = 3
            binding.viewFinder.visibility = View.VISIBLE
            textTranslationImageView.visibility = View.VISIBLE
            
            // Show active slider if expanded
            showActiveSlider()
            
            // Ensure all UI elements remain visible and on top
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.bringToFront()
            histogramView.bringToFront()
            fpsView.bringToFront()
            Toast.makeText(this, "Text Translation Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.robButton.setStrokeColorResource(android.R.color.white)
            binding.robButton.strokeWidth = 1
            binding.viewFinder.visibility = View.VISIBLE
            textTranslationImageView.visibility = View.GONE
            // Ensure all UI elements remain visible
            histogramView.visibility = View.VISIBLE
            fpsView.visibility = View.VISIBLE
            binding.controlsContainer.visibility = View.VISIBLE
            Toast.makeText(this, "Text Translation Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setMode(photo: Boolean) {
        isPhotoMode = photo
        if (photo) {
            binding.photoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED))
            binding.videoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
            binding.recordButton.setImageResource(R.drawable.ic_record)
        } else {
            binding.photoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.WHITE))
            binding.videoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED))
            binding.recordButton.setImageResource(R.drawable.ic_record)
        }
    }

    private fun takePhoto() {
        // Capture the current screen content (including any active filters)
        val processedBitmap = captureCurrentScreenContent()
        
        if (processedBitmap != null) {
            // Save the processed bitmap to gallery
            saveProcessedBitmapToGallery(processedBitmap)
        } else {
            // Fallback to original camera capture
            takeOriginalPhoto()
        }
    }

    private fun captureCurrentScreenContent(): Bitmap? {
        try {
            // Get the current processed image based on active filters
            val processedBitmap = when {
                isEdgeDetectionEnabled -> {
                    // Get the edge detection image
                    val drawable = edgeDetectionImageView.drawable
                    if (drawable is BitmapDrawable) {
                        val bitmap = drawable.bitmap
                        Log.d(TAG, "Capturing edge detection image: ${bitmap.width}x${bitmap.height}")
                        bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    } else null
                }
                isOpticalFlowEnabled -> {
                    // Get the Optical Flow processed image
                    val drawable = opticalFlowImageView.drawable
                    if (drawable is BitmapDrawable) {
                        val bitmap = drawable.bitmap
                        Log.d(TAG, "Capturing Optical Flow image: ${bitmap.width}x${bitmap.height}")
                        bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    } else null
                }
                isNightVisionEnabled || isMLNightVisionEnabled -> {
                    // Get the night vision image
                    val drawable = nightVisionImageView.drawable
                    if (drawable is BitmapDrawable) {
                        val bitmap = drawable.bitmap
                        Log.d(TAG, "Capturing night vision image: ${bitmap.width}x${bitmap.height}")
                        bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    } else null
                }
                else -> {
                    // No filters active, capture the camera preview
                    val previewView = binding.viewFinder
                    if (previewView.width > 0 && previewView.height > 0) {
                        val bitmap = Bitmap.createBitmap(
                            previewView.width,
                            previewView.height,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        previewView.draw(canvas)
                        Log.d(TAG, "Capturing camera preview: ${bitmap.width}x${bitmap.height}")
                        bitmap
                    } else null
                }
            }
            
            return processedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen content: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun saveProcessedBitmapToGallery(bitmap: Bitmap) {
        try {
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
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
                
                // Notify media scanner
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
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
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
                    
                    // Notify media scanner to update gallery
                    output.savedUri?.let { uri ->
                        sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    }
                }
            }
        )
    }
    
    private fun takeProcessedPhoto() {
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
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
                    
                    // Notify media scanner to update gallery
                    output.savedUri?.let { uri ->
                        sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                    }
                }
            }
        )
    }

    private fun startRecording() {
        val videoCapture = videoCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }
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
                            
                            // Notify media scanner to update gallery
                            event.outputResults.outputUri?.let { uri ->
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

    private fun stopRecording() {
        activeRecording?.stop()
        isRecording = false
        binding.recordButton.setImageResource(R.drawable.ic_record)
    }

    private fun toggleDualCamera() {
        isDualCameraMode = !isDualCameraMode
        if (isDualCameraMode) {

            binding.viewFinder.visibility = View.GONE
            binding.splitScreenContainer.visibility = View.VISIBLE
            
            // Clear any existing images
            binding.backCameraImageView.setImageBitmap(null)
            binding.frontCameraImageView.setImageBitmap(null)
            
            startSplitScreenDualCamera()
            Toast.makeText(this, "Dual Camera Split-Screen Enabled", Toast.LENGTH_SHORT).show()
        } else {
            binding.splitScreenContainer.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            stopSplitScreenDualCamera()
            
            // Restart single camera
            startCamera()
            Toast.makeText(this, "Dual Camera Mode Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSplitScreenDualCamera() {
        splitScreenCapturing = true
        splitScreenHandler = Handler(Looper.getMainLooper())
        
        // Initialize separate camera provider for split screen
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            splitScreenCameraProvider = cameraProviderFuture.get()
            
            // Check dual camera support with the initialized provider
            checkDualCameraSupportWithProvider(splitScreenCameraProvider!!)
            
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkDualCameraSupportWithProvider(cameraProvider: ProcessCameraProvider) {
        try {
            // Check if both cameras are available
            val hasBackCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            
            if (hasBackCamera && hasFrontCamera) {
                // Both cameras exist, now test if we can bind them simultaneously
                supportsTrueDualCamera = testSimultaneousCameraBinding(cameraProvider)
                Log.d(TAG, "Dual camera support: $supportsTrueDualCamera")
            } else {
                supportsTrueDualCamera = false
                Log.d(TAG, "One or both cameras not available (Back: $hasBackCamera, Front: $hasFrontCamera)")
            }
            
            // Start the appropriate capture mode
            if (supportsTrueDualCamera) {
                startTrueDualCameraCapture()
                Log.d(TAG, "Split-screen dual camera started (true dual mode)")
            } else {
                // Fall back to sequential mode
                splitScreenActiveCamera = CameraSelector.DEFAULT_BACK_CAMERA
                startSequentialCameraCapture()
                Log.d(TAG, "Split-screen dual camera started (sequential mode)")
            }
            
        } catch (e: Exception) {
            supportsTrueDualCamera = false
            Log.e(TAG, "Error checking dual camera support: ${e.message}")
            // Fall back to sequential mode
            splitScreenActiveCamera = CameraSelector.DEFAULT_BACK_CAMERA
            startSequentialCameraCapture()
        }
    }

    private fun stopSplitScreenDualCamera() {
        splitScreenCapturing = false
        splitScreenHandler?.removeCallbacksAndMessages(null)
        splitScreenHandler = null
        splitScreenRunnable = null
        
        // Clean up camera provider
        splitScreenCameraProvider?.unbindAll()
        splitScreenCameraProvider = null
    }

    private fun startSequentialCameraCapture() {
        splitScreenRunnable = object : Runnable {
            override fun run() {
                if (!splitScreenCapturing) return
                
                captureCurrentCameraFrame()
                
                // Switch to other camera after a short delay
                splitScreenHandler?.postDelayed({
                    if (splitScreenCapturing) {
                        splitScreenActiveCamera = if (splitScreenActiveCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        captureCurrentCameraFrame()
                        
                        // Schedule next capture
                        splitScreenHandler?.postDelayed(this, 200) // Switch every 200ms
                    }
                }, 100)
            }
        }
        splitScreenRunnable?.run()
    }



    private fun testSimultaneousCameraBinding(cameraProvider: ProcessCameraProvider): Boolean {
        return try {
            // Create dummy use cases to test binding
            val backAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            val frontAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            // Try to bind both simultaneously
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, backAnalyzer
            )
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, frontAnalyzer
            )
            
            // If we get here, it worked
            cameraProvider.unbindAll()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Simultaneous camera binding failed: ${e.message}")
            cameraProvider.unbindAll()
            false
        }
    }

    private fun startTrueDualCameraCapture() {
        val cameraProvider = splitScreenCameraProvider ?: return
        
        try {
            // Create analyzers for both cameras
            val backAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(image: ImageProxy) {
                            if (!splitScreenCapturing) {
                                image.close()
                                return
                            }
                            
                            val bitmap = yuvToBitmap(image)
                            image.close()
                            
                            if (bitmap != null) {
                                runOnUiThread {
                                    splitScreenBackBitmap = bitmap
                                    binding.backCameraImageView.setImageBitmap(bitmap)
                                    Log.d(TAG, "Updated back camera frame (true dual)")
                                }
                            }
                        }
                    })
                }
            
            val frontAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(image: ImageProxy) {
                            if (!splitScreenCapturing) {
                                image.close()
                                return
                            }
                            
                            val bitmap = yuvToBitmap(image)
                            image.close()
                            
                            if (bitmap != null) {
                                runOnUiThread {
                                    splitScreenFrontBitmap = bitmap
                                    binding.frontCameraImageView.setImageBitmap(bitmap)
                                    Log.d(TAG, "Updated front camera frame (true dual)")
                                }
                            }
                        }
                    })
                }
            
            // Bind both cameras simultaneously
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, backAnalyzer
            )
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, frontAnalyzer
            )
            
            Log.d(TAG, "True dual camera capture started")
            
        } catch (e: Exception) {
            Log.e(TAG, "True dual camera capture error: ${e.message}")
            // Fall back to sequential mode
            supportsTrueDualCamera = false
            splitScreenActiveCamera = CameraSelector.DEFAULT_BACK_CAMERA
            startSequentialCameraCapture()
        }
    }

    private fun captureCurrentCameraFrame() {
        val cameraProvider = splitScreenCameraProvider ?: return
        
        try {
            // Unbind previous use cases to switch cameras
            cameraProvider.unbindAll()
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(image: ImageProxy) {
                            if (!splitScreenCapturing) {
                                image.close()
                                return
                            }
                            
                            val bitmap = yuvToBitmap(image)
                            image.close()
                            
                            if (bitmap != null) {
                                runOnUiThread {
                                    if (splitScreenActiveCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
                                        splitScreenBackBitmap = bitmap
                                        binding.backCameraImageView.setImageBitmap(bitmap)
                                        Log.d(TAG, "Updated back camera frame")
                                    } else {
                                        splitScreenFrontBitmap = bitmap
                                        binding.frontCameraImageView.setImageBitmap(bitmap)
                                        Log.d(TAG, "Updated front camera frame")
                                    }
                                }
                            }
                        }
                    })
                }
            
            // Bind to lifecycle
            cameraProvider.bindToLifecycle(
                this, splitScreenActiveCamera, imageAnalyzer
            )
            
            Log.d(TAG, "Capturing from camera: ${if (splitScreenActiveCamera == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Camera capture error: ${e.message}")
            // Continue with next camera even if this one fails
        }
    }

    private fun toggleInfoPanel() {
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
        // Ensure all UI elements remain visible
        histogramView.visibility = View.VISIBLE
        fpsView.visibility = View.VISIBLE
        binding.controlsContainer.visibility = View.VISIBLE
        binding.infoButton.bringToFront() // Keep info button on top
        histogramView.bringToFront()
        fpsView.bringToFront()
        binding.controlsContainer.bringToFront()
    }

    private fun hideInfoPanel() {
        binding.infoPanel.visibility = View.GONE
    }

    private fun switchCamera() {
        if (!isDualCameraMode) {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }
    }

    private fun startDualCamera() {
        // Software-based dual camera using Picture-in-Picture mode
        // This works on any device by showing main camera + small overlay of other camera
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            try {
                cameraProvider.unbindAll()
                
                // Main camera (current selection)
                val mainPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
                
                // Secondary camera (other camera) as small overlay
                val secondaryPreview = Preview.Builder()
                    .setTargetResolution(Size(320, 240)) // Small resolution for overlay
                    .build().also {
                        it.setSurfaceProvider(frontCameraView.surfaceProvider)
                    }
                
                // Determine which camera is main and which is secondary
                val mainCameraSelector = cameraSelector
                val secondaryCameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                // Create image analyzer for main camera
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also {
                        it.setAnalyzer(cameraExecutor, NightVisionAnalyzer { luma ->
                            Log.d(TAG, "Average luminosity: $luma")
                        })
                    }
                
                // Bind main camera with full functionality
                cameraProvider.bindToLifecycle(
                    this, mainCameraSelector, mainPreview, imageCapture, videoCapture, imageAnalyzer
                )
                
                // Bind secondary camera with just preview
                cameraProvider.bindToLifecycle(
                    this, secondaryCameraSelector, secondaryPreview
                )
                
                // Show the dual camera layout
                binding.viewFinder.visibility = View.VISIBLE
                dualCameraContainer.visibility = View.VISIBLE
                
                // Position the secondary camera view as a small overlay
                val overlayWidth = (binding.viewFinder.width * 0.25).toInt() // 25% of main view width
                val overlayHeight = (binding.viewFinder.height * 0.15).toInt() // 15% of main view height
                
                frontCameraView.layoutParams = LinearLayout.LayoutParams(
                    overlayWidth, overlayHeight
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    setMargins(0, 100, 20, 0) // Top-right corner with margins
                }
                
                // Add a close button for the secondary camera
                addSecondaryCameraCloseButton()
                
                // Make secondary camera view draggable
                setupDraggableSecondaryCamera()
                
                Log.d(TAG, "Software dual camera mode enabled successfully")
                
            } catch (exc: Exception) {
                Log.e(TAG, "Software dual camera failed, falling back to single camera", exc)
                Toast.makeText(this, "Dual camera mode not available, using single camera", Toast.LENGTH_SHORT).show()
                // Fallback to single camera
                isDualCameraMode = false
                binding.viewFinder.visibility = View.VISIBLE
                dualCameraContainer.visibility = View.GONE
                startCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun setupDraggableSecondaryCamera() {
        // Make the secondary camera view draggable
        frontCameraView.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Store initial touch position
                    view.tag = Pair(event.rawX, event.rawY)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Move the view based on touch movement
                    val initialPos = view.tag as? Pair<Float, Float>
                    if (initialPos != null) {
                        val deltaX = event.rawX - initialPos.first
                        val deltaY = event.rawY - initialPos.second
                        
                        view.translationX += deltaX
                        view.translationY += deltaY
                        
                        // Update stored position
                        view.tag = Pair(event.rawX, event.rawY)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // Snap to screen bounds
                    val parent = view.parent as? android.view.ViewGroup
                    if (parent != null) {
                        val maxX = parent.width - view.width
                        val maxY = parent.height - view.height
                        
                        view.translationX = view.translationX.coerceIn(0f, maxX.toFloat())
                        view.translationY = view.translationY.coerceIn(0f, maxY.toFloat())
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun addSecondaryCameraCloseButton() {
        // Create a close button for the secondary camera
        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.RED)
            gravity = android.view.Gravity.CENTER
            setPadding(8, 4, 8, 4)
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, 0, 0, 0)
            }
            
            setOnClickListener {
                // Hide secondary camera and return to single camera mode
                frontCameraView.visibility = View.GONE
                this.visibility = View.GONE
                isDualCameraMode = false
                startCamera()
                Toast.makeText(this@MainActivity, "Dual camera mode disabled", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Add close button to the dual camera container
        dualCameraContainer.addView(closeButton)
        
        // Position it on top of the secondary camera view
        closeButton.bringToFront()
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
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor, NightVisionAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
                )
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
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

    // Robust YUV to Bitmap conversion with multiple fallback methods
    private fun yuvToBitmap(image: ImageProxy): Bitmap? {
        try {
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            
            // Try multiple YUV formats since different cameras use different formats
            val formats = listOf(ImageFormat.NV21, ImageFormat.YV12, ImageFormat.YUV_420_888)
            
            for (format in formats) {
                try {
                    // Create YuvImage with current format
                    val yuvImage = YuvImage(
                        image.planes[0].buffer.array(),
                        format,
                        width,
                        height,
                        null
                    )
                    
                    // Convert to JPEG with high quality
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
                    val imageBytes = out.toByteArray()
                    
                    // Convert JPEG to Bitmap
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    
                    if (bitmap != null) {
                        Log.d(TAG, "Successfully converted YUV using format: $format")
                        
                        // Apply rotation if needed
                        return if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                        } else {
                            bitmap
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Format $format failed: ${e.message}")
                    continue
                }
            }
            
            // If all YuvImage formats fail, try manual conversion
            Log.w(TAG, "All YuvImage formats failed, trying manual conversion")
            return tryManualYuvConversion(image)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV to bitmap: ${e.message}")
            e.printStackTrace()
            
            // Last resort: try manual conversion
            return tryManualYuvConversion(image)
        }
    }
    
    // Manual YUV conversion as fallback with better error handling
    private fun tryManualYuvConversion(image: ImageProxy): Bitmap? {
        try {
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            
            Log.d(TAG, "Starting manual YUV conversion for ${width}x${height}")
            
            // Get YUV planes
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            
            // Get plane info for debugging
            val yStride = yPlane.rowStride
            val uStride = uPlane.rowStride
            val vStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            
            Log.d(TAG, "YUV plane info - Y stride: $yStride, U stride: $uStride, V stride: $vStride")
            Log.d(TAG, "UV pixel stride - U: $uPixelStride, V: $vPixelStride")
            
            // Create bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            
            // Get Y values (luminance)
            val yBuffer = yPlane.buffer
            yBuffer.rewind()
            val yValues = ByteArray(yBuffer.remaining())
            yBuffer.get(yValues)
            
            Log.d(TAG, "Y buffer size: ${yValues.size}, expected: ${width * height}")
            
            // Get U and V values (chrominance)
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            uBuffer.rewind()
            vBuffer.rewind()
            val uValues = ByteArray(uBuffer.remaining())
            val vValues = ByteArray(vBuffer.remaining())
            uBuffer.get(uValues)
            vBuffer.get(vValues)
            
            Log.d(TAG, "U buffer size: ${uValues.size}, V buffer size: ${vValues.size}")
            
            // Manual YUV to RGB conversion with proper UV sampling
            var pixelIndex = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Get Y value
                    val yIndex = y * width + x
                    val yValue = if (yIndex < yValues.size) yValues[yIndex].toInt() and 0xFF else 0
                    
                    // Get U and V values with proper stride handling
                    val uvRow = y / 2
                    val uvCol = x / 2
                    
                    // Calculate UV indices with proper stride
                    val uIndex = uvRow * uStride + uvCol * uPixelStride
                    val vIndex = uvRow * vStride + uvCol * vPixelStride
                    
                    val uValue = if (uIndex < uValues.size) uValues[uIndex].toInt() and 0xFF else 128
                    val vValue = if (vIndex < vValues.size) vValues[vIndex].toInt() and 0xFF else 128
                    
                    // YUV to RGB conversion (BT.601)
                    val r = yValue + 1.402 * (vValue - 128)
                    val g = yValue - 0.344 * (uValue - 128) - 0.714 * (vValue - 128)
                    val b = yValue + 1.772 * (uValue - 128)
                    
                    val red = r.coerceIn(0.0, 255.0).toInt()
                    val green = g.coerceIn(0.0, 255.0).toInt()
                    val blue = b.coerceIn(0.0, 255.0).toInt()
                    
                    pixels[pixelIndex++] = Color.rgb(red, green, blue)
                }
            }
            
            Log.d(TAG, "Manual conversion completed, setting pixels")
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            // Apply rotation if needed
            return if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            } else {
                bitmap
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Manual YUV conversion failed: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    


    private inner class NightVisionAnalyzer(private val listener: (Double) -> Unit) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()
            listener(luma)

            // Update FPS counter
            frameCount++
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFpsTime >= 1000) {
                currentFps = frameCount * 1000f / (currentTime - lastFpsTime)
                frameCount = 0
                lastFpsTime = currentTime
                
                runOnUiThread {
                    fpsView.text = "FPS: %.1f".format(currentFps)
                }
            }

            // Always calculate and display histograms
            val bitmap = yuvToBitmap(image)
            if (bitmap != null) {
                val (redHistogram, greenHistogram, blueHistogram) = calculateHistograms(bitmap)
                
                // Debug: Log histogram differences to verify color channels are distinct
                val redMax = redHistogram.maxOrNull() ?: 0
                val greenMax = greenHistogram.maxOrNull() ?: 0
                val blueMax = blueHistogram.maxOrNull() ?: 0
                
                if (frameCount % 30 == 0) { // Log every 30 frames to avoid spam
                    Log.d(TAG, "Histogram max values - Red: $redMax, Green: $greenMax, Blue: $blueMax")
                    
                    // Check if histograms are identical (which would indicate a problem)
                    val redSum = redHistogram.sum()
                    val greenSum = greenHistogram.sum()
                    val blueSum = blueHistogram.sum()
                    
                    if (redSum == greenSum && greenSum == blueSum) {
                        Log.w(TAG, "Warning: All histogram sums are identical - color conversion may not be working properly")
                    }
                    
                    // Sample a few pixels to verify color conversion
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    
                    if (pixels.isNotEmpty()) {
                        val samplePixel = pixels[pixels.size / 2] // Sample middle pixel
                        val r = Color.red(samplePixel)
                        val g = Color.green(samplePixel)
                        val b = Color.blue(samplePixel)
                        Log.d(TAG, "Sample pixel RGB: R=$r, G=$g, B=$b")
                        
                        // Check if all channels are the same (indicating grayscale)
                        if (r == g && g == b) {
                            Log.w(TAG, "Warning: Sample pixel is grayscale - color conversion may not be working")
                        }
                    }
                }
                
                runOnUiThread {
                    histogramView.updateHistograms(redHistogram, greenHistogram, blueHistogram)
                }
                
                // Run object detection if enabled
                if (isObjectDetectionEnabled) {
                    val detections = objectDetectionProcessor.detectObjects(bitmap)
                    runOnUiThread {
                        objectDetectionOverlay.setResults(detections, bitmap.height, bitmap.width)
                    }
                }
            }

            if (isNightVisionEnabled || isMLNightVisionEnabled) {
                val enhancedBitmap = if (isMLNightVisionEnabled) {
                    // Apply ML-based night vision processing
                    val inputBitmap = yuvToBitmap(image)
                    if (inputBitmap != null) {
                        mlNightVisionProcessor.processMLNightVision(inputBitmap, mlNightVisionSensitivity)
                    } else {
                        null
                    }
                } else {
                    // Apply regular histogram equalization for night vision with intensity control
                    applyHistogramEqualization(image)
                }
                
                if (enhancedBitmap != null) {
                    // Update the night vision overlay with enhanced image
                    runOnUiThread {
                        nightVisionImageView.setImageBitmap(applyCameraFlip(enhancedBitmap))
                    }
                } else {
                    Log.w(TAG, "Night vision processing failed, showing original preview")
                    // Fallback: show original preview if processing fails
                    runOnUiThread {
                        binding.viewFinder.visibility = View.VISIBLE
                        nightVisionImageView.visibility = View.GONE
                    }
                }
            }

            if (isEdgeDetectionEnabled) {
                val inputBitmap = yuvToBitmap(image)
                if (inputBitmap != null) {
                    val edgeBitmap = edgeDetectionProcessor.detectEdges(inputBitmap, edgeDetectionSensitivity)
                    runOnUiThread {
                        edgeDetectionImageView.setImageBitmap(applyCameraFlip(edgeBitmap))
                    }
                } else {
                    Log.w(TAG, "Edge detection processing failed")
                    runOnUiThread {
                        binding.viewFinder.visibility = View.VISIBLE
                        edgeDetectionImageView.visibility = View.GONE
                    }
                }
            }

            if (isOpticalFlowEnabled) {
                val inputBitmap = yuvToBitmap(image)
                if (inputBitmap != null) {
                    val opticalFlowBitmap = opticalFlowProcessor.processOpticalFlow(inputBitmap)
                    runOnUiThread {
                        opticalFlowImageView.setImageBitmap(applyCameraFlip(opticalFlowBitmap))
                    }
                } else {
                    Log.w(TAG, "Optical flow processing failed")
                }
            }

            if (isColorAdjustmentEnabled) {
                val inputBitmap = yuvToBitmap(image)
                if (inputBitmap != null) {
                    val colorAdjustedBitmap = applyColorAdjustment(inputBitmap)
                    runOnUiThread {
                        colorAdjustmentImageView.setImageBitmap(applyCameraFlip(colorAdjustedBitmap))
                    }
                } else {
                    Log.w(TAG, "Color adjustment processing failed")
                }
            }

            if (isTextTranslationEnabled) {
                val inputBitmap = yuvToBitmap(image)
                if (inputBitmap != null) {
                    val translatedBitmap = processTextTranslation(inputBitmap)
                    runOnUiThread {
                        textTranslationImageView.setImageBitmap(applyCameraFlip(translatedBitmap))
                    }
                } else {
                    Log.w(TAG, "Text translation processing failed")
                }
            }

            if (isSegmentationEnabled) {
                val inputBitmap = yuvToBitmap(image)
                if (inputBitmap != null) {
                    // Log the preview dimensions
                    val previewWidth = binding.viewFinder.width
                    val previewHeight = binding.viewFinder.height
                    Log.d(TAG, "Preview dimensions: ${previewWidth}x${previewHeight}")
                    Log.d(TAG, "Input bitmap dimensions: ${inputBitmap.width}x${inputBitmap.height}")
                    
                    val segmentationOverlay = imageSegmentationProcessor.segmentImage(inputBitmap)
                    if (segmentationOverlay != null) {
                        runOnUiThread {
                            segmentationImageView.setImageBitmap(applyCameraFlip(segmentationOverlay))
                        }
                    } else {
                        Log.w(TAG, "Segmentation processing failed")
                    }
                } else {
                    Log.w(TAG, "Segmentation processing failed - could not convert YUV to bitmap")
                }
            }

            image.close()
        }

        private fun calculateHistograms(bitmap: Bitmap): Triple<IntArray, IntArray, IntArray> {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Separate RGB channels
            val redChannel = IntArray(pixels.size)
            val greenChannel = IntArray(pixels.size)
            val blueChannel = IntArray(pixels.size)
            
            for (i in pixels.indices) {
                redChannel[i] = Color.red(pixels[i])
                greenChannel[i] = Color.green(pixels[i])
                blueChannel[i] = Color.blue(pixels[i])
            }
            
            // Calculate histograms for each channel
            val redHistogram = calculateHistogram(redChannel)
            val greenHistogram = calculateHistogram(greenChannel)
            val blueHistogram = calculateHistogram(blueChannel)
            
            return Triple(redHistogram, greenHistogram, blueHistogram)
        }

        private fun applyHistogramEqualization(image: ImageProxy): Bitmap? {
            try {
                // Convert YUV to RGB bitmap using proper conversion
                val bitmap = yuvToBitmap(image)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert YUV to bitmap")
                    return null
                }
                
                Log.d(TAG, "Successfully converted YUV to bitmap: ${bitmap.width}x${bitmap.height}")
                
                // Apply histogram equalization to the bitmap
                val enhancedBitmap = histogramEqualizationRGB(bitmap)
                
                Log.d(TAG, "Night vision enhancement applied with intensity: $nightVisionIntensity")
                return enhancedBitmap
                
            } catch (e: Exception) {
                Log.e(TAG, "Error applying histogram equalization: ${e.message}")
                e.printStackTrace()
                return null
            }
        }

        private fun histogramEqualizationRGB(bitmap: Bitmap): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Get histograms using the shared function
            val (redHistogram, greenHistogram, blueHistogram) = calculateHistograms(bitmap)
            
            // Calculate CDF for each channel
            val redCdf = calculateCDF(redHistogram)
            val greenCdf = calculateCDF(greenHistogram)
            val blueCdf = calculateCDF(blueHistogram)
            
            // Apply histogram equalization with intensity control
            val equalizedPixels = IntArray(pixels.size)
            for (i in pixels.indices) {
                val originalRed = Color.red(pixels[i])
                val originalGreen = Color.green(pixels[i])
                val originalBlue = Color.blue(pixels[i])
                
                // Apply equalization with intensity control
                val equalizedRed = applyEqualizationWithIntensity(originalRed, redCdf, redHistogram)
                val equalizedGreen = applyEqualizationWithIntensity(originalGreen, greenCdf, greenHistogram)
                val equalizedBlue = applyEqualizationWithIntensity(originalBlue, blueCdf, blueHistogram)
                
                equalizedPixels[i] = Color.rgb(equalizedRed, equalizedGreen, equalizedBlue)
            }
            
            val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            enhancedBitmap.setPixels(equalizedPixels, 0, width, 0, 0, width, height)
            return enhancedBitmap
        }

        private fun calculateHistogram(channel: IntArray): IntArray {
            val histogram = IntArray(256)
            for (value in channel) {
                histogram[value]++
            }
            return histogram
        }

        private fun calculateCDF(histogram: IntArray): FloatArray {
            val cdf = FloatArray(256)
            cdf[0] = histogram[0].toFloat()
            for (i in 1 until 256) {
                cdf[i] = cdf[i - 1] + histogram[i]
            }
            return cdf
        }

        private fun applyEqualizationWithIntensity(originalValue: Int, cdf: FloatArray, histogram: IntArray): Int {
            val totalPixels = histogram.sum()
            if (totalPixels == 0) return originalValue
            
            // Find non-zero minimum for better equalization
            val cdfMin = cdf.first { it > 0 }
            
            // Calculate normalized CDF
            val normalizedCdf = if (totalPixels - cdfMin > 0) {
                (cdf[originalValue] - cdfMin) / (totalPixels - cdfMin)
            } else {
                0f
            }
            
            // Apply intensity control: blend between original and equalized values
            val equalizedValue = (normalizedCdf * 255).toInt()
            val blendedValue = (originalValue * (1 - nightVisionIntensity) + equalizedValue * nightVisionIntensity).toInt()
            
            return blendedValue.coerceIn(0, 255)
        }

        private fun applyColorAdjustment(bitmap: Bitmap): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Apply color adjustments
            val adjustedPixels = IntArray(pixels.size)
            for (i in pixels.indices) {
                val originalRed = Color.red(pixels[i])
                val originalGreen = Color.green(pixels[i])
                val originalBlue = Color.blue(pixels[i])
                val originalAlpha = Color.alpha(pixels[i])

                // Apply color adjustments (0.0 = no color, 2.0 = double intensity)
                val adjustedRed = (originalRed * redAdjustment).toInt().coerceIn(0, 255)
                val adjustedGreen = (originalGreen * greenAdjustment).toInt().coerceIn(0, 255)
                val adjustedBlue = (originalBlue * blueAdjustment).toInt().coerceIn(0, 255)

                adjustedPixels[i] = Color.argb(originalAlpha, adjustedRed, adjustedGreen, adjustedBlue)
            }

            val adjustedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            adjustedBitmap.setPixels(adjustedPixels, 0, width, 0, 0, width, height)
            return adjustedBitmap
        }

        private fun processTextTranslation(bitmap: Bitmap): Bitmap {
            // For now, implement a simple text detection and translation simulation
            // In a real implementation, you would use ML models for OCR and translation
            
            try {
                // Create a copy of the original bitmap
                val resultBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(resultBitmap)
                val paint = Paint().apply {
                    color = Color.RED
                    textSize = 40f
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }

                // Simulate text detection and translation
                // In a real implementation, this would use OCR to detect text
                // and then translate it using a translation model
                
                // For demonstration, we'll add some sample translated text overlays
                val sampleTexts = listOf(
                    "Hello → Hola",
                    "Good morning → Buenos días",
                    "Thank you → Gracias",
                    "你好 (Hello)",
                    "早上好 (Good morning)",
                    "谢谢 (Thank you)"
                )
                
                var yPosition = 100f
                for (text in sampleTexts) {
                    canvas.drawText(text, 50f, yPosition, paint)
                    yPosition += 60f
                }
                
                // Add a note about the feature
                paint.textSize = 30f
                paint.color = Color.YELLOW
                canvas.drawText("Text Translation Active - Detecting and translating text...", 50f, yPosition + 40f, paint)
                
                return resultBitmap
                
            } catch (e: Exception) {
                Log.e(TAG, "Text translation processing failed: ${e.message}")
                return bitmap // Return original if processing fails
            }
        }
    }

    // Utility function to flip a Bitmap horizontally (right to left)
    private fun flipBitmapHorizontally(src: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.preScale(-1f, 1f)  // This flips horizontally (right to left)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // Check if current camera is front camera
    private fun isFrontCamera(): Boolean {
        return cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    // Apply appropriate flip based on camera type
    private fun applyCameraFlip(bitmap: Bitmap): Bitmap {
        return if (isFrontCamera()) {
            flipBitmapHorizontally(bitmap)
        } else {
            bitmap
        }
    }

    // Custom view for displaying histogram
    inner class HistogramView(context: android.content.Context) : View(context) {
        private var redHistogram: IntArray = IntArray(256)
        private var greenHistogram: IntArray = IntArray(256)
        private var blueHistogram: IntArray = IntArray(256)
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 10f
            textAlign = Paint.Align.LEFT
        }

        fun updateHistograms(red: IntArray, green: IntArray, blue: IntArray) {
            redHistogram = red
            greenHistogram = green
            blueHistogram = blue
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val width = width.toFloat()
            val height = height.toFloat()
            val histogramHeight = height / 3f - 20f // Divide into 3 sections with spacing
            
            // Draw background with border
            paint.color = Color.BLACK
            canvas.drawRect(0f, 0f, width, height, paint)
            
            // Draw border
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.style = Paint.Style.FILL
            
            // Find max values for each channel
            val maxRed = redHistogram.maxOrNull() ?: 1
            val maxGreen = greenHistogram.maxOrNull() ?: 1
            val maxBlue = blueHistogram.maxOrNull() ?: 1
            
            val barWidth = width / 256f
            
            // Draw Red histogram
            paint.color = Color.RED
            paint.alpha = 255
            for (i in 0 until 256) {
                val barHeight = (redHistogram[i] / maxRed.toFloat()) * histogramHeight
                canvas.drawRect(
                    i * barWidth,
                    histogramHeight - barHeight + 10f,
                    (i + 1) * barWidth,
                    histogramHeight + 10f,
                    paint
                )
            }
            canvas.drawText("Red", 5f, histogramHeight + 25f, textPaint)
            
            // Draw Green histogram
            paint.color = Color.GREEN
            paint.alpha = 255
            for (i in 0 until 256) {
                val barHeight = (greenHistogram[i] / maxGreen.toFloat()) * histogramHeight
                canvas.drawRect(
                    i * barWidth,
                    2 * histogramHeight - barHeight + 30f,
                    (i + 1) * barWidth,
                    2 * histogramHeight + 30f,
                    paint
                )
            }
            canvas.drawText("Green", 5f, 2 * histogramHeight + 45f, textPaint)
            
            // Draw Blue histogram
            paint.color = Color.BLUE
            paint.alpha = 255
            for (i in 0 until 256) {
                val barHeight = (blueHistogram[i] / maxBlue.toFloat()) * histogramHeight
                canvas.drawRect(
                    i * barWidth,
                    3 * histogramHeight - barHeight + 50f,
                    (i + 1) * barWidth,
                    3 * histogramHeight + 50f,
                    paint
                )
            }
            canvas.drawText("Blue", 5f, 3 * histogramHeight + 65f, textPaint)
        }
    }
}