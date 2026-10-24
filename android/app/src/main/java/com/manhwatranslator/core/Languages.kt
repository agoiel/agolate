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
    fun tgt(code: String): TargetLang = targets.firstOrNull { it.code == code } ?: targets[0]
}
