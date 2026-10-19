package ab.inque.visiontechai

import ab.inque.robotics.text.TextLineClustererNative


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Recognizes text on the frame, clusters words into sentences by location (C++ where possible),
 * translates to the target language, and overlays translations at original locations.
 *
 * Architecture: process() is non-blocking. It always returns immediately with the current
 * camera frame + the latest cached overlay. OCR + translation runs asynchronously on a
 * dedicated background thread and atomically updates the cached overlay when complete.
 */
class TextTranslationFrameProcessor(private val context: Context) : FrameProcessor {

    private var textRecognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var lastLangPairKey: String? = null
    private var modelReadyForPair: String? = null

    private val translationCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > 256
    }

    data class TranslationEntry(
        val original: String,
        val translated: String,
        val bounds: Rect,
        val angleDegrees: Float
    )

    private data class OverlayEntry(
        val translated: String,
        val bounds: Rect,
        val fontSize: Float,
        val angleDegrees: Float,
        val boundsWidth: Float,
        val boundsHeight: Float
    )

    @Volatile private var cachedOverlay: List<OverlayEntry>? = null
    @Volatile private var cachedEntries: List<TranslationEntry>? = null
    @Volatile private var cachedFrame: Bitmap? = null
    @Volatile private var ocrInProgress = false
    private var lastDispatchMs = 0L

    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TextTranslation-OCR").apply { isDaemon = true }
    }

    private fun getRecognizer(): TextRecognizer {
        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        return textRecognizer!!
    }

    private fun languageCodeToMlKit(code: String): String? =
        TranslationLanguages.mlKitCode(code)

    private fun getTranslator(sourceLanguageCode: String, targetLanguageCode: String): Translator? {
        val src = languageCodeToMlKit(sourceLanguageCode) ?: return null
        val tgt = languageCodeToMlKit(targetLanguageCode) ?: return null
        val key = "$src->$tgt"

        if (key != lastLangPairKey) {
            translator?.close()
            translator = null
            lastLangPairKey = key
            modelReadyForPair = null
            translationCache.clear()
            cachedOverlay = null
        }

        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(tgt)
                .build()
            translator = Translation.getClient(options)
        }
        return translator
    }

    override fun process(input: Bitmap, params: ProcessorParams): Bitmap? {
        val sourceCode = params.translationSourceLanguageCode
        val targetCode = params.translationTargetLanguageCode
        if (sourceCode == null || targetCode == null || sourceCode == targetCode) return input

        val textScale = params.translationTextScale
        val bgAlpha = params.translationBackgroundAlpha

        val now = System.currentTimeMillis()
        if (!ocrInProgress && now - lastDispatchMs >= PROCESS_INTERVAL_MS) {
            lastDispatchMs = now
            ocrInProgress = true
            val bitmapCopy = input.copy(input.config ?: Bitmap.Config.ARGB_8888, false)
            val paramsCopy = params.copy()
            ocrExecutor.execute {
                try {
                    runOcrAndTranslate(bitmapCopy, paramsCopy)
                    cachedFrame?.recycle()
                    cachedFrame = bitmapCopy
                } catch (e: Exception) {
                    Log.e(TAG, "Async OCR/translate failed: ${e.message}", e)
                    bitmapCopy.recycle()
                } finally {
                    ocrInProgress = false
                }
            }
        }

        val overlay = cachedOverlay
        return if (overlay != null) {
            drawOverlay(input, overlay, textScale, bgAlpha)
        } else {
            applyDimming(input, bgAlpha)
        }
    }

    private fun runOcrAndTranslate(bitmap: Bitmap, params: ProcessorParams) {
        val sourceCode = params.translationSourceLanguageCode ?: return
        val targetCode = params.translationTargetLanguageCode ?: return
        val minConfidence = params.translationMinConfidence

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val text = try {
            Tasks.await(getRecognizer().process(inputImage), 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Text recognition failed: ${e.message}")
            return
        }

        val lineAngles = mutableListOf<Float>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                computeAngle(line.cornerPoints)?.let { lineAngles.add(it) }
            }
        }
        val baseAngle = if (lineAngles.isNotEmpty()) {
            lineAngles.sorted()[lineAngles.size / 2]
        } else 0f

        val minLength = params.translationMinLength
        val words = mutableListOf<WordWithBox>()
        for ((blockIdx, block) in text.textBlocks.withIndex()) {
            for (line in block.lines) {
                val lineAngle = computeAngle(line.cornerPoints)
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val word = element.text?.trim() ?: continue
                    if (word.isEmpty()) continue
                    val confidence = element.confidence ?: 1.0f
                    if (confidence < minConfidence) continue
                    val elemAngle = computeAngle(element.cornerPoints)
                    val angle = pickAngle(elemAngle, lineAngle, baseAngle)
                    words.add(WordWithBox(word, box.left, box.top, box.right, box.bottom, angle, confidence, blockIdx))
                }
            }
        }
        if (words.isEmpty()) {
            cachedOverlay = emptyList()
            return
        }

        val count = words.size
        val left = IntArray(count) { words[it].left }
        val top = IntArray(count) { words[it].top }
        val right = IntArray(count) { words[it].right }
        val bottom = IntArray(count) { words[it].bottom }
        val groupIds = IntArray(count)

        try {
            TextLineClustererNative.clusterWordsIntoLines(left, top, right, bottom, count, groupIds)
        } catch (e: UnsatisfiedLinkError) {
            clusterWordsKotlinFallback(words, groupIds)
        }

        val lineGroups = mutableMapOf<Int, MutableList<WordWithBox>>()
        words.forEachIndexed { i, w -> lineGroups.getOrPut(groupIds[i]) { mutableListOf() }.add(w) }

        // Merge line-groups that share the same TextBlock into sentence-level groups
        val blockToLines = mutableMapOf<Int, MutableList<Int>>()
        for ((lineId, lineWords) in lineGroups) {
            val dominantBlock = lineWords.groupBy { it.blockId }
                .maxByOrNull { it.value.size }!!.key
            blockToLines.getOrPut(dominantBlock) { mutableListOf() }.add(lineId)
        }

        val sentenceGroups = mutableMapOf<Int, MutableList<WordWithBox>>()
        var sentenceId = 0
        for ((_, lineIds) in blockToLines) {
            val merged = mutableListOf<WordWithBox>()
            for (lid in lineIds) {
                lineGroups[lid]?.let { merged.addAll(it) }
            }
            if (merged.isNotEmpty()) {
                sentenceGroups[sentenceId++] = merged
            }
        }

        val trans = getTranslator(sourceCode, targetCode) ?: return

        if (lastLangPairKey != modelReadyForPair) {
            try {
                Tasks.await(trans.downloadModelIfNeeded(), 120, TimeUnit.SECONDS)
                modelReadyForPair = lastLangPairKey
                Log.d(TAG, "Translation model ready for $lastLangPairKey")
            } catch (e: Exception) {
                Log.w(TAG, "Translation model download failed (will retry): ${e.message}")
            }
        }

        data class GroupEntry(val sentence: String, val union: Rect, val angleDegrees: Float)

        val entries = sentenceGroups.values.filter { it.isNotEmpty() }.map { groupWords ->
            val sorted = groupWords.sortedWith(compareBy({ it.top + it.bottom }, { it.left }))
            val sentence = sorted.joinToString(" ") { it.text }
            val union = Rect(sorted[0].left, sorted[0].top, sorted[0].right, sorted[0].bottom)
            for (i in 1 until sorted.size) {
                val w = sorted[i]
                union.union(w.left, w.top, w.right, w.bottom)
            }
            val avgAngle = sorted.map { it.angleDegrees }.average().toFloat()
            GroupEntry(sentence, union, avgAngle)
        }.filter { it.sentence.length >= minLength }

        data class PendingEntry(val entry: GroupEntry, val task: com.google.android.gms.tasks.Task<String>)

        val overlayEntries = mutableListOf<OverlayEntry>()
        val translationEntries = mutableListOf<TranslationEntry>()
        val pending = mutableListOf<PendingEntry>()

        for (entry in entries) {
            val hit = translationCache[entry.sentence]
            if (hit != null) {
                val fontSize = (entry.union.height() * 0.9f).coerceIn(12f, 72f)
                overlayEntries.add(OverlayEntry(
                    hit, Rect(entry.union), fontSize, entry.angleDegrees,
                    entry.union.width().toFloat(), entry.union.height().toFloat()
                ))
                translationEntries.add(TranslationEntry(
                    entry.sentence, hit, Rect(entry.union), entry.angleDegrees
                ))
            } else {
                pending.add(PendingEntry(entry, trans.translate(entry.sentence)))
            }
        }

        for (p in pending) {
            val translated = try {
                Tasks.await(p.task, 3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Translate failed for \"${p.entry.sentence}\": ${e.message}")
                p.entry.sentence
            }
            translationCache[p.entry.sentence] = translated
            val fontSize = (p.entry.union.height() * 0.9f).coerceIn(12f, 72f)
            overlayEntries.add(OverlayEntry(
                translated, Rect(p.entry.union), fontSize, p.entry.angleDegrees,
                p.entry.union.width().toFloat(), p.entry.union.height().toFloat()
            ))
            translationEntries.add(TranslationEntry(
                p.entry.sentence, translated, Rect(p.entry.union), p.entry.angleDegrees
            ))
        }

        cachedOverlay = overlayEntries
        cachedEntries = translationEntries
    }

    private fun computeAngle(corners: Array<Point>?): Float? {
        if (corners == null || corners.size < 2) return null
        val dx = (corners[1].x - corners[0].x).toFloat()
        val dy = (corners[1].y - corners[0].y).toFloat()
        if (hypot(dx, dy) < 2f) return null
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun pickAngle(elemAngle: Float?, lineAngle: Float?, baseAngle: Float): Float {
        val MAX_DEVIATION = 15f
        if (elemAngle != null && kotlin.math.abs(elemAngle - baseAngle) <= MAX_DEVIATION) return elemAngle
        if (lineAngle != null && kotlin.math.abs(lineAngle - baseAngle) <= MAX_DEVIATION) return lineAngle
        return baseAngle
    }

    private fun applyDimming(input: Bitmap, bgAlpha: Float): Bitmap {
        if (bgAlpha >= 1f) return input
        val result = input.copy(input.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val dimAlpha = ((1f - bgAlpha) * 255).toInt().coerceIn(0, 255)
        canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
        return result
    }

    private fun drawOverlay(input: Bitmap, overlay: List<OverlayEntry>, textScale: Float, bgAlpha: Float): Bitmap {
        val result = input.copy(input.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        if (bgAlpha < 1f) {
            val dimAlpha = ((1f - bgAlpha) * 255).toInt().coerceIn(0, 255)
            canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
        }

        if (overlay.isEmpty()) return result

        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }

        for (entry in overlay) {
            val cx = entry.bounds.exactCenterX()
            val cy = entry.bounds.exactCenterY()

            val targetW = entry.boundsWidth * textScale
            val targetH = entry.boundsHeight * textScale

            textPaint.textSize = targetH * 0.85f
            val measuredW = textPaint.measureText(entry.translated)
            if (measuredW > targetW && measuredW > 0f) {
                textPaint.textSize *= (targetW / measuredW)
            }
            textPaint.textSize = textPaint.textSize.coerceIn(6f, 200f)

            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            val textW = textPaint.measureText(entry.translated)

            val pad = 4f
            val halfW = textW / 2f + pad
            val halfH = textH / 2f + pad

            canvas.save()
            canvas.rotate(entry.angleDegrees, cx, cy)

            canvas.drawRect(
                cx - halfW, cy - halfH,
                cx + halfW, cy + halfH,
                bgPaint
            )
            canvas.drawText(
                entry.translated,
                cx - textW / 2f,
                cy - fm.ascent - textH / 2f,
                textPaint
            )

            canvas.restore()
        }
        return result
    }

    private data class WordWithBox(
        val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
        val angleDegrees: Float, val confidence: Float, val blockId: Int
    )

    private fun clusterWordsKotlinFallback(words: List<WordWithBox>, outGroupIds: IntArray) {
        if (words.isEmpty()) return
        val sorted = words.mapIndexed { i, w -> i to w }.sortedWith(compareBy({ it.second.top + it.second.bottom }, { it.second.left }))
        var groupId = 0
        outGroupIds[sorted[0].first] = groupId
        val avgHeight = words.sumOf { it.bottom - it.top } / words.size.coerceAtLeast(1)
        val yTol = (avgHeight * 0.5f).toInt().coerceAtLeast(4)
        for (i in 1 until sorted.size) {
            val (idx, w) = sorted[i]
            val (_, prev) = sorted[i - 1]
            val sameLine = kotlin.math.abs((w.top + w.bottom) / 2 - (prev.top + prev.bottom) / 2) <= yTol &&
                w.left - prev.right < avgHeight * 4
            if (!sameLine) groupId++
            outGroupIds[idx] = groupId
        }
    }

    fun getTranslationSnapshot(): List<Pair<String, String>> {
        return translationCache.entries.map { (original, translated) -> original to translated }
    }

    fun getFullTranslationSnapshot(): List<TranslationEntry> {
        return cachedEntries?.toList() ?: emptyList()
    }

    fun getLatestFrame(): Bitmap? {
        val frame = cachedFrame ?: return null
        return try {
            frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) { null }
    }

    fun clearTranslationCache() {
        translationCache.clear()
        cachedOverlay = null
        cachedEntries = null
    }

    fun close() {
        ocrExecutor.shutdownNow()
        textRecognizer?.close()
        textRecognizer = null
        translator?.close()
        translator = null
        lastLangPairKey = null
        modelReadyForPair = null
        translationCache.clear()
        cachedOverlay = null
        cachedEntries = null
        cachedFrame?.recycle()
        cachedFrame = null
    }

    companion object {
        private const val TAG = "TextTranslationFP"
        private const val PROCESS_INTERVAL_MS = 400L
    }
}
