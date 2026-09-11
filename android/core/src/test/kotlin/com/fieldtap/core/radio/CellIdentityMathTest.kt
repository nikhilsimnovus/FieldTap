package com.fieldtap.core.radio

import com.fieldtap.format.ServingRat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellIdentityMathTest {

    @Test
    fun band66IsTheGoldenSessionsUplink() {
        assertEquals(132_322, CellIdentityMath.lteUlEarfcn(66_786, 66))
    }

    @Test
    fun fddBandsUseTheOffsetsOfTs36101Table573_1() {
        // (band, DL EARFCN, UL EARFCN), each pair checked against the band's DL and UL channel ranges.
        val cases = listOf(
            Triple(1, 300, 18_300),
            Triple(2, 900, 18_900),
            Triple(3, 1_575, 19_575),
            Triple(4, 2_175, 20_175),
            Triple(5, 2_525, 20_525),
            Triple(7, 3_100, 21_100),
            Triple(8, 3_625, 21_625),
            Triple(12, 5_110, 23_110),
            Triple(13, 5_230, 23_230),
            Triple(14, 5_330, 23_330),
            Triple(17, 5_790, 23_790),
            Triple(20, 6_300, 24_300),
            Triple(25, 8_365, 26_365),
            Triple(26, 8_865, 26_865),
            Triple(28, 9_460, 27_460),
            Triple(30, 9_820, 27_710),
            Triple(31, 9_900, 27_790),
            Triple(65, 65_536, 131_072),
            Triple(66, 66_436, 131_972),
            Triple(68, 67_600, 132_736),
            Triple(70, 68_336, 132_972),
            Triple(71, 68_686, 133_222),
            Triple(74, 69_100, 133_636),
            Triple(85, 70_400, 134_036),
            Triple(87, 70_550, 134_186),
            Triple(88, 70_600, 134_236),
            Triple(103, 70_650, 134_286),
            Triple(106, 70_680, 134_316),
        )
        for ((band, dl, ul) in cases) {
            assertEquals("band $band DL $dl", ul, CellIdentityMath.lteUlEarfcn(dl, band))
        }
    }

    @Test
    fun theTopOfAnAsymmetricBandHasNoPairedUplink() {
        assertEquals(132_671, CellIdentityMath.lteUlEarfcn(67_135, 66))
        assertNull(CellIdentityMath.lteUlEarfcn(67_136, 66))
        assertNull(CellIdentityMath.lteUlEarfcn(67_335, 66))
        assertEquals(133_121, CellIdentityMath.lteUlEarfcn(68_485, 70))
        assertNull(CellIdentityMath.lteUlEarfcn(68_486, 70))
    }

    @Test
    fun aDlEarfcnOutsideItsBandHasNoUplink() {
        // The emulator reports EARFCN 103 with band 42, and nothing checks such pairs but this.
        assertNull(CellIdentityMath.lteUlEarfcn(103, 66))
        assertNull(CellIdentityMath.lteUlEarfcn(66_786, 3))
        assertNull(CellIdentityMath.lteUlEarfcn(600, 1))
        assertNull(CellIdentityMath.lteUlEarfcn(-1, 1))
    }

    @Test
    fun tddSupplementalDownlinkUnknownAndMissingBandsHaveNoUplink() {
        for (band in listOf(33, 38, 39, 40, 41, 42, 43, 46, 48, 53)) {
            assertNull("TDD band $band", CellIdentityMath.lteUlEarfcn(40_000, band))
        }
        assertNull(CellIdentityMath.lteUlEarfcn(41_590, 42))
        assertNull(CellIdentityMath.lteUlEarfcn(9_700, 29))
        assertNull(CellIdentityMath.lteUlEarfcn(10_000, 32))
        assertNull(CellIdentityMath.lteUlEarfcn(67_400, 67))
        assertNull(CellIdentityMath.lteUlEarfcn(68_000, 69))
        assertNull(CellIdentityMath.lteUlEarfcn(69_500, 75))
        assertNull(CellIdentityMath.lteUlEarfcn(70_330, 76))
        assertNull(CellIdentityMath.lteUlEarfcn(66_786, 999))
        assertNull(CellIdentityMath.lteUlEarfcn(66_786, null))
    }

    @Test
    fun plausibleNeedsPciAndDlEarfcnInsideTheRangesOfTheRat() {
        assertTrue(CellIdentityMath.isPlausible(ServingRat.LTE, 212, 66_786))
        assertTrue(CellIdentityMath.isPlausible(ServingRat.LTE, 0, 0))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.LTE, null, 66_786))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.LTE, 212, null))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.LTE, 504, 66_786))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.LTE, 212, 262_144))
        assertTrue(CellIdentityMath.isPlausible(ServingRat.NR, 1_007, 3_279_165))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.NR, 1_008, 650_000))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.NR, 393, 3_279_166))
        assertFalse(CellIdentityMath.isPlausible(ServingRat.NR, -1, 650_000))
    }

    @Test
    fun bandwidthIsKhzOverAThousandInsideTheRange() {
        assertEquals(20.0, CellIdentityMath.bandwidthMhz(20_000)!!, 0.0)
        assertEquals(1.4, CellIdentityMath.bandwidthMhz(1_400)!!, 0.0)
        assertEquals(0.0, CellIdentityMath.bandwidthMhz(0)!!, 0.0)
        assertEquals(400.0, CellIdentityMath.bandwidthMhz(400_000)!!, 0.0)
        assertNull(CellIdentityMath.bandwidthMhz(400_001))
        assertNull(CellIdentityMath.bandwidthMhz(-1))
        assertNull(CellIdentityMath.bandwidthMhz(null))
    }

    @Test
    fun inRangeKeepsValuesInsideAndBlanksTheRest() {
        assertEquals(5, CellIdentityMath.inRange(5, 0..10))
        assertEquals(0, CellIdentityMath.inRange(0, 0..10))
        assertEquals(10, CellIdentityMath.inRange(10, 0..10))
        assertNull(CellIdentityMath.inRange(11, 0..10))
        assertNull(CellIdentityMath.inRange(-1, 0..10))
        assertNull(CellIdentityMath.inRange(null, 0..10))
        assertEquals(7, CellIdentityMath.inRange(7, null))
        assertEquals(68_719_476_735L, CellIdentityMath.inRange(68_719_476_735L, 0L..68_719_476_735L))
        assertNull(CellIdentityMath.inRange(68_719_476_736L, 0L..68_719_476_735L))
        assertNull(CellIdentityMath.inRange(Double.NaN, 0.0..400.0))
        assertEquals(20.5, CellIdentityMath.inRange(20.5, -43.0..20.5))
    }
}
