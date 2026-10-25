package com.manhwatranslator.core

/** Языки оригинала и перевода. Коды совпадают с ПК-версией. */
data class SourceLang(
    val code: String,
    val name: String,
    val script: String,      // какой распознаватель ML Kit нужен: latin | korean | japanese | chinese
    val google: String,
    val deepl: String,
    val microsoft: String,
    val llm: String,
    val joiner: String,      // чем склеивать строки внутри облака
    val rtl: Boolean,        // облака в одном ряду читаются справа налево (манга)
)

data class TargetLang(
    val code: String,
    val name: String,
    val google: String,
    val deepl: String,
    val microsoft: String,
    val llm: String,
)

object Languages {
    val sources = listOf(
        SourceLang("ko", "Корейский (манхва)", "korean", "ko", "KO", "ko", "Korean", " ", false),
        SourceLang("ja", "Японский (манга)", "japanese", "ja", "JA", "ja", "Japanese", "", true),
        SourceLang("zh", "Китайский упрощённый (маньхуа)", "chinese", "zh-CN", "ZH", "zh-Hans", "Simplified Chinese", "", false),
        SourceLang("zh-tw", "Китайский традиционный", "chinese", "zh-TW", "ZH", "zh-Hant", "Traditional Chinese", "", false),
        SourceLang("en", "Английский (сканлейты)", "latin", "en", "EN", "en", "English", " ", false),
    )

    val targets = listOf(
        TargetLang("ru", "Русский", "ru", "RU", "ru", "Russian"),
        TargetLang("uk", "Украинский", "uk", "UK", "uk", "Ukrainian"),
        TargetLang("en", "Английский", "en", "EN-US", "en", "English"),
    )

    fun src(code: String): SourceLang = sources.firstOrNull { it.code == code } ?: sources[0]

    private val hangul = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")
    private val kana = Regex("[\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF66-\\uFF9F]")
    private val han = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    private val latin = Regex("[A-Za-z\\u00C0-\\u024F]")

    /**
     * Язык конкретного облака по символам. Если в облаке только латиница, оно переводится
     * как английское, даже когда в настройках выбран корейский (страница со сканлейтом,
     * вывески, вставки на английском). Иначе — язык из настроек.
     */
    fun detect(text: String, selected: SourceLang): SourceLang {
        val h = hangul.findAll(text).count()
        val k = kana.findAll(text).count()
        val c = han.findAll(text).count()
        val l = latin.findAll(text).count()
        return when {
            h > 0 && h >= k -> src("ko")
            k > 0 -> src("ja")
            c > 0 -> if (selected.code == "ja" || selected.code.startsWith("zh")) selected else src("zh")
            l >= 2 -> src("en")
            else -> selected
        }
    }
    fun tgt(code: String): TargetLang = targets.firstOrNull { it.code == code } ?: targets[0]
}
