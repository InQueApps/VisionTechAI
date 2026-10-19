package ab.inque.visiontechai

import ab.inque.robotics.vision.depth.DepthNative


import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
/**
 * MiDaS v2.1 small depth estimation. Renders depth as greyscale:
 * light grey = close, dark grey = far.
 * Loads TFLite from assets: "Midas-V2-Quantized.tflite" or "midas_v21_small.tflite".
 * See DEPTH_FIELD.md for download links.
 */
class DepthEstimationProcessor(private val context: Context) {

    companion object {
        private const val TAG = "DepthEstimationProcessor"
        private const val DEFAULT_INPUT_SIZE = 256
        private val MODEL_FILES = listOf(
            "midas/Midas-V2_w8a8.tflite",
            "midas/model.data",
            "midas/model.tflite",
            "midas/Midas-V2-Quantized.tflite",
            "midas/midas.tflite",
            "Midas-V2-Quantized.tflite",
            "midas_v21_small.tflite",
            "midas.tflite",
            "depth.tflite"
        )
        private val SKIP_PREFIXES = setOf("yolo", "esrgan", "class") // don't use these for depth
        private val MODEL_SUBFOLDERS = listOf("midas")
        /** Only submit a new inference every N frames. Copying the frame on the analyzer thread blocks ~20-50ms; doing it every frame freezes the pipeline. */
        private const val SUBMIT_EVERY_N_FRAMES = 5
    }

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var inputSize: Int = DEFAULT_INPUT_SIZE
    private var outputDepthRows: Int = DEFAULT_INPUT_SIZE
    private var outputDepthCols: Int = DEFAULT_INPUT_SIZE
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var depthMap: FloatArray = FloatArray(DEFAULT_INPUT_SIZE * DEFAULT_INPUT_SIZE)
    private var nullReturnLogCounter = 0
    private var submitFrameCounter = 0
    /** Cached last depth bitmap; written by background thread, read by analyzer. */
    @Volatile
    private var lastDepthBitmap: Bitmap? = null
    private val inferenceRunning = AtomicBoolean(false)
    private val depthExecutor = Executors.newSingleThreadExecutor()
    /** True when model expects uint8 input (e.g. Midas-V2_w8a8). */
    private var inputQuantized: Boolean = false
    /** True when model returns uint8 output. */
    private var outputQuantized: Boolean = false

    private val modelLoading = AtomicBoolean(false)

    init {
        modelLoading.set(true)
        depthExecutor.execute {
            try {
                initModel()
            } finally {
                modelLoading.set(false)
            }
        }
    }

