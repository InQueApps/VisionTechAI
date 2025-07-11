package ab.inque.visiontechai

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.segmenter.ImageSegmenter
import org.tensorflow.lite.task.vision.segmenter.OutputType
import org.tensorflow.lite.task.vision.segmenter.Segmentation
import org.tensorflow.lite.support.image.TensorImage
import kotlin.math.max
import kotlin.math.min

class ImageSegmentationProcessor(private val context: Context) {
    companion object {
        private const val TAG = "ImageSegmentationProcessor"
        private const val MODEL_FILE = "deeplabv3.tflite"
        private const val ALPHA_COLOR = 128
    }

    private var imageSegmenter: ImageSegmenter? = null
    private var isInitialized = false
    // No manual preprocessing needed - ImageSegmenter handles this automatically

    // PASCAL VOC labels and colors
    private val colorLabels = listOf(
        ColorLabel(0, "background", 0x000000),
        ColorLabel(1, "aeroplane", 0x800000),
        ColorLabel(2, "bicycle", 0x008000),
        ColorLabel(3, "bird", 0x808000),
        ColorLabel(4, "boat", 0x000080),
        ColorLabel(5, "bottle", 0x800080),
        ColorLabel(6, "bus", 0x008080),
        ColorLabel(7, "car", 0x808080),
        ColorLabel(8, "cat", 0x400000),
        ColorLabel(9, "chair", 0x004000),
        ColorLabel(10, "cow", 0x404000),
        ColorLabel(11, "dining table", 0x000040),
        ColorLabel(12, "dog", 0x400040),
        ColorLabel(13, "horse", 0x004040),
        ColorLabel(14, "motorbike", 0x404040),
        ColorLabel(15, "person", 0x200000),
        ColorLabel(16, "potted plant", 0x002000),
        ColorLabel(17, "sheep", 0x202000),
        ColorLabel(18, "sofa", 0x000020),
        ColorLabel(19, "train", 0x200020),
        ColorLabel(20, "tv/monitor", 0x002020)
    )

    data class ColorLabel(
        val id: Int,
        val label: String,
        val rgbColor: Int,
        var isExist: Boolean = false
    ) {
        fun getColor(): Int {
            // Use completely transparent for the background color.
            return if (id == 0) Color.TRANSPARENT else Color.argb(
                ALPHA_COLOR,
                Color.red(rgbColor),
                Color.green(rgbColor),
                Color.blue(rgbColor)
            )
        }
    }

    init {
        initializeSegmentation()
    }

    private fun initializeSegmentation() {
        try {
            // Create the base options for the segmenter
            val optionsBuilder = ImageSegmenter.ImageSegmenterOptions.builder()
            
            // Set general segmentation options, including number of used threads
            val baseOptionsBuilder = BaseOptions.builder().setNumThreads(4)
            optionsBuilder.setBaseOptions(baseOptionsBuilder.build())
            
            // Use CATEGORY_MASK output type to get class indices directly
            optionsBuilder.setOutputType(OutputType.CATEGORY_MASK)
            
            imageSegmenter = ImageSegmenter.createFromFileAndOptions(
                context,
                MODEL_FILE,
                optionsBuilder.build()
            )
            
            Log.d(TAG, "Segmentation model loaded successfully")
            Log.d(TAG, "Using ImageSegmenter with CATEGORY_MASK output type")
            
            // Set initialization flag
            isInitialized = true
            Log.d(TAG, "Image segmentation processor initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize segmentation processor: ${e.message}")
            e.printStackTrace()
            isInitialized = false
        }
    }

    data class CropInfo(
        val cropRect: Rect,
        val scaleFactor: Float,
        val modelInputSize: Int
    )

    private fun calculateCropInfo(bitmap: Bitmap): CropInfo {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        // Calculate the largest square that fits within the image
        val cropSize = min(originalWidth, originalHeight)
        
        // Calculate the crop rectangle (center crop)
        val xOffset = (originalWidth - cropSize) / 2
        val yOffset = (originalHeight - cropSize) / 2
        val cropRect = Rect(xOffset, yOffset, xOffset + cropSize, yOffset + cropSize)
        
        return CropInfo(cropRect, 1.0f, cropSize)
    }

