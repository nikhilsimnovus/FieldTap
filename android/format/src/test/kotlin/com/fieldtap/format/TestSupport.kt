package com.fieldtap.format

import java.util.Locale

/**
 * A string holding one code point, including lone surrogates and control characters, so that test
 * sources never carry invisible or unencodable characters.
 */
internal fun ch(codePoint: Int): String = String(Character.toChars(codePoint))

/**
 * Default locales that would change digits, separators or the calendar if a writer formatted
 * through them: German decimal commas, Arabic-Indic, Thai and Devanagari digits, a Buddhist
 * calendar, and Turkish case rules.
 */
internal val TRICKY_LOCALES: List<String> = listOf(
    "de-DE",
    "ar-EG-u-nu-arab",
    "th-TH-u-ca-buddhist-nu-thai",
    "hi-IN-u-nu-deva",
    "tr-TR",
)

/** Runs [block] with [languageTag] as the JVM default locale for every category, then restores it. */
internal inline fun <T> withDefaultLocale(languageTag: String, block: () -> T): T {
    val saved = Locale.getDefault()
    val savedFormat = Locale.getDefault(Locale.Category.FORMAT)
    val savedDisplay = Locale.getDefault(Locale.Category.DISPLAY)
    Locale.setDefault(Locale.forLanguageTag(languageTag))
    try {
        return block()
    } finally {
        Locale.setDefault(saved)
        Locale.setDefault(Locale.Category.FORMAT, savedFormat)
        Locale.setDefault(Locale.Category.DISPLAY, savedDisplay)
    }
}