    /** Placeholder shown while the model is loading or if the TFLite file is missing. */
    fun createPlaceholderBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#FF2D2D2D"))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = (width / 15f).coerceIn(14f, 48f)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val msg = if (modelLoading.get()) {
            "Loading depth model..."
        } else {
            "Depth model not loaded.\n\nAdd Midas-V2-Quantized.tflite to:\napp/src/main/assets/\n\n(See README_DEPTH_MODEL.txt in that folder)"
        }
        val y = bmp.height / 2f
        val lines = msg.split("\n")
        var lineY = y - (lines.size - 1) * paint.textSize * 0.5f
        for (line in lines) {
            canvas.drawText(line, bmp.width / 2f, lineY, paint)
            lineY += paint.textSize * 1.2f
        }
        return bmp
    }

    private fun initModel() {
        fun tryLoad(fileName: String): Boolean {
            return try {
                context.assets.open(fileName).use { stream ->
                    val bytes = stream.readBytes()
                    Log.d(TAG, "Loading depth model: $fileName (${bytes.size} bytes)")
                    val options = Interpreter.Options().apply {
                        setNumThreads(2)
                        setUseXNNPACK(true)
                    }
                    interpreter = Interpreter(ByteBuffer.allocateDirect(bytes.size).apply {
                        put(bytes)
                        rewind()
                    }, options)
                    val numInputs = interpreter?.inputTensorCount ?: 0
                    val numOutputs = interpreter?.outputTensorCount ?: 0
                    Log.d(TAG, "Model has $numInputs input(s), $numOutputs output(s)")
                    val inTensor = interpreter?.getInputTensor(0)
                    val outTensor = interpreter?.getOutputTensor(0)
                    val inShape = inTensor?.shape()
                    val outShapeNullable = outTensor?.shape()
                    val inType = inTensor?.dataType() ?: DataType.FLOAT32
                    val outType = outTensor?.dataType() ?: DataType.FLOAT32
                    inputQuantized = (inType == DataType.UINT8)
                    outputQuantized = (outType == DataType.UINT8)
                    Log.d(TAG, "Depth model input shape: ${inShape?.contentToString()} type=$inType (quantized=$inputQuantized), output: ${outShapeNullable?.contentToString()} type=$outType (quantized=$outputQuantized)")
                    if (inShape != null && inShape.size >= 3) {
                        inputSize = inShape[1].coerceIn(128, 512)
                        if (inShape[1] != inShape[2]) inputSize = minOf(inShape[1], inShape[2]).coerceIn(128, 512)
                    }
                    val inSize = inputSize
                    inputBuffer = if (inputQuantized) {
                        ByteBuffer.allocateDirect(1 * inSize * inSize * 3).order(ByteOrder.nativeOrder())
                    } else {
                        ByteBuffer.allocateDirect(1 * inSize * inSize * 3 * 4).order(ByteOrder.nativeOrder())
                    }
                    val outShape = outShapeNullable ?: intArrayOf(1, inSize, inSize, 1)
                    val outNumElements = outShape.fold(1) { acc, d -> acc * d }
                    val outBytesPerElement = if (outputQuantized) 1 else 4
                    val outCapacity = outNumElements * outBytesPerElement
                    outputBuffer = ByteBuffer.allocateDirect(outCapacity).order(ByteOrder.nativeOrder())
                    // Spatial dims: [N,H,W] or [N,H,W,C] → use H=shape[1], W=shape[2]. Using last-two gave cols=1 for [1,256,256,1] → horizontal lines.
                    when {
                        outShape.size >= 3 -> {
                            outputDepthRows = outShape[1].coerceIn(1, 512)
                            outputDepthCols = outShape[2].coerceIn(1, 512)
                        }
                        outShape.size == 2 -> {
                            outputDepthRows = outShape[0].coerceIn(1, 512)
                            outputDepthCols = outShape[1].coerceIn(1, 512)
                        }
                        else -> {
                            outputDepthRows = inSize
                            outputDepthCols = inSize
                        }
                    }
                    depthMap = FloatArray(outNumElements.coerceAtLeast(1))
                    Log.d(TAG, "Output shape: ${outShape.contentToString()} → depth ${outputDepthRows}x${outputDepthCols}, buffer: $outNumElements elements, ${outCapacity}B")
                    isInitialized = true
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load $fileName: ${e.message}")
                false
            }
        }

        for (fileName in MODEL_FILES) {
            if (tryLoad(fileName)) return
        }
        // Fallback: try any .tflite or .data in assets root and in midas subfolder
        fun tryListInDir(dir: String) {
            try {
                @Suppress("UNCHECKED_CAST")
                val list = context.assets.list(dir) as? Array<String> ?: return
                for (name in list) {
                    val lower = name.lowercase()
                    if (!lower.endsWith(".tflite") && !lower.endsWith(".data")) continue
                    if (SKIP_PREFIXES.any { lower.contains(it) }) continue
                    val path = if (dir.isEmpty()) name else "$dir/$name"
                    if (tryLoad(path)) return
                }
            } catch (_: Exception) { }
        }
        tryListInDir("")
        for (sub in MODEL_SUBFOLDERS) tryListInDir(sub)
        val assetsList = try {
            (context.assets.list("") as? Array<String>)?.joinToString(", ") ?: "?"
        } catch (_: Exception) { "?" }
        Log.e(TAG, "No depth TFLite model found. Assets root: [$assetsList]. Put model in assets/ or assets/midas/ (e.g. midas/model.data or Midas-V2-Quantized.tflite)")
    }

    /**
     * Run depth estimation and render depth as greyscale only:
     * light grey = close, dark grey = far. Returns null if model not loaded or on error.
     * Inference runs on a background thread so we never block the analyzer (avoids surface freeze).
     */
    fun processDepthField(input: Bitmap, params: ProcessorParams): Bitmap? {
        if (!isInitialized || interpreter == null) {
            if (nullReturnLogCounter++ % 90 == 0) {
                if (modelLoading.get()) {
                    Log.i(TAG, "Depth model still loading in background...")
                } else {
                    Log.w(TAG, "Depth field: model not loaded. Add a MiDaS .tflite to app/src/main/assets/ or assets/midas/")
                }
            }
            return null
        }
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return null

        val cached = lastDepthBitmap
        val sameSize = cached != null && cached.width == width && cached.height == height
        submitFrameCounter++
        val shouldSubmit = !inferenceRunning.get() && (cached == null || submitFrameCounter % SUBMIT_EVERY_N_FRAMES == 0)
        if (shouldSubmit) {
            val inputCopy = input.copy(input.config ?: Bitmap.Config.ARGB_8888, false)
            if (inputCopy != null) {
                inferenceRunning.set(true)
                depthExecutor.execute {
                    try {
                        val result = runDepthInference(inputCopy)
                        if (result != null) lastDepthBitmap = result
                    } finally {
                        inputCopy.recycle()
                        inferenceRunning.set(false)
                    }
                }
            }
        }
        return if (sameSize) cached else null
    }

    /** Runs on depth executor. Must not be called from analyzer thread. */
    private fun runDepthInference(input: Bitmap): Bitmap? {
        val width = input.width
        val height = input.height
        val inBuf = inputBuffer ?: return null
        val outBuf = outputBuffer ?: return null
        val size = inputSize
        val rgb = Bitmap.createScaledBitmap(input, size, size, true)
        fillInputBuffer(rgb, size)
        rgb.recycle()
        try {
            interpreter?.run(inBuf, outBuf)
        } catch (e: Exception) {
            val cause = e.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "none"
            Log.e(TAG, "MiDaS inference failed: ${e.javaClass.simpleName} msg=${e.message} cause=$cause", e)
            return null
        }
        outBuf.rewind()
        val depthRows = outputDepthRows
        val depthCols = outputDepthCols
        val outPixels = IntArray(width * height)
        try {
            if (DepthNative.depthRenderToGreyscale(outBuf, depthRows, depthCols, outputQuantized, width, height, outPixels)) {
                val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                result.setPixels(outPixels, 0, width, 0, 0, width, height)
                return result
            }
        } catch (_: UnsatisfiedLinkError) {
            // Native lib not loaded; fall back to Kotlin path
        }
        // Fallback: Kotlin postprocess
        outBuf.rewind()
        val depthLen = depthMap.size
        if (outputQuantized) {
            for (i in 0 until depthLen) {
                if (outBuf.hasRemaining()) depthMap[i] = (outBuf.get().toInt() and 0xFF) / 255f
            }
        } else {
            for (i in 0 until depthLen) {
                if (outBuf.hasRemaining()) depthMap[i] = outBuf.float
            }
        }
        normalizeDepthMap(depthMap)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dy = (y * depthRows / height).coerceIn(0, depthRows - 1)
                val dx = (x * depthCols / width).coerceIn(0, depthCols - 1)
                val depthIdx = dy * depthCols + dx
                val d = if (depthIdx < depthLen) depthMap[depthIdx] else 0f
                val grey = (d * 255f).toInt().coerceIn(0, 255)
                outPixels[y * width + x] = Color.argb(255, grey, grey, grey)
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun fillInputBuffer(bitmap: Bitmap, size: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = inputBuffer ?: return
        buf.rewind()
        if (inputQuantized) {
            for (i in pixels.indices) {
                val c = pixels[i]
                buf.put(Color.red(c).toByte())
                buf.put(Color.green(c).toByte())
                buf.put(Color.blue(c).toByte())
            }
        } else {
            for (i in pixels.indices) {
                val c = pixels[i]
                buf.putFloat(Color.red(c) / 255f)
                buf.putFloat(Color.green(c) / 255f)
                buf.putFloat(Color.blue(c) / 255f)
            }
        }
        buf.rewind()
    }

    private fun normalizeDepthMap(map: FloatArray) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in map) {
            if (v.isFinite()) {
                min = minOf(min, v)
                max = maxOf(max, v)
            }
        }
        if (max <= min) return
        val range = max - min
        for (i in map.indices) {
            map[i] = ((map[i] - min) / range).coerceIn(0f, 1f)
        }
    }

    /**
     * Applies focus to the depth visualization: brightens the depth band at params.depthFieldFocus (0=far, 1=near).
     */
    fun applyDepthFieldParams(bitmap: Bitmap, params: ProcessorParams): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        if (n <= 0) return bitmap
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val focus = params.depthFieldFocus.coerceIn(0f, 1f)

        for (i in 0 until n) {
            val grey = pixels[i] and 0xFF
            val d = grey / 255f
            val focusScore = (1f - 2f * kotlin.math.abs(d - focus)).coerceIn(0f, 1f)
            val blended = (grey * (1f - 0.6f * focusScore) + 255f * 0.6f * focusScore).toInt().coerceIn(0, 255)
            pixels[i] = 0xFF000000.toInt() or (blended shl 16) or (blended shl 8) or blended
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Blocking nearness map [0,1] for sphere motion refine (G5). Call from stitch worker only.
     */
    fun extractNearnessMapBlocking(bitmap: Bitmap): FloatArray? {
        if (!isInitialized || interpreter == null) return null
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) ?: return null
        return try {
            val grey = runDepthInference(copy)
            copy.recycle()
            grey?.let { greyscaleToNearness(it) }
        } catch (e: Exception) {
            copy.recycle()
            null
        }
    }

    /**
     * Async MiDaS nearness for sphere stitch — never blocks the stitch executor.
     * [bitmap] is recycled after inference completes.
     */
    fun scheduleNearnessMap(bitmap: Bitmap, onComplete: (FloatArray) -> Unit) {
        if (!isInitialized || interpreter == null) {
            bitmap.recycle()
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        depthExecutor.execute {
            try {
                val grey = runDepthInference(bitmap)
                grey?.let {
                    onComplete(greyscaleToNearness(it))
                    it.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "scheduleNearnessMap failed: ${e.message}")
            } finally {
                bitmap.recycle()
                inferenceRunning.set(false)
            }
        }
    }

    private fun greyscaleToNearness(depthGrey: Bitmap): FloatArray {
        val w = depthGrey.width
        val h = depthGrey.height
        val n = w * h
        val pixels = IntArray(n)
        depthGrey.getPixels(pixels, 0, w, 0, 0, w, h)
        val raw = FloatArray(n) { i -> (pixels[i] and 0xFF) / 255f }
        return SphereMotionDepth.normalizeMetricNearness(raw)
    }
}
