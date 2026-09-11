package com.fieldtap.core.export

import com.fieldtap.core.location.Golden
import com.fieldtap.format.DeviceMeta
import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.PrivacyMeta
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.SummaryMeta
import com.fieldtap.format.TransportMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionReducerTest {

    private val positioned = listOf(SessionFile.KPI, SessionFile.CELLINFO, SessionFile.TRACK)

    /** The contract's approx_110m text, computed with BigDecimal alone: 3 decimals half-even, written with 7. */
    private fun approx(text: String): String =
        BigDecimal(text.toDouble()).setScale(3, RoundingMode.HALF_EVEN).setScale(7).toPlainString()

    /** `fieldtap validate`: `abs(n * 1000 - round(n * 1000)) <= 1e-4`, Python's round being half-even. */
    private fun pythonAcceptsApprox(text: String): Boolean {
        val scaled = text.toDouble() * 1_000
        return abs(scaled - Math.rint(scaled)) <= 1e-4
    }

    @Test
    fun fullLeavesEveryFileUnchanged() {
        for (file in SessionFile.BUNDLE) {
            val text = Golden.text(file.fileName)
            assertEquals(file.fileName, text, PrecisionReducer.reduceCsvText(file, text, LocationPrecision.FULL))
        }
    }

    @Test
    fun filesWithoutPositionsAreUnchangedAtEveryPrecision() {
        for (file in listOf(SessionFile.SESSION_JSON, SessionFile.EVENTS, SessionFile.TRAFFIC, SessionFile.CELLS)) {
            val text = Golden.text(file.fileName)
            for (precision in LocationPrecision.entries) {
                assertEquals("${file.fileName} at $precision", text, PrecisionReducer.reduceCsvText(file, text, precision))
            }
        }
    }

    @Test
    fun approx110mRoundsEveryPositionAndLeavesEveryOtherFieldAsItWas() {
        for (file in positioned) {
            val text = Golden.text(file.fileName)
            val reduced = requireNotNull(PrecisionReducer.reduceCsvText(file, text, LocationPrecision.APPROX_110M))
            assertEquals("${file.fileName}: seven decimals either way", text.length, reduced.length)

            val before = Golden.records(text)
            val after = Golden.records(reduced)
            assertEquals(before.size, after.size)
            assertEquals("header", before.first(), after.first())
            val header = Golden.fields(before.first())
            val lat = header.indexOf("lat")
            val lon = header.indexOf("lon")
            var positions = 0
            for (i in 1 until before.size) {
                val original = Golden.fields(before[i])
                val rounded = Golden.fields(after[i])
                assertEquals(original.size, rounded.size)
                for (j in original.indices) {
                    val where = "${file.fileName} record $i ${header[j]}"
                    when {
                        j != lat && j != lon -> assertEquals(where, original[j], rounded[j])
                        original[j].isEmpty() -> assertEquals(where, "", rounded[j])
                        else -> {
                            assertEquals(where, approx(original[j]), rounded[j])
                            assertTrue(where, APPROX_TEXT.matches(rounded[j]))
                            assertTrue(where, pythonAcceptsApprox(rounded[j]))
                            positions++
                        }
                    }
                }
            }
            assertTrue("${file.fileName} has positions", positions > 0)
        }
    }

    @Test
    fun theFirstGoldenPositionRoundsOnTheExactBinaryValue() {
        // 38.8895 is stored as 38.88949999999999818..., so half-even on the exact value gives 38.889, not 38.890.
        val kpi = requireNotNull(PrecisionReducer.reduceCsvText(SessionFile.KPI, Golden.text("kpi.csv"), LocationPrecision.APPROX_110M))
        val first = Golden.fields(Golden.records(kpi)[1])
        assertEquals("38.8890000", first[9])
        assertEquals("-77.0350000", first[10])
    }

    @Test
    fun roundingIsHalfEvenOnTheExactBinaryValueWithoutNegativeZero() {
        val text = TRACK_HEADER + trackRow("38.8895123", "-77.0355") + trackRow("0.0625", "-0.0004") +
            trackRow("38.8905", "-12.3455")
        val expected = TRACK_HEADER + trackRow("38.8900000", "-77.0350000") + trackRow("0.0620000", "0.0000000") +
            trackRow("38.8910000", "-12.3450000")
        assertEquals(expected, PrecisionReducer.reduceCsvText(SessionFile.TRACK, text, LocationPrecision.APPROX_110M))
    }

    @Test
    fun noneBlanksKpiAndCellinfoPositionsAndLeavesOutTrack() {
        assertNull(PrecisionReducer.reduceCsvText(SessionFile.TRACK, Golden.text("track.csv"), LocationPrecision.NONE))
        for (file in listOf(SessionFile.KPI, SessionFile.CELLINFO)) {
            val text = Golden.text(file.fileName)
            val reduced = requireNotNull(PrecisionReducer.reduceCsvText(file, text, LocationPrecision.NONE))
            val before = Golden.records(text)
            val after = Golden.records(reduced)
            assertEquals(before.size, after.size)
            assertEquals(before.first(), after.first())
            val header = Golden.fields(before.first())
            val lat = header.indexOf("lat")
            val lon = header.indexOf("lon")
            for (i in 1 until before.size) {
                val original = Golden.fields(before[i])
                val blanked = Golden.fields(after[i])
                assertEquals("", blanked[lat])
                assertEquals("", blanked[lon])
                assertEquals(
                    "${file.fileName} record $i",
                    original.filterIndexed { j, _ -> j != lat && j != lon },
                    blanked.filterIndexed { j, _ -> j != lat && j != lon },
                )
            }
        }
    }

    @Test
    fun aTornLastLineIsDroppedWhenAFileIsRewritten() {
        val text = Golden.text("kpi.csv")
        val torn = text + ",1789050720.400,lte,,213,-8"
        for (precision in listOf(LocationPrecision.APPROX_110M, LocationPrecision.NONE)) {
            assertEquals(
                PrecisionReducer.reduceCsvText(SessionFile.KPI, text, precision),
                PrecisionReducer.reduceCsvText(SessionFile.KPI, torn, precision),
            )
        }
        assertEquals("", PrecisionReducer.reduceCsvText(SessionFile.KPI, "frame,time_epoch,rat", LocationPrecision.NONE))
    }

    @Test
    fun emptyAndHeaderOnlyFilesSurvive() {
        assertEquals("", PrecisionReducer.reduceCsvText(SessionFile.CELLINFO, "", LocationPrecision.APPROX_110M))
        assertEquals(TRACK_HEADER, PrecisionReducer.reduceCsvText(SessionFile.TRACK, TRACK_HEADER, LocationPrecision.APPROX_110M))
    }

    @Test
    fun quotedFieldsKeepTheirExactBytes() {
        val cellinfo = Golden.records(Golden.text("cellinfo.csv"))
        val header = Golden.fields(cellinfo[0])
        val fields = Golden.fields(cellinfo[1]).toMutableList()
        fields[header.indexOf("operator")] = "Big \"Net\", Inc"
        fields[header.indexOf("bands")] = "66, 2"
        fields[header.indexOf("additional_plmns")] = "310260, 311480"
        fields[header.indexOf("lat")] = "38.8895123"
        fields[header.indexOf("lon")] = "-77.0353999"
        val expected = fields.toMutableList()
        expected[header.indexOf("lat")] = "38.8900000"
        expected[header.indexOf("lon")] = "-77.0350000"

        val text = cellinfo[0] + "\r\n" + csv(fields)
        assertTrue("the row really is quoted", csv(fields).contains("\"Big \"\"Net\"\", Inc\""))
        assertEquals(
            cellinfo[0] + "\r\n" + csv(expected),
            PrecisionReducer.reduceCsvText(SessionFile.CELLINFO, text, LocationPrecision.APPROX_110M),
        )
    }

    @Test
    fun everyRecordKeepsItsOwnLineEnding() {
        val text = TRACK_HEADER + trackRow("38.8895123", "-77.0355").removeSuffix("\r\n") + "\n" + trackRow("0.0625", "-0.0004")
        val expected = TRACK_HEADER + trackRow("38.8900000", "-77.0350000").removeSuffix("\r\n") + "\n" +
            trackRow("0.0620000", "0.0000000")
        assertEquals(expected, PrecisionReducer.reduceCsvText(SessionFile.TRACK, text, LocationPrecision.APPROX_110M))
    }

    @Test
    fun aPositionThatIsNotAFiniteNumberIsBlankedInKpiAndItsTrackRowDropped() {
        val kpiHeader = "frame,time_epoch,rat,meas_id,pci,rsrp_dbm,rsrq_db,sinr_db,comment,lat,lon\r\n"
        val row = ",1789050600.400,lte,,212,-84.0,-8.0,15.0,android-api age_ms=500 src=request,"
        val kpi = kpiHeader + row + "38.8895123,north\r\n" + row + "NaN,-77.0355\r\n" + row + "38.8895123,-77.0355\r\n"
        assertEquals(
            kpiHeader + row + ",\r\n" + row + ",\r\n" + row + "38.8900000,-77.0350000\r\n",
            PrecisionReducer.reduceCsvText(SessionFile.KPI, kpi, LocationPrecision.APPROX_110M),
        )

        val track = TRACK_HEADER + trackRow("38.8895123", "Infinity") + trackRow("0.0625", "-0.0004")
        assertEquals(
            TRACK_HEADER + trackRow("0.0620000", "0.0000000"),
            PrecisionReducer.reduceCsvText(SessionFile.TRACK, track, LocationPrecision.APPROX_110M),
        )
    }

    @Test
    fun aRowThatDoesNotLineUpWithTheHeaderIsDropped() {
        val shortRow = "2026-09-10T14:30:00.000+00:00,38.8895123,-77.0355,gps,android\r\n"
        val text = TRACK_HEADER + shortRow + trackRow("0.0625", "-0.0004")
        assertEquals(
            TRACK_HEADER + trackRow("0.0620000", "0.0000000"),
            PrecisionReducer.reduceCsvText(SessionFile.TRACK, text, LocationPrecision.APPROX_110M),
        )
    }

    @Test
    fun aRecordLongerThanTheLimitFailsTheStream() {
        val input = ByteArrayInputStream(("x".repeat(5_000) + "\r\n").toByteArray(Charsets.US_ASCII))
        assertThrows(IOException::class.java) {
            PrecisionReducer.reduceCsv(SessionFile.KPI, LocationPrecision.NONE, input, ByteArrayOutputStream(), maxRecordBytes = 1_024)
        }
    }

    @Test
    fun omitsAndRewritesDescribeTheCopy() {
        assertTrue(PrecisionReducer.omits(SessionFile.TRACK, LocationPrecision.NONE))
        assertFalse(PrecisionReducer.omits(SessionFile.TRACK, LocationPrecision.APPROX_110M))
        assertFalse(PrecisionReducer.omits(SessionFile.KPI, LocationPrecision.NONE))
        for (file in SessionFile.BUNDLE) assertFalse(PrecisionReducer.rewrites(file, LocationPrecision.FULL))
        assertEquals(
            positioned.toSet(),
            SessionFile.BUNDLE.filter { PrecisionReducer.rewrites(it, LocationPrecision.APPROX_110M) }.toSet(),
        )
    }

    @Test
    fun reduceMetaSetsThePrecisionAndDropsTrackOnlyAtNone() {
        val meta = SessionMeta(
            sessionId = "3f6c1a2e-8b7d-4e21-9c55-2a1f0b9d7e44",
            name = "Mall walk (north path)",
            startedUtcMs = Golden.START_WALL_MS,
            stoppedUtcMs = Golden.START_WALL_MS + 120_000,
            transport = TransportMeta(appVersion = "0.1.0", versionCode = 1),
            device = DeviceMeta(key = "app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f"),
            summary = SummaryMeta(stoppedBy = "user"),
            privacy = PrivacyMeta(
                locationPrecision = LocationPrecision.FULL,
                zonePauses = 2,
                consentVersion = "2026-09-10-draft",
                consentSha256 = "0".repeat(64),
            ),
        )

        val approx = PrecisionReducer.reduceMeta(meta, LocationPrecision.APPROX_110M)
        assertEquals(LocationPrecision.APPROX_110M, approx.privacy.locationPrecision)
        assertEquals(meta.copy(privacy = meta.privacy.copy(locationPrecision = LocationPrecision.APPROX_110M)), approx)

        val none = PrecisionReducer.reduceMeta(meta, LocationPrecision.NONE)
        assertEquals(LocationPrecision.NONE, none.privacy.locationPrecision)
        assertEquals(SessionFile.CSV - SessionFile.TRACK, none.files)
        assertEquals(2, none.privacy.zonePauses)

        assertEquals(meta, PrecisionReducer.reduceMeta(meta, LocationPrecision.FULL))
    }

    private companion object {
        val APPROX_TEXT = Regex("-?[0-9]+\\.[0-9]{3}0000")

        const val TRACK_HEADER = "time_utc,lat,lon,accuracy_m,altitude_m,speed_mps,provider,source\r\n"

        fun trackRow(lat: String, lon: String) = "2026-09-10T14:30:00.000+00:00,$lat,$lon,4.9,18.0,1.40,gps,android\r\n"

        /** Python csv's minimal quoting, CR LF at the end. */
        fun csv(fields: List<String>): String = fields.joinToString(",") { field ->
            if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                "\"" + field.replace("\"", "\"\"") + "\""
            } else {
                field
            }
        } + "\r\n"
    }
}
