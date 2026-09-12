package com.fieldtap.ui.theme

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class EyebrowCaseTest {
    private val en = Locale.US

    @Test
    fun ordinaryLabelsAreFullyUppercased() {
        assertEquals("MEDIAN NR RSRP", EyebrowCase.uppercase("Median NR RSRP", en))
        assertEquals("DURATION", EyebrowCase.uppercase("Duration", en))
        assertEquals("SAMPLING GAPS", EyebrowCase.uppercase("Sampling gaps", en))
        assertEquals("SERVING · NR SA", EyebrowCase.uppercase("Serving · NR SA", en))
    }

    @Test
    fun unitTokensKeepTheirFixedCasing() {
        assertEquals("BELOW -105 dBm", EyebrowCase.uppercase("Below -105 dBm", en))
        assertEquals("SINR IN dB", EyebrowCase.uppercase("SINR in dB", en))
        assertEquals("dBm", EyebrowCase.uppercase("dBm", en))
    }

    @Test
    fun unitTokensAreMatchedCaseInsensitivelyButRenderedCanonically() {
        assertEquals("dBm", EyebrowCase.uppercase("DBM", en))
        assertEquals("dB", EyebrowCase.uppercase("db", en))
    }

    @Test
    fun aWordThatMerelyContainsAUnitIsStillUppercased() {
        // "decibels" contains "db"? No — the token must equal a unit; a non-unit word is uppercased whole.
        assertEquals("DECIBELS", EyebrowCase.uppercase("decibels", en))
        assertEquals("HERTZLY", EyebrowCase.uppercase("hertzly", en))
    }

    @Test
    fun theTurkishLocaleDoesNotDottedUppercaseAUnit() {
        // A blanket uppercase of "in" in Turkish yields "İN"; the unit "dB" is untouched regardless.
        assertEquals("dB", EyebrowCase.uppercase("dB", Locale.forLanguageTag("tr-TR")))
    }
}
