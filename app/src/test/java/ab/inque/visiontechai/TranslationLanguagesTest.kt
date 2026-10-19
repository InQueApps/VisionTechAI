package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLanguagesTest {

    @Test
    fun fromCode_resolvesKnownLanguage() {
        val lang = TranslationLanguages.fromCode("es")
        assertEquals("Spanish", lang?.displayName)
        assertEquals("es", lang?.code)
    }

    @Test
    fun fromCode_isCaseInsensitive() {
        assertEquals("English", TranslationLanguages.fromCode("EN")?.displayName)
    }

    @Test
    fun fromCode_unknownReturnsNull() {
        assertNull(TranslationLanguages.fromCode("xx"))
    }

    @Test
    fun displayName_fallsBackToCode() {
        assertEquals("xx", TranslationLanguages.displayName("xx"))
    }

    @Test
    fun allLanguages_haveUniqueCodes() {
        val codes = TranslationLanguages.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun displayNames_matchesAllEntries() {
        assertEquals(TranslationLanguages.ALL.size, TranslationLanguages.displayNames.size)
    }
}
