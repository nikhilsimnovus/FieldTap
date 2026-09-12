package com.fieldtap.ui.theme

import java.util.Locale

/**
 * The display transform for [com.fieldtap.ui.components.Eyebrow] labels: it uppercases the label but keeps
 * the fixed casing of unit tokens, so "Below -105 dBm" reads "BELOW -105 dBm", never "BELOW -105 DBM". An RF
 * reader parses dBm with its exact casing (dBm, not DBM; dB, not DB), and a blanket uppercase mangles it —
 * exactly the detail this app's audience notices.
 *
 * A token — a run between single spaces — that equals one of [UNIT_TOKENS] case-insensitively keeps the
 * unit's canonical spelling; every other token is uppercased in [locale]. Pure, so it is unit-tested.
 */
object EyebrowCase {
    /**
     * Units whose casing is fixed, so an uppercase pass must not turn "dBm" into "DBM" or "kHz" into "KHZ".
     * The signal units an eyebrow or a stat-tile label can carry; add a unit here when a new label needs it.
     */
    val UNIT_TOKENS: List<String> = listOf("dBm", "dBi", "dBµV", "dB", "Hz", "kHz", "MHz", "GHz")

    /** [text] uppercased in [locale], with any [UNIT_TOKENS] token kept in its canonical casing. */
    fun uppercase(text: String, locale: Locale): String =
        text.split(' ').joinToString(" ") { token ->
            UNIT_TOKENS.firstOrNull { it.equals(token, ignoreCase = true) } ?: token.uppercase(locale)
        }
}
