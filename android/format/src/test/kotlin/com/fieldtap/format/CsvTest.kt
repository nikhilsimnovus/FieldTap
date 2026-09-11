package com.fieldtap.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {

    @Test
    fun recordQuotesOnlyCommaQuoteCrAndLf() {
        assertEquals(
            "a,b c, lead,trail ,tab\tx,,0,-84.0\r\n",
            Csv.record(listOf("a", "b c", " lead", "trail ", "tab\tx", "", "0", "-84.0")),
        )
        assertEquals(
            "\"a,b\",\"say \"\"hi\"\"\",\"c\rd\",\"e\nf\"\r\n",
            Csv.record(listOf("a,b", "say \"hi\"", "c\rd", "e\nf")),
        )
    }

    @Test
    fun recordOfALoneEmptyFieldIsQuotedAsPythonWritesIt() {
        assertEquals("\"\"\r\n", Csv.record(listOf("")))
        assertEquals(",\r\n", Csv.record(listOf("", "")))
        assertEquals("\r\n", Csv.record(emptyList()))
    }

    @Test
    fun textReplacesEachCrAndLfWithASpace() {
        assertEquals("", Csv.text(null))
        assertEquals("", Csv.text(""))
        assertEquals("a  b c d", Csv.text("a\r\nb\rc\nd"))
        val unicode = "North entrance " + ch(0x2013) + " badge " + ch(0x1F6B6)
        assertEquals(unicode, Csv.text(unicode))
    }

    @Test
    fun textReplacesLoneSurrogatesWhichUtf8CannotHold() {
        assertEquals("a" + ch(0xFFFD) + "b" + ch(0xFFFD), Csv.text("a" + ch(0xD800) + "b" + ch(0xDC00)))
    }

    @Test
    fun textIsQuotedByRecordWhenItHoldsACommaOrAQuote() {
        assertEquals(
            "\"Door \"\"B\"\", level 2  near lift\",x\r\n",
            Csv.record(listOf(Csv.text("Door \"B\", level 2\r\nnear lift"), "x")),
        )
    }

    @Test
    fun integersAreDigitsAndSentinelsAreBlank() {
        assertEquals("", Csv.int(null))
        assertEquals("0", Csv.int(0))
        assertEquals("-84", Csv.int(-84))
        assertEquals("1007", Csv.int(1007))
        assertEquals("2147483646", Csv.int(Int.MAX_VALUE - 1))
        assertEquals("", Csv.int(Int.MAX_VALUE))
        assertEquals("", Csv.int(Int.MIN_VALUE))
        assertEquals("", Csv.int(-Int.MAX_VALUE))
        assertEquals("-7", Csv.cell(-7))
        assertEquals("", Csv.cell(Int.MAX_VALUE))
    }

    @Test
    fun longsAreDigitsAndSentinelsAreBlank() {
        assertEquals("", Csv.long(null))
        assertEquals("68719476735", Csv.long(68_719_476_735L))
        assertEquals("25323856", Csv.long(25_323_856L))
        assertEquals("2147483649", Csv.long(2_147_483_649L))
        assertEquals("", Csv.long(Long.MAX_VALUE))
        assertEquals("", Csv.long(Long.MIN_VALUE))
        assertEquals("", Csv.long(2_147_483_647L))
        assertEquals("", Csv.long(-2_147_483_647L))
        assertEquals("", Csv.long(2_147_483_648L))
        assertEquals("", Csv.long(-2_147_483_648L))
    }

    @Test
    fun decimalsRoundHalfEvenOnTheExactBinaryValue() {
        assertEquals("0.062", Csv.decimal(0.0625, 3))
        assertEquals("4.2", Csv.decimal(4.25, 1))
        assertEquals("18.2", Csv.decimal(18.25, 1))
        assertEquals("1.12", Csv.decimal(1.125, 2))
        assertEquals("4.13", Csv.decimal(4_135 / 1000.0, 2))
        assertEquals("20.062", Csv.decimal(10_031_250L * 8 / 4.0 / 1e6, 3))
        assertEquals("2.67", Csv.decimal(2.675, 2))
        assertEquals("1.00", Csv.decimal(1.005, 2))
        assertEquals("0.3", Csv.decimal(0.35, 1))
        assertEquals("58.9", Csv.decimal(58.9, 1))
        assertEquals("2", Csv.decimal(2.5, 0))
        assertEquals("123456789.5", Csv.decimal(123_456_789.55, 1))
    }

    @Test
    fun decimalsAreNeverNegativeZero() {
        assertEquals("0.0", Csv.decimal(-0.04, 1))
        assertEquals("0.0", Csv.decimal(-0.0, 1))
        assertEquals("0.00", Csv.decimal(-0.001, 2))
        assertEquals("0", Csv.decimal(-0.4, 0))
        assertEquals("-0.1", Csv.decimal(-0.05000001, 1))
    }

    @Test
    fun decimalsAreBlankForUnknownValues() {
        assertEquals("", Csv.decimal(null, 1))
        assertEquals("", Csv.decimal(Double.NaN, 1))
        assertEquals("", Csv.decimal(Double.POSITIVE_INFINITY, 1))
        assertEquals("", Csv.decimal(Double.NEGATIVE_INFINITY, 1))
        assertEquals("", Csv.decimal(2_147_483_647.0, 1))
        assertEquals("", Csv.decimal(-2_147_483_648.0, 1))
    }

    @Test
    fun decimalsNeverUseScientificNotation() {
        assertEquals("0.0001000", Csv.decimal(0.0001, 7))
        assertEquals("0.0", Csv.decimal(1.0e-20, 1))
        assertEquals("100000000000000000000.0", Csv.decimal(1.0e20, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDecimalsAreAProgrammingError() {
        Csv.decimal(1.0, -1)
    }

    @Test
    fun androidIntegersAreWrittenAsDecimalsWithoutADouble() {
        assertEquals("-84.0", Csv.decimalOfInt(-84, 1))
        assertEquals("0.0", Csv.decimalOfInt(0, 1))
        assertEquals("15.00", Csv.decimalOfInt(15, 2))
        assertEquals("-3", Csv.decimalOfInt(-3, 0))
        assertEquals("", Csv.decimalOfInt(null, 1))
        assertEquals("", Csv.decimalOfInt(Int.MAX_VALUE, 1))
    }

    @Test
    fun epochIsBuiltFromIntegerMilliseconds() {
        assertEquals("1789050600.400", Csv.epoch(1_789_050_600_400L))
        assertEquals("1789050600.000", Csv.epoch(1_789_050_600_000L))
        assertEquals("1789050600.009", Csv.epoch(1_789_050_600_009L))
        assertEquals("1789050600.099", Csv.epoch(1_789_050_600_099L))
    }

    @Test
    fun utcFieldsAreTheContractFormOrBlank() {
        assertEquals("2026-09-10T14:30:00.400+00:00", Csv.utc(1_789_050_600_400L))
        assertEquals("", Csv.utc(null))
    }

    @Test
    fun flagsAndPythonBooleans() {
        assertEquals("1", Csv.flag(true))
        assertEquals("0", Csv.flag(false))
        assertEquals("True", Csv.pythonBool(true))
        assertEquals("False", Csv.pythonBool(false))
    }

    @Test
    fun listsAreJoinedWithCommaSpaceAndQuotedByRecord() {
        assertEquals("66, 2", Csv.list(listOf("66", "2")))
        assertEquals("41", Csv.list(listOf("41")))
        assertEquals("", Csv.list(emptyList()))
        assertEquals("\"66, 2\",x\r\n", Csv.record(listOf(Csv.list(listOf("66", "2")), "x")))
    }

    @Test
    fun parseRecordInvertsRecord() {
        val samples = listOf(
            listOf("a", "", "b c"),
            listOf("a,b", "say \"hi\"", "", "\"", ",", "\"\""),
            listOf("", ""),
            listOf(""),
            listOf("North entrance " + ch(0x2013) + " badge reader, door 3", "x"),
            listOf("c\rd", "e\nf", "g\r\nh"),
            listOf(" spaced ", "tab\tx"),
        )
        for (fields in samples) {
            val record = Csv.record(fields)
            assertTrue(record.endsWith(Csv.LINE_END))
            assertEquals(fields, Csv.parseRecord(record.removeSuffix(Csv.LINE_END)))
        }
    }

    @Test
    fun parseRecordIsAsLenientAsPythonsReader() {
        assertEquals(listOf("a", "bc", "d\"e", "f\"g", ""), Csv.parseRecord("a,\"b\"c,d\"e,\"f\"\"g\",\"\""))
        assertEquals(listOf(""), Csv.parseRecord(""))
        assertEquals(listOf("", "", ""), Csv.parseRecord(",,"))
        assertEquals(listOf("open"), Csv.parseRecord("\"open"))
    }

    @Test
    fun recordsSplitAtLineEndsAndReturnATornLastRecord() {
        assertEquals(emptyList<String>(), Csv.records(""))
        assertEquals(listOf("h1,h2", "1,2"), Csv.records("h1,h2\r\n1,2\r\n"))
        assertEquals(listOf("h1,h2", "1,2", "3,"), Csv.records("h1,h2\r\n1,2\r\n3,"))
        assertEquals(listOf("a", "", "b"), Csv.records("a\r\n\r\nb\r\n"))
        assertEquals(listOf("a", "b", "c"), Csv.records("a\nb\rc\r\n"))
    }

    @Test
    fun recordsKeepLineBreaksInsideQuotedFields() {
        val text = Csv.record(listOf("x", "c\r\nd")) + Csv.record(listOf("y", "e\nf"))
        val records = Csv.records(text)
        assertEquals(2, records.size)
        assertEquals(listOf("x", "c\r\nd"), Csv.parseRecord(records[0]))
        assertEquals(listOf("y", "e\nf"), Csv.parseRecord(records[1]))
        assertEquals(listOf("a,\"unterminated\r\nrest"), Csv.records("a,\"unterminated\r\nrest"))
    }

    @Test
    fun noDefaultLocaleChangesANumber() {
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertEquals(tag, "44.7", Csv.decimal(44.7, 1))
                assertEquals(tag, "-84.0", Csv.decimalOfInt(-84, 1))
                assertEquals(tag, "-1234567", Csv.int(-1_234_567))
                assertEquals(tag, "1789050600.400", Csv.epoch(1_789_050_600_400L))
                assertEquals(tag, "2026-09-10T14:30:00.400+00:00", Csv.utc(1_789_050_600_400L))
            }
        }
    }

    @Test
    fun theTrapsTheContractWarnsAboutAreReal() {
        assertEquals("44,7", String.format(Locale.GERMANY, "%.1f", 44.7))
        assertEquals("1.0E-4", 0.0001.toString())
    }
}
