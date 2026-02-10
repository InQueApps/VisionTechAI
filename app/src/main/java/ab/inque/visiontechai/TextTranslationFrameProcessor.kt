package ab.inque.visiontechai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Recognizes text on the frame, clusters words into sentences by location (C++ where possible),
 * translates to the target language (e.g. English → Spanish), and overlays translations at original locations.
 */
class TextTranslationFrameProcessor(private val context: Context) : FrameProcessor {

    private var textRecognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var lastLangPairKey: String? = null
    /** Language pair (same as lastLangPairKey) for which we have successfully completed downloadModelIfNeeded(). */
    private var modelReadyForPair: String? = null

    private fun getRecognizer(): TextRecognizer {
        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        return textRecognizer!!
    }

    private fun languageCodeToMlKit(code: String): String? =
        when (code.lowercase()) {
            "en" -> TranslateLanguage.ENGLISH
            "es" -> TranslateLanguage.SPANISH
            else -> null
        }

    private fun getTranslator(sourceLanguageCode: String, targetLanguageCode: String): Translator? {
        val src = languageCodeToMlKit(sourceLanguageCode) ?: return null
        val tgt = languageCodeToMlKit(targetLanguageCode) ?: return null
        val key = "$src->$tgt"

        if (key != lastLangPairKey) {
            translator?.close()
            translator = null
            lastLangPairKey = key
            modelReadyForPair = null
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

    override fun process(input: android.graphics.Bitmap, params: ProcessorParams): android.graphics.Bitmap? {
        val sourceCode = params.translationSourceLanguageCode
        val targetCode = params.translationTargetLanguageCode
        if (sourceCode == null || targetCode == null || sourceCode == targetCode) return input

        return try {
            val result = input.copy(input.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
            val inputImage = InputImage.fromBitmap(input, 0)
            val textTask = getRecognizer().process(inputImage)
            val text: Text = try {
                Tasks.await(textTask, 2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Text recognition failed: ${e.message}")
                return result
            }

            val words = mutableListOf<WordWithBox>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        val word = element.text?.trim() ?: continue
                        if (word.isEmpty()) continue
                        words.add(WordWithBox(word, box.left, box.top, box.right, box.bottom))
                    }
                }
            }
            if (words.isEmpty()) return result

            val count = words.size
            val left = IntArray(count) { words[it].left }
            val top = IntArray(count) { words[it].top }
            val right = IntArray(count) { words[it].right }
            val bottom = IntArray(count) { words[it].bottom }
            val groupIds = IntArray(count)

            try {
                TextTranslatorNative.clusterWordsIntoLines(left, top, right, bottom, count, groupIds)
            } catch (e: UnsatisfiedLinkError) {
                clusterWordsKotlinFallback(words, groupIds)
            }

            val groups = mutableMapOf<Int, MutableList<WordWithBox>>()
            words.forEachIndexed { i, w -> groups.getOrPut(groupIds[i]) { mutableListOf() }.add(w) }

            val trans = getTranslator(sourceCode, targetCode) ?: return result

            // ML Kit requires the translation model to be downloaded before translate() works.
            if (lastLangPairKey != modelReadyForPair) {
                try {
                    Tasks.await(trans.downloadModelIfNeeded(), 120, TimeUnit.SECONDS)
                    modelReadyForPair = lastLangPairKey
                    Log.d(TAG, "Translation model ready for $lastLangPairKey")
                } catch (e: Exception) {
                    Log.w(TAG, "Translation model download failed (will retry): ${e.message}")
                }
            }

            val canvas = Canvas(result)
            val textPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            val bgPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0)
                style = Paint.Style.FILL
            }

            for ((_, groupWords) in groups) {
                if (groupWords.isEmpty()) continue
                val sentence = groupWords.joinToString(" ") { it.text }
                val union = Rect(groupWords[0].left, groupWords[0].top, groupWords[0].right, groupWords[0].bottom)
                for (i in 1 until groupWords.size) {
                    val w = groupWords[i]
                    union.union(w.left, w.top, w.right, w.bottom)
                }
                val translated: String = try {
                    Tasks.await(trans.translate(sentence), 5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "Translate failed for \"$sentence\": ${e.message}")
                    sentence
                }
                val fontSize = (union.height() * 0.9f).coerceIn(12f, 72f)
                textPaint.textSize = fontSize
                val textBounds = Rect()
                textPaint.getTextBounds(translated, 0, translated.length, textBounds)
                val pad = 4
                val bgRect = RectF(
                    union.left.toFloat(),
                    union.top.toFloat(),
                    (union.left + textBounds.width() + pad * 2).coerceAtMost(result.width).toFloat(),
                    (union.top + textBounds.height() + pad * 2).coerceAtMost(result.height).toFloat()
                )
                canvas.drawRect(bgRect, bgPaint)
                canvas.drawText(translated, union.left + pad.toFloat(), (union.top + textBounds.height() + pad).toFloat(), textPaint)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.message}", e)
            input
        }
    }

    private data class WordWithBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

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

    fun close() {
        textRecognizer?.close()
        textRecognizer = null
        translator?.close()
        translator = null
        lastLangPairKey = null
        modelReadyForPair = null
    }

    companion object {
        private const val TAG = "TextTranslationFP"
    }
}
