package ab.inque.visiontechai

import com.google.mlkit.nl.translate.TranslateLanguage

data class TranslationLang(
    val code: String,
    val displayName: String,
    val mlKitCode: String
)

object TranslationLanguages {

    val ALL: List<TranslationLang> = listOf(
        TranslationLang("af", "Afrikaans", TranslateLanguage.AFRIKAANS),
        TranslationLang("sq", "Albanian", TranslateLanguage.ALBANIAN),
        TranslationLang("ar", "Arabic", TranslateLanguage.ARABIC),
        TranslationLang("be", "Belarusian", TranslateLanguage.BELARUSIAN),
        TranslationLang("bn", "Bengali", TranslateLanguage.BENGALI),
        TranslationLang("bg", "Bulgarian", TranslateLanguage.BULGARIAN),
        TranslationLang("ca", "Catalan", TranslateLanguage.CATALAN),
        TranslationLang("zh", "Chinese", TranslateLanguage.CHINESE),
        TranslationLang("hr", "Croatian", TranslateLanguage.CROATIAN),
        TranslationLang("cs", "Czech", TranslateLanguage.CZECH),
        TranslationLang("da", "Danish", TranslateLanguage.DANISH),
        TranslationLang("nl", "Dutch", TranslateLanguage.DUTCH),
        TranslationLang("en", "English", TranslateLanguage.ENGLISH),
        TranslationLang("eo", "Esperanto", TranslateLanguage.ESPERANTO),
        TranslationLang("et", "Estonian", TranslateLanguage.ESTONIAN),
        TranslationLang("fi", "Finnish", TranslateLanguage.FINNISH),
        TranslationLang("fr", "French", TranslateLanguage.FRENCH),
        TranslationLang("gl", "Galician", TranslateLanguage.GALICIAN),
        TranslationLang("ka", "Georgian", TranslateLanguage.GEORGIAN),
        TranslationLang("de", "German", TranslateLanguage.GERMAN),
        TranslationLang("el", "Greek", TranslateLanguage.GREEK),
        TranslationLang("gu", "Gujarati", TranslateLanguage.GUJARATI),
        TranslationLang("ht", "Haitian Creole", TranslateLanguage.HAITIAN_CREOLE),
        TranslationLang("he", "Hebrew", TranslateLanguage.HEBREW),
        TranslationLang("hi", "Hindi", TranslateLanguage.HINDI),
        TranslationLang("hu", "Hungarian", TranslateLanguage.HUNGARIAN),
        TranslationLang("is", "Icelandic", TranslateLanguage.ICELANDIC),
        TranslationLang("id", "Indonesian", TranslateLanguage.INDONESIAN),
        TranslationLang("ga", "Irish", TranslateLanguage.IRISH),
        TranslationLang("it", "Italian", TranslateLanguage.ITALIAN),
        TranslationLang("ja", "Japanese", TranslateLanguage.JAPANESE),
        TranslationLang("kn", "Kannada", TranslateLanguage.KANNADA),
        TranslationLang("ko", "Korean", TranslateLanguage.KOREAN),
        TranslationLang("lv", "Latvian", TranslateLanguage.LATVIAN),
        TranslationLang("lt", "Lithuanian", TranslateLanguage.LITHUANIAN),
        TranslationLang("mk", "Macedonian", TranslateLanguage.MACEDONIAN),
        TranslationLang("ms", "Malay", TranslateLanguage.MALAY),
        TranslationLang("mt", "Maltese", TranslateLanguage.MALTESE),
        TranslationLang("mr", "Marathi", TranslateLanguage.MARATHI),
        TranslationLang("no", "Norwegian", TranslateLanguage.NORWEGIAN),
        TranslationLang("fa", "Persian", TranslateLanguage.PERSIAN),
        TranslationLang("pl", "Polish", TranslateLanguage.POLISH),
        TranslationLang("pt", "Portuguese", TranslateLanguage.PORTUGUESE),
        TranslationLang("ro", "Romanian", TranslateLanguage.ROMANIAN),
        TranslationLang("ru", "Russian", TranslateLanguage.RUSSIAN),
        TranslationLang("gd", "Scottish Gaelic", "gd"),
        TranslationLang("sk", "Slovak", TranslateLanguage.SLOVAK),
        TranslationLang("sl", "Slovenian", TranslateLanguage.SLOVENIAN),
        TranslationLang("es", "Spanish", TranslateLanguage.SPANISH),
        TranslationLang("sw", "Swahili", TranslateLanguage.SWAHILI),
        TranslationLang("sv", "Swedish", TranslateLanguage.SWEDISH),
        TranslationLang("tl", "Tagalog", TranslateLanguage.TAGALOG),
        TranslationLang("ta", "Tamil", TranslateLanguage.TAMIL),
        TranslationLang("te", "Telugu", TranslateLanguage.TELUGU),
        TranslationLang("th", "Thai", TranslateLanguage.THAI),
        TranslationLang("tr", "Turkish", TranslateLanguage.TURKISH),
        TranslationLang("uk", "Ukrainian", TranslateLanguage.UKRAINIAN),
        TranslationLang("ur", "Urdu", TranslateLanguage.URDU),
        TranslationLang("vi", "Vietnamese", TranslateLanguage.VIETNAMESE),
        TranslationLang("cy", "Welsh", TranslateLanguage.WELSH),
    )

    private val byCode = ALL.associateBy { it.code }
    private val byMlKit = ALL.associateBy { it.mlKitCode }

    fun fromCode(code: String): TranslationLang? = byCode[code.lowercase()]

    fun fromMlKitCode(mlKitCode: String): TranslationLang? = byMlKit[mlKitCode]

    fun displayName(code: String): String = fromCode(code)?.displayName ?: code

    fun mlKitCode(code: String): String? = fromCode(code)?.mlKitCode

    val displayNames: List<String> = ALL.map { it.displayName }
}
