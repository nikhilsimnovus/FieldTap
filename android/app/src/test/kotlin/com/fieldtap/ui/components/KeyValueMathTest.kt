package com.fieldtap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyValueMathTest {
    // A 300 px row with a 12 px gap leaves 288 px; 40% of that is 115.

    @Test
    fun aKeyAndValueThatFitKeepTheirWidthsAndTheValueGetsTheRest() {
        assertEquals(50 to 238, KeyValueMath.widths(availablePx = 300, gapPx = 12, keyPx = 50, valuePx = 100))
    }

    @Test
    fun aLongValueWrapsInAllTheRoomAShortKeyLeavesNotInHalfTheRow() {
        val (key, value) = KeyValueMath.widths(availablePx = 300, gapPx = 12, keyPx = 40, valuePx = 500)

        assertEquals(40, key)
        assertEquals(248, value)
        assertTrue("the value used to get at most half the row", value > 300 / 2)
    }

    @Test
    fun aLongKeyWrapsOnlyAsFarAsAShortValueNeeds() {
        assertEquals(228 to 60, KeyValueMath.widths(availablePx = 300, gapPx = 12, keyPx = 250, valuePx = 60))
    }

    @Test
    fun whenBothAreLongTheKeyKeepsFortyPercent() {
        assertEquals(115 to 173, KeyValueMath.widths(availablePx = 300, gapPx = 12, keyPx = 250, valuePx = 500))
    }

    @Test
    fun widthsAreNeverNegative() {
        assertEquals(0 to 0, KeyValueMath.widths(availablePx = 10, gapPx = 12, keyPx = 30, valuePx = 30))
        assertEquals(0 to 288, KeyValueMath.widths(availablePx = 300, gapPx = 12, keyPx = -5, valuePx = 400))
    }
}