    fun segmentImage(bitmap: Bitmap): Bitmap? {
        if (!isInitialized || imageSegmenter == null) {
            Log.w(TAG, "Segmentation processor not initialized")
            return null
        }

        try {
            val cropInfo = calculateCropInfo(bitmap)

            // Extract the crop region
            val croppedBitmap = Bitmap.createBitmap(
                bitmap, 
                cropInfo.cropRect.left, 
                cropInfo.cropRect.top, 
                cropInfo.cropRect.width(), 
                cropInfo.cropRect.height()
            )

            // Create TensorImage directly from the cropped bitmap
            // ImageSegmenter will handle the resizing internally
            val tensorImage = TensorImage.fromBitmap(croppedBitmap)
            
            // Run segmentation using ImageSegmenter
            val segmentResult = imageSegmenter?.segment(tensorImage)
            
            if (segmentResult != null && segmentResult.isNotEmpty()) {
                // Process the segmentation result
                val modelOutputSegmentation = processSegmentationResult(segmentResult[0])
                
                // Scale the segmentation back to the original crop size
                val croppedSegmentation = Bitmap.createScaledBitmap(
                    modelOutputSegmentation, 
                    cropInfo.cropRect.width(), 
                    cropInfo.cropRect.height(), 
                    true
                )
                
                // Create the final overlay by placing the segmentation in the correct position
                val finalOverlay = createFinalOverlay(cropInfo.cropRect.width(), bitmap, croppedSegmentation, cropInfo.cropRect)
                
                return finalOverlay
            } else {
                Log.w(TAG, "Segmentation returned null or empty result")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun processSegmentationResult(segmentation: Segmentation): Bitmap {
        // Get the colored labels from the segmentation result
        val colorLabels = segmentation.coloredLabels.mapIndexed { index, coloredLabel ->
            ColorLabel(
                index,
                coloredLabel.getlabel(),
                coloredLabel.argb
            )
        }

        // Get the mask tensor from the segmentation result
        val maskTensor = segmentation.masks[0]
        val maskArray = maskTensor.buffer.array()
        val pixels = IntArray(maskArray.size)

        for (i in maskArray.indices) {
            val classIndex = maskArray[i].toInt().coerceIn(0, colorLabels.size - 1)
            val colorLabel = colorLabels[classIndex].apply {
                isExist = true
            }
            val color = colorLabel.getColor()
            pixels[i] = color
        }

        val coloredSegmentation = Bitmap.createBitmap(
            pixels,
            maskTensor.width,
            maskTensor.height,
            Bitmap.Config.ARGB_8888
        )

        // Log detected classes (only if any are found)
        val detectedClasses = colorLabels.filter { it.isExist }.map { it.label }
        if (detectedClasses.isNotEmpty()) {
            Log.d(TAG, "Detected classes: $detectedClasses")
        }

        // Reset isExist flags for next run
        colorLabels.forEach { it.isExist = false }

        return coloredSegmentation
    }

    private fun createFinalOverlay(width: Int, originalBitmap: Bitmap, segmentationBitmap: Bitmap, cropRect: Rect): Bitmap {
        // Extract the cropped region from the original image
        val croppedOriginal = Bitmap.createBitmap(
            originalBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )
        
        // Calculate the shortest side of the possible image size
        val shortestSide = min(cropRect.width(), cropRect.height())
        
        Log.d(TAG, "Creating final overlay - Crop: ${cropRect.width()}x${cropRect.height()}, Shortest side: $shortestSide")
        
        // Scale the cropped original to maintain aspect ratio with shortest side
        val scaledOriginal = if (cropRect.width() > cropRect.height()) {
            // Landscape: scale by height
            val scaleFactor = shortestSide.toFloat() / cropRect.height()
            val newWidth = (cropRect.width() * scaleFactor).toInt()
            Log.d(TAG, "Landscape scaling - Scale factor: $scaleFactor, New size: ${newWidth}x$shortestSide")
            Bitmap.createScaledBitmap(croppedOriginal, newWidth, shortestSide, true)
        } else {
            // Portrait: scale by width
            val scaleFactor = shortestSide.toFloat() / cropRect.width()
            val newHeight = (cropRect.height() * scaleFactor).toInt()
            Log.d(TAG, "Portrait scaling - Scale factor: $scaleFactor, New size: ${shortestSide}x$newHeight")
            Bitmap.createScaledBitmap(croppedOriginal, shortestSide, newHeight, true)
        }
        
        // Create the final overlay by combining the scaled original with segmentation
        val resultBitmap = scaledOriginal.copy(scaledOriginal.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        
        // Scale the segmentation bitmap to match the shortest side
        val scaledSegmentation = Bitmap.createScaledBitmap(segmentationBitmap, shortestSide, shortestSide, true)
        
        // Calculate the position to center the segmentation overlay
        val overlayX = (scaledOriginal.width - shortestSide) / 2
        val overlayY = (scaledOriginal.height - shortestSide) / 2
        
        Log.d(TAG, "Final overlay - Result size: ${resultBitmap.width}x${resultBitmap.height}, Overlay position: ($overlayX, $overlayY)")
        
        // Draw the segmentation overlay on top of the original image
        val paint = Paint().apply {
            alpha = 128 // Semi-transparent
        }
        canvas.drawBitmap(scaledSegmentation, overlayX.toFloat(), overlayY.toFloat(), paint)
        
        return resultBitmap
    }
} 