package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.format.FixProvider
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import org.junit.Assert.assertTrue

/*
 * Shared by the location, privacy and export tests of workstream location-privacy-core. Nothing here
 * uses the :format writers or readers, so a failure points at the code under test.
 */

/** Wall clock minus elapsedRealtime, as in the golden session and `ManualClock`'s defaults. */
internal const val WALL_MINUS_ELAPSED_MS: Long = 1_789_050_600_000L - 25_323_456L

/** A fix at [elapsedMs] on the monotonic clock, delivered [deliveryDelayMs] later. */
internal fun fix(
    elapsedMs: Long,
    lat: Double = 38.8895,
    lon: Double = -77.0353,
    provider: FixProvider = FixProvider.GPS,
    accuracyM: Double? = 5.0,
    altitudeM: Double? = 18.0,
    speedMps: Double? = 1.4,
    mock: Boolean = false,
    deliveryDelayMs: Long = 150,
): FixSample = FixSample(
    elapsedMs = elapsedMs,
    wallMs = elapsedMs + WALL_MINUS_ELAPSED_MS,
    lat = lat,
    lon = lon,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    provider = provider,
    mock = mock,
    observedWallMs = elapsedMs + deliveryDelayMs + WALL_MINUS_ELAPSED_MS,
    observedElapsedMs = elapsedMs + deliveryDelayMs,
)

/** `%.7f` of the exact binary value, as Python writes it; never `-0.0000000`. */
internal fun seven(value: Double): String = BigDecimal(value).setScale(7, RoundingMode.HALF_EVEN).toPlainString()

/**
 * The golden session in tests/fixtures/android_session/, read relative to the :core module
 * directory. Missing fixture files fail the test; they never skip it.
 */
internal object Golden {
    const val DIR_NAME: String = "20260910-143000_Mall-walk-north-path"

    /** `started_utc`, 2026-09-10T14:30:00.000+00:00. */
    const val START_WALL_MS: Long = 1_789_050_600_000L

    /** `CellInfo.getTimestampMillis()` at `started_utc` in the fixture generator. */
    const val BOOT_MS_AT_START: Long = 25_323_456L

    val dir: File
        get() {
            val dir = File("../../tests/fixtures/android_session/$DIR_NAME")
            assertTrue("golden session missing at ${dir.absolutePath}", dir.isDirectory)
            return dir
        }

    fun file(name: String): File {
        val file = File(dir, name)
        assertTrue("golden file missing: ${file.absolutePath}", file.isFile)
        return file
    }

    fun bytes(name: String): ByteArray = file(name).readBytes()

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    /** Records without their CR LF; the text must end in CR LF. */
    fun records(text: String): List<String> {
        assertTrue("text does not end in CR LF", text.isEmpty() || text.endsWith("\r\n"))
        return if (text.isEmpty()) emptyList() else text.removeSuffix("\r\n").split("\r\n")
    }

    /** Python csv's minimal quoting undone. */
    fun fields(record: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < record.length) {
            val c = record[i]
            when {
                inQuotes && c == '"' && i + 1 < record.length && record[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    /** A CSV file as a header and rows of unquoted fields. */
    class Table(val header: List<String>, val rows: List<List<String>>) {
        fun column(name: String): Int = header.indexOf(name).also { assertTrue("no column $name", it >= 0) }
    }

    fun table(name: String): Table {
        val records = records(text(name))
        return Table(fields(records.first()), records.drop(1).map { fields(it) })
    }

    fun elapsedAt(wallMs: Long): Long = BOOT_MS_AT_START + (wallMs - START_WALL_MS)

    fun utcMs(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    /** `time_epoch` text to integer milliseconds, with no double involved. */
    fun epochMs(text: String): Long {
        val parts = text.split('.')
        assertTrue("not a time_epoch: $text", parts.size == 2 && parts[1].length == 3)
        return parts[0].toLong() * 1000 + parts[1].toLong()
    }

    /** track.csv as the fixes the phone delivered, 150 ms after each fix time. */
    fun trackFixes(): List<FixSample> {
        val track = table("track.csv")
        val time = track.column("time_utc")
        val lat = track.column("lat")
        val lon = track.column("lon")
        val accuracy = track.column("accuracy_m")
        val altitude = track.column("altitude_m")
        val speed = track.column("speed_mps")
        val provider = track.column("provider")
        return track.rows.map { row ->
            val wall = utcMs(row[time])
            val elapsed = elapsedAt(wall)
            FixSample(
                elapsedMs = elapsed,
                wallMs = wall,
                lat = row[lat].toDouble(),
                lon = row[lon].toDouble(),
                accuracyM = row[accuracy].toDoubleOrNull(),
                altitudeM = row[altitude].toDoubleOrNull(),
                speedMps = row[speed].toDoubleOrNull(),
                provider = FixProvider.entries.first { it.wire == row[provider] },
                mock = false,
                observedWallMs = wall + 150,
                observedElapsedMs = elapsed + 150,
            )
        }
    }
}
