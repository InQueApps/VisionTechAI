package ab.inque.visiontechai

import ab.inque.robotics.accumulate.FrameAccumulatorNative


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
import ab.inque.visiontechai.controller.CameraController
import ab.inque.visiontechai.controller.PermissionCoordinator
import ab.inque.visiontechai.controller.PipelineResultDispatcher
import ab.inque.visiontechai.controller.SphereSessionController
import ab.inque.visiontechai.controller.SphereUiCoordinator
import ab.inque.visiontechai.controller.VisionModeController
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.ui.VisionOverlayHost
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
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var overlayHost: VisionOverlayHost
    private lateinit var orientationTracker: OrientationTracker
    private lateinit var permissionCoordinator: PermissionCoordinator
    private lateinit var cameraController: CameraController
    private lateinit var visionModeController: VisionModeController
    private lateinit var pipelineDispatcher: PipelineResultDispatcher
    private lateinit var sphereSession: SphereSessionController
    private lateinit var sphereUiCoordinator: SphereUiCoordinator
    private var cameraFovH = 65f
    private var cameraFovV = 50f

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionCoordinator.onPermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val parentLayout = binding.viewFinder.parent as androidx.constraintlayout.widget.ConstraintLayout
        overlayHost = VisionOverlayHost(parentLayout, binding.controlsContainer.id)
        binding.controlsContainer.elevation = Constants.ELEVATION_CONTROLS

        permissionCoordinator = PermissionCoordinator(
            activity = this,
            onAllGranted = { startCamera() },
            onMinimumGranted = { startCamera() },
            requestLauncher = requestPermissionLauncher
        )

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
            ContactEmailLauncher.open(this)
        }
        binding.recordButton.setOnClickListener {
            if (visionState.mode == VisionMode.SPHERE) {
                sphereSession.handleRecordButton(isPhotoMode) { binding.recordButton.setImageResource(it) }
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
                if (fromUser) {
                    visionState.params = visionState.params.copy(nightVisionIntensity = progress / 100f)
                    binding.nightVisionIntensityValueText.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.nightVisionExposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frames = progress + 1  // 0-9 → 1-10
                    visionState.params = visionState.params.copy(nightVisionExposureFrames = frames)
                    binding.nightVisionExposureValueText.text = "$frames"
                    if (frames <= 1) {
                        FrameAccumulatorNative.clear()
                    }
                }
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
                        progress < 50 -> String.format("%.2fx", 1f - (50 - progress) * 0.0198f)
                        progress > 50 -> String.format("%.1fx", 1f + (progress - 50) * 0.06f)
                        else -> "1.0x"
                    }
                    binding.hdScaleValueText.text = scaleStr
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.hdExposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frames = progress + 1
                    visionState.params = visionState.params.copy(hdExposureFrames = frames)
                    binding.hdExposureValueText.text = "$frames"
                    if (frames <= 1) FrameAccumulatorNative.clear()
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
        binding.opticalFlowSensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    visionState.params = visionState.params.copy(opticalFlowSensitivity = progress / 100f)
                    binding.opticalFlowSensitivityValueText.text = "$progress%"
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

        visionModeController = VisionModeController(
            binding = binding,
            visionState = visionState,
            overlayHost = overlayHost,
            frameProcessors = frameProcessors,
            opticalFlowProcessor = opticalFlowProcessor,
            imageSegmentationProcessor = imageSegmentationProcessor,
            onSphereCleanup = { sphereSession.onSphereCleanupFromModeSwitch() }
        )

        sphereSession = SphereSessionController(
            activity = this,
            binding = binding,
            orientationTracker = orientationTracker,
            setVisionMode = { visionModeController.setVisionMode(it) },
            getCameraFovH = { cameraFovH },
            getCameraFovV = { cameraFovV },
            getAnalysisWidth = { visionState.lastAnalysisWidth },
            getAnalysisHeight = { visionState.lastAnalysisHeight },
            getDisplayRotationDegrees = {
                when (windowManager.defaultDisplay.rotation) {
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            },
            getUseFrontCamera = { useFrontCamera },
            depthProcessor = depthEstimationProcessor
        )

        sphereUiCoordinator = SphereUiCoordinator(binding, visionState, sphereSession)
        sphereUiCoordinator.bind()

        sphereSession.checkInterruptedExport()

        pipelineDispatcher = PipelineResultDispatcher(
            visionState = visionState,
            overlayHost = overlayHost,
            binding = binding,
            sphereControllerProvider = { sphereSession.controller },
            sphereCaptureNextFrame = { sphereSession.captureNextFrame },
            clearSphereCaptureNextFrame = { sphereSession.captureNextFrame = false },
            onSphereRawFrame = { bitmap, ts, rot -> sphereSession.onRawFrame(bitmap, ts, rot) },
            isRecordingProvider = { isRecording },
            processedVideoRecorderProvider = { processedVideoRecorder }
        )

        visionPipeline = VisionAnalysisPipeline(
            getState = { visionState },
            processors = frameProcessors,
            detectObjects = objectDetectionProcessor::detectObjects,
            applyFlip = { b -> if (useFrontCamera) ImageUtils.flipBitmapHorizontally(b) else b }
        )

        cameraController = CameraController(
            activity = this,
            previewView = binding.viewFinder,
            cameraExecutor = cameraExecutor,
            createAnalyzer = {
                visionPipeline.createAnalyzer { result ->
                    runOnUiThread { pipelineDispatcher.dispatch(result) }
                }
            },
            onFovDetected = { h, v ->
                cameraFovH = h
                cameraFovV = v
            }
        )

        permissionCoordinator.checkAndRequest()
        binding.infoButton.bringToFront()
    }

    private var useFrontCamera = false

    private fun setVisionMode(mode: VisionMode) = visionModeController.setVisionMode(mode)

    private fun toggleSlider() = visionModeController.toggleSlider()

    private fun toggleCategoryOverlay(category: VisionMode.Category) =
        visionModeController.toggleCategoryOverlay(category)

    private fun setMainButtonActive(underline: View, active: Boolean) {
        underline.setBackgroundColor(if (active) Color.WHITE else Color.TRANSPARENT)
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

    private fun setMode(photo: Boolean) {
        isPhotoMode = photo
        if (visionState.mode == VisionMode.SPHERE) {
            sphereSession.onSphereCaptureModeChanged(photo)
        }
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
        return try {
            if (MediaCapturePolicy.shouldCaptureOverlayBitmap(visionState.mode)) {
                overlayHost.captureFilterBitmap()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen content: ${e.message}", e)
            null
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
        val body = TranslationExportFormatter.formatTxt(translations, fromLang, toLang)

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
                        stream.write(body.toByteArray(Charsets.UTF_8))
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
                file.writeText(body, Charsets.UTF_8)
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
        val capture = cameraController.imageCapture ?: return
        Log.d(TAG, "Saving processed camera image")
        takeProcessedPhoto()
    }

    private fun takeRawPhoto() {
        val capture = cameraController.imageCapture ?: return
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
        capture.takePicture(
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
        val capture = cameraController.imageCapture ?: return
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
        capture.takePicture(
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
        val recordWithFilter = MediaCapturePolicy.shouldRecordProcessedVideo(visionState.mode)
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
            val videoCapture = cameraController.videoCapture ?: return
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
        overlayHost.histogramView.visibility = View.VISIBLE
        overlayHost.fpsView.visibility = View.VISIBLE
        binding.controlsContainer.visibility = View.VISIBLE
        binding.infoButton.bringToFront()
        overlayHost.bringHudToFront(binding.controlsContainer)
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

    private fun switchCamera() {
        if (isRecording) stopRecording()
        cameraController.switchCamera()
        useFrontCamera = cameraController.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun startCamera() {
        if (!::cameraController.isInitialized) return
        cameraController.startCamera()
    }

    override fun onResume() {
        super.onResume()
        if (!::cameraController.isInitialized) return
        if (permissionCoordinator.allGranted() && cameraController.imageCapture == null) {
            startCamera()
        } else if (permissionCoordinator.hasMinimum() && cameraController.imageCapture == null) {
            startCamera()
        } else if (cameraController.imageCapture == null) {
            permissionCoordinator.checkAndRequest()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::sphereSession.isInitialized) {
            sphereSession.cleanupOnDestroy()
        } else if (::orientationTracker.isInitialized) {
            orientationTracker.stop()
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PROCESSED_VIDEO_WIDTH = 1280
        private const val PROCESSED_VIDEO_HEIGHT = 720
    }
}
