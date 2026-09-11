package com.fieldtap.core.export

import com.fieldtap.core.location.Golden
import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val zipName = Golden.DIR_NAME + ".zip"

    private val exports: File get() = File(temp.root, "exports")

    private val bundleNames = SessionFile.BUNDLE.map { it.fileName }

    /** A closed session: the golden files copied into a directory of the same name. */
    private fun goldenCopy(): File {
        val dir = File(temp.root, "sessions/${Golden.DIR_NAME}")
        assertTrue(dir.mkdirs())
        for (file in SessionFile.BUNDLE) Golden.file(file.fileName).copyTo(File(dir, file.fileName))
        return dir
    }

    private fun ZipFile.bytes(name: String): ByteArray =
        getInputStream(requireNotNull(getEntry(name)) { "no entry $name" }).use { it.readBytes() }

    private fun ZipFile.text(name: String): String = String(bytes(name), Charsets.UTF_8)

    private fun ZipFile.names(): List<String> = entries().toList().map { it.name }

    private fun independentSha256(file: File): String =
        BigInteger(1, MessageDigest.getInstance("SHA-256").digest(file.readBytes())).toString(16).padStart(64, '0')

    private fun le16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)

    @Test
    fun aFullExportHoldsTheSevenFilesFlatInBundleOrder() {
        val result = SessionExporter().export(goldenCopy(), LocationPrecision.FULL, exports)

        assertEquals(File(exports, zipName), result.zip)
        assertEquals(LocationPrecision.FULL, result.precision)
        assertEquals(bundleNames, result.entries)
        assertEquals(result.zip.length(), result.bytes)
        ZipFile(result.zip).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(bundleNames, entries.map { it.name })
            for (entry in entries) {
                assertEquals(entry.name, ZipEntry.DEFLATED, entry.method)
                assertFalse(entry.name, entry.isDirectory)
                assertFalse(entry.name, entry.name.contains('/') || entry.name.contains('\\'))
            }
            for (file in SessionFile.CSV) assertArrayEquals(file.fileName, Golden.bytes(file.fileName), zip.bytes(file.fileName))
        }
    }

    @Test
    fun sessionJsonInTheZipIsTheSessionReencodedWithThePrecision() {
        val session = goldenCopy()
        val golden = SessionJson.decode(Golden.text("session.json"))
        for (precision in LocationPrecision.entries) {
            val result = SessionExporter().export(session, precision, exports)
            val meta = ZipFile(result.zip).use { SessionJson.decode(it.text("session.json")) }
            assertEquals(precision, meta.privacy.locationPrecision)
            assertEquals(golden.sessionId, meta.sessionId)
            assertEquals(golden.stoppedUtcMs, meta.stoppedUtcMs)
            assertEquals(golden.summary, meta.summary)
            assertEquals(golden.collection, meta.collection)
            assertEquals(golden.privacy.consentSha256, meta.privacy.consentSha256)
        }
        val full = SessionExporter().export(session, LocationPrecision.FULL, exports)
        ZipFile(full.zip).use { assertEquals(SessionJson.encode(golden), it.text("session.json")) }
    }

    @Test
    fun theSha256IsThatOfTheZipFile() {
        val result = SessionExporter().export(goldenCopy(), LocationPrecision.APPROX_110M, exports)
        assertTrue(Regex("[0-9a-f]{64}").matches(result.sha256))
        assertEquals(independentSha256(result.zip), result.sha256)
        assertEquals(Sha256.hex(result.zip), result.sha256)
    }

    @Test
    fun anApprox110mExportRewritesPositionsInTheCopyOnly() {
        val session = goldenCopy()
        val before = SessionFile.BUNDLE.associateWith { File(session, it.fileName).readBytes() }

        val result = SessionExporter().export(session, LocationPrecision.APPROX_110M, exports)

        assertEquals(bundleNames, result.entries)
        ZipFile(result.zip).use { zip ->
            for (file in listOf(SessionFile.KPI, SessionFile.CELLINFO, SessionFile.TRACK)) {
                val expected = PrecisionReducer.reduceCsvText(file, Golden.text(file.fileName), LocationPrecision.APPROX_110M)
                assertEquals(file.fileName, expected, zip.text(file.fileName))
                assertNotEquals(file.fileName, Golden.text(file.fileName), zip.text(file.fileName))
            }
            for (file in listOf(SessionFile.EVENTS, SessionFile.TRAFFIC, SessionFile.CELLS)) {
                assertArrayEquals(file.fileName, Golden.bytes(file.fileName), zip.bytes(file.fileName))
            }
        }
        for ((file, bytes) in before) {
            assertArrayEquals("local ${file.fileName} stays full precision", bytes, File(session, file.fileName).readBytes())
        }
    }

    @Test
    fun aNoneExportLeavesOutTrackCsvAndItsFilesKey() {
        val result = SessionExporter().export(goldenCopy(), LocationPrecision.NONE, exports)
        val expectedNames = SessionFile.BUNDLE.filter { it != SessionFile.TRACK }.map { it.fileName }

        assertEquals(expectedNames, result.entries)
        ZipFile(result.zip).use { zip ->
            assertEquals(expectedNames, zip.names())
            assertEquals(SessionFile.CSV - SessionFile.TRACK, SessionJson.decode(zip.text("session.json")).files)
            for (name in listOf("kpi.csv", "cellinfo.csv")) {
                val records = Golden.records(zip.text(name))
                val header = Golden.fields(records.first())
                for (record in records.drop(1)) {
                    val fields = Golden.fields(record)
                    assertEquals(name, "", fields[header.indexOf("lat")])
                    assertEquals(name, "", fields[header.indexOf("lon")])
                }
            }
        }
    }

    @Test
    fun anOpenSessionIsRefusedAndNothingIsWritten() {
        val session = goldenCopy()
        val golden = SessionJson.decode(Golden.text("session.json"))
        val open = golden.copy(stoppedUtcMs = null, summary = golden.summary.copy(stoppedBy = "recording"))
        File(session, "session.json").writeText(SessionJson.encode(open), Charsets.UTF_8)

        val refused = assertThrows(ExportException::class.java) {
            SessionExporter().export(session, LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.SESSION_OPEN, refused.reason)
        assertTrue(exports.listFiles().isNullOrEmpty())
    }

    @Test
    fun aMissingSessionJsonIsRefused() {
        val session = goldenCopy()
        assertTrue(File(session, "session.json").delete())
        val refused = assertThrows(ExportException::class.java) {
            SessionExporter().export(session, LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.MISSING_SESSION_JSON, refused.reason)

        val noDirectory = assertThrows(ExportException::class.java) {
            SessionExporter().export(File(temp.root, "no-such-session"), LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.MISSING_SESSION_JSON, noDirectory.reason)
        assertTrue(exports.listFiles().isNullOrEmpty())
    }

    @Test
    fun anUnreadableSessionJsonIsRefused() {
        val session = goldenCopy()
        File(session, "session.json").writeText("{ \"format\": \"fieldtap-session/1\", ", Charsets.UTF_8)
        val refused = assertThrows(ExportException::class.java) {
            SessionExporter().export(session, LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.MISSING_SESSION_JSON, refused.reason)
    }

    @Test
    fun tooManyUncompressedBytesFailsAndLeavesNoFile() {
        val failed = assertThrows(ExportException::class.java) {
            SessionExporter(maxUncompressedBytes = 10_000).export(goldenCopy(), LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.TOO_LARGE, failed.reason)
        assertEquals(emptyList<String>(), exports.list()!!.toList())
    }

    @Test
    fun aZipLargerThanTheLimitFailsAndLeavesNoFile() {
        val failed = assertThrows(ExportException::class.java) {
            SessionExporter(maxCompressedBytes = 2_000).export(goldenCopy(), LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.TOO_LARGE, failed.reason)
        assertEquals(emptyList<String>(), exports.list()!!.toList())
    }

    @Test
    fun bothLimitsAreInclusiveAndAFailedExportKeepsTheLastGoodZip() {
        val session = goldenCopy()
        val first = SessionExporter().export(session, LocationPrecision.FULL, exports)
        val uncompressed = ZipFile(first.zip).use { zip -> zip.names().sumOf { zip.bytes(it).size.toLong() } }

        val exact = SessionExporter(maxUncompressedBytes = uncompressed, maxCompressedBytes = first.bytes)
            .export(session, LocationPrecision.FULL, exports)
        assertEquals(first.sha256, exact.sha256)

        val overUncompressed = assertThrows(ExportException::class.java) {
            SessionExporter(maxUncompressedBytes = uncompressed - 1).export(session, LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.TOO_LARGE, overUncompressed.reason)
        val overCompressed = assertThrows(ExportException::class.java) {
            SessionExporter(maxCompressedBytes = first.bytes - 1).export(session, LocationPrecision.FULL, exports)
        }
        assertEquals(ExportException.Reason.TOO_LARGE, overCompressed.reason)

        assertEquals(listOf(zipName), exports.list()!!.toList())
        assertEquals(first.sha256, Sha256.hex(File(exports, zipName)))
    }

    @Test
    fun onlyTheSevenNamesEverEnterTheZip() {
        val session = goldenCopy()
        val strays = listOf("summary.json", "report.html", "index.html", "kpi.csv.tmp", "session.json.tmp", "session.heartbeat")
        for (name in strays) File(session, name).writeText("not part of a bundle", Charsets.UTF_8)
        assertTrue(File(session, "nested").mkdir())
        File(session, "nested/kpi.csv").writeText("not part of a bundle", Charsets.UTF_8)

        val result = SessionExporter().export(session, LocationPrecision.FULL, exports)

        ZipFile(result.zip).use { zip -> assertEquals(bundleNames, zip.names()) }
    }

    @Test
    fun anAbsentCsvIsSkippedAndLeftOutOfFiles() {
        val session = goldenCopy()
        assertTrue(File(session, "traffic.csv").delete())

        val result = SessionExporter().export(session, LocationPrecision.FULL, exports)

        val expectedNames = SessionFile.BUNDLE.filter { it != SessionFile.TRAFFIC }.map { it.fileName }
        assertEquals(expectedNames, result.entries)
        ZipFile(result.zip).use { zip ->
            assertEquals(expectedNames, zip.names())
            assertEquals(SessionFile.CSV - SessionFile.TRAFFIC, SessionJson.decode(zip.text("session.json")).files)
        }
    }

    @Test
    fun anExistingZipOfTheSameNameIsReplaced() {
        assertTrue(exports.mkdirs())
        File(exports, zipName).writeText("an old, broken zip", Charsets.UTF_8)

        val result = SessionExporter().export(goldenCopy(), LocationPrecision.FULL, exports)

        ZipFile(result.zip).use { zip -> assertEquals(7, zip.size()) }
        assertEquals(independentSha256(result.zip), result.sha256)
        assertEquals(listOf(zipName), exports.list()!!.toList())
    }

    @Test
    fun exportingTheSameSessionTwiceGivesTheSameBytes() {
        val session = goldenCopy()
        val first = SessionExporter().export(session, LocationPrecision.APPROX_110M, exports)
        val firstBytes = first.zip.readBytes()
        val second = SessionExporter().export(session, LocationPrecision.APPROX_110M, exports)
        assertEquals(first.sha256, second.sha256)
        assertArrayEquals(firstBytes, second.zip.readBytes())
    }

    @Test
    fun theZipHasNoZip64RecordsAndNoDirectoryEntries() {
        val result = SessionExporter().export(goldenCopy(), LocationPrecision.FULL, exports)
        val bytes = result.zip.readBytes()

        val end = bytes.size - 22
        assertEquals("end of central directory, no comment", 0x06054b50L, le32(bytes, end))
        assertEquals(0, le16(bytes, end + 20))
        assertEquals(7, le16(bytes, end + 10))
        assertTrue("no zip64 locator", end < 20 || le32(bytes, end - 20) != 0x07064b50L)

        var record = le32(bytes, end + 16).toInt()
        repeat(7) {
            assertEquals("central directory header", 0x02014b50L, le32(bytes, record))
            assertTrue("version needed is below zip64's 4.5", le16(bytes, record + 6) <= 20)
            val nameLength = le16(bytes, record + 28)
            val extraLength = le16(bytes, record + 30)
            val commentLength = le16(bytes, record + 32)
            val name = String(bytes, record + 46, nameLength, Charsets.UTF_8)
            assertFalse(name, name.endsWith("/"))
            var extra = record + 46 + nameLength
            val extraEnd = extra + extraLength
            while (extra + 4 <= extraEnd) {
                assertNotEquals("zip64 extra field in $name", 0x0001, le16(bytes, extra))
                extra += 4 + le16(bytes, extra + 2)
            }
            record = extraEnd + commentLength
        }
    }

    @Test
    fun theOutputDirectoryIsCreated() {
        val nested = File(exports, "a/b")
        val result = SessionExporter().export(goldenCopy(), LocationPrecision.FULL, nested)
        assertEquals(File(nested, zipName), result.zip)
        assertTrue(result.zip.isFile)
    }

    @Test
    fun exportingIntoTheSessionDirectoryIsAProgrammingError() {
        val session = goldenCopy()
        assertThrows(IllegalArgumentException::class.java) {
            SessionExporter().export(session, LocationPrecision.FULL, session)
        }
        assertEquals(bundleNames.sorted(), session.list()!!.sorted())
    }
}
