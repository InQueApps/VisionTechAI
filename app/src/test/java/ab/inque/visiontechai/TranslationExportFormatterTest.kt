package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationExportFormatterTest {

    @Test
    fun formatTxt_includesHeaderAndPairs() {
        val text = TranslationExportFormatter.formatTxt(
            translations = listOf("Hola" to "Hello", "Mundo" to "World"),
            fromLang = "es",
            toLang = "en",
            timestampMillis = 1_700_000_000_000L
        )
        assertTrue(text.contains("PRISM AI Translation — es → en"))
        assertTrue(text.contains("Original:    Hola"))
        assertTrue(text.contains("Translated:  Hello"))
        assertTrue(text.contains("[2]"))
        assertTrue(text.contains("Translated:  World"))
    }

    @Test
    fun formatTxt_emptyList_stillHasHeader() {
        val lines = TranslationExportFormatter.formatTxt(emptyList(), "?", "?").lines()
        assertEquals("PRISM AI Translation — ? → ?", lines[0])
    }
}
