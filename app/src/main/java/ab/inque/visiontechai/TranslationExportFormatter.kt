package ab.inque.visiontechai

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds plain-text export content for on-device translation history.
 */
object TranslationExportFormatter {

    fun formatTxt(
        translations: List<Pair<String, String>>,
        fromLang: String,
        toLang: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("PRISM AI Translation — $fromLang → $toLang")
        sb.appendLine(
            "Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(timestampMillis)}"
        )
        sb.appendLine("=".repeat(50))
        sb.appendLine()
        for ((i, pair) in translations.withIndex()) {
            sb.appendLine("[${i + 1}]")
            sb.appendLine("  Original:    ${pair.first}")
            sb.appendLine("  Translated:  ${pair.second}")
            sb.appendLine()
        }
        return sb.toString()
    }
}
