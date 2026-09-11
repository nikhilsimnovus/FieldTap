package com.fieldtap.core.nettest

import com.fieldtap.core.session.SessionEvents
import com.fieldtap.core.time.Clock
import com.fieldtap.format.EventRow
import com.fieldtap.format.TrafficRow
import com.fieldtap.format.TrafficTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Targets and limits for the opt-in tests. Tests are off by default and chosen per session.
 *
 * Defaults follow the lead decision of 2026-09-10 (android/ARCHITECTURE.md, section 0, item 6): ping
 * `8.8.8.8`, 5 echoes every 60 s; download [DEFAULT_DOWNLOAD_URL] every 5 minutes, capped at 10 MB, with a
 * 100 MB budget per session. [NetTestScheduler] tolerates any value a user can type: a blank target or
 * URL disables that test, and intervals shorter than [NetTestScheduler.MIN_INTERVAL_MS] are raised to it.
 *
 * Owner: workstream `service-and-tests`.
 */
data class TestSettings(
    val pingTarget: String = DEFAULT_PING_TARGET,
    val pingIntervalMs: Long = 60_000,
    val pingCount: Int = 5,
    /** How long each echo waits for its reply. */
    val pingTimeoutMs: Long = 2_000,
    /** HTTPS URL of the file to download. Null or blank disables the download test. */
    val downloadUrl: String? = DEFAULT_DOWNLOAD_URL,
    val downloadIntervalMs: Long = 300_000,
    /** The download stops counting at this many bytes (10 MB). */
    val downloadCapBytes: Long = 10_000_000,
    /** Downloads stop for the rest of the session once less than one cap of this budget is left. */
    val sessionBudgetBytes: Long = 100_000_000,
) {
    companion object {
        const val DEFAULT_PING_TARGET: String = "8.8.8.8"
        const val DEFAULT_DOWNLOAD_URL: String = "https://speed.cloudflare.com/__down?bytes=10000000"
    }
}

/** Why a test failed. [errorText] is written to traffic.csv `error` and the test_failed detail. */
enum class NetFailure(val errorText: String) {
    NO_CELLULAR_NETWORK("no cellular network"),
    TIMEOUT("timeout"),
    DNS("host not found"),
    HTTP_STATUS("http status not 200"),
    SOCKET_NOT_PERMITTED("icmp socket not permitted"),
    IO("network error"),

    /** The download URL does not parse or is not HTTPS. */
    INVALID_URL("invalid download url"),
}

sealed interface PingOutcome {
    val seconds: Double

    /** [rttsMs] has one entry per reply received; `sent - rttsMs.size` were lost. */
    data class Replies(val sent: Int, val rttsMs: List<Double>, override val seconds: Double) : PingOutcome

    data class Failed(val failure: NetFailure, val detail: String?, override val seconds: Double) : PingOutcome
}

sealed interface DownloadOutcome {
    val seconds: Double

    data class Completed(
        val bytes: Long,
        override val seconds: Double,
        val httpCode: Int,
        /** True when the byte cap or the time limit ended the transfer before the body did. */
        val capped: Boolean,
    ) : DownloadOutcome

    data class Failed(
        val failure: NetFailure,
        val detail: String?,
        override val seconds: Double,
        val httpCode: Int?,
        val bytes: Long?,
    ) : DownloadOutcome
}

/**
 * The network side of the tests, implemented in :app by
 * com.fieldtap.nettest.CellularTestTransport over a cellular `Network` (ICMP datagram socket bound
 * with `Network.bindSocket`, download through `Network.openConnection`). Never falls back to Wi-Fi:
 * with no cellular network it returns [NetFailure.NO_CELLULAR_NETWORK].
 *
 * Owner: workstream `service-and-tests`.
 */
interface NetTestTransport {
    suspend fun ping(target: String, count: Int, timeoutMs: Long): PingOutcome

    suspend fun download(url: String, capBytes: Long, timeoutMs: Long): DownloadOutcome
}

/** loss and RTTs for traffic.csv. */
data class PingSummary(
    val lossPct: Double,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
)

/**
 * `loss_pct = 100 * (sent - received) / sent`; min, mean and max of the RTTs, null with no reply.
 * Rounding to one decimal is the encoder's.
 *
 * RTTs that are not finite or are negative are not replies. More replies than echoes sent (a duplicate)
 * count as no loss rather than negative loss, and nothing sent counts as 100 % loss.
 *
 * Owner: workstream `service-and-tests`.
 */
object PingStats {
    fun summarize(sent: Int, rttsMs: List<Double>): PingSummary {
        val replies = rttsMs.filter { it.isFinite() && it >= 0.0 }
        val lossPct = if (sent <= 0) {
            100.0
        } else {
            val received = minOf(replies.size, sent)
            100.0 * (sent - received) / sent
        }
        if (replies.isEmpty()) return PingSummary(lossPct, null, null, null)
        return PingSummary(
            lossPct = lossPct,
            rttMinMs = replies.min(),
            rttAvgMs = replies.sum() / replies.size,
            rttMaxMs = replies.max(),
        )
    }
}

/** One test result ready to write: the traffic.csv row and, when it failed, its test_failed event. */
data class TrafficRecord(val row: TrafficRow, val failure: EventRow?)

/**
 * Turns outcomes into [TrafficRecord]s.
 *
 * - Ping replies: `ok` 1 when at least one reply came back, loss and RTTs filled; zero replies: `ok` 0,
 *   `error` `no reply`, loss 100.0. A ping that could not run at all (no network, no socket) measured no
 *   loss, so its `loss_pct` is blank: the report averages loss over every row that has one.
 * - Download completed with HTTP 200 and at least one byte: `ok` 1, `mbps` = bytes * 8 / seconds /
 *   1 000 000, bytes, http_code. Any other status is an [NetFailure.HTTP_STATUS] failure.
 * - Any failure: `ok` 0, `error` from [NetFailure.errorText] (plus `: detail`), plus
 *   `SessionEvents.testFailed` at the test's start.
 * - `seconds` is never negative or blank; `http_code` outside 100..599 is left blank.
 *
 * Owner: workstream `service-and-tests`.
 */
object TrafficRecords {
    /** `error` of a ping that sent echoes and got no reply. */
    const val NO_REPLY: String = "no reply"

    /** Longest `detail` kept in `error`; the error is a short reason, not a log. */
    const val MAX_DETAIL_LENGTH: Int = 60

    fun ping(startedWallMs: Long, target: String, outcome: PingOutcome): TrafficRecord = when (outcome) {
        is PingOutcome.Replies -> {
            val summary = PingStats.summarize(outcome.sent, outcome.rttsMs)
            if (summary.rttAvgMs != null) {
                val row = TrafficRow(
                    timeUtcMs = startedWallMs,
                    test = TrafficTest.PING,
                    target = target,
                    ok = true,
                    seconds = seconds(outcome.seconds),
                    lossPct = summary.lossPct,
                    rttMinMs = summary.rttMinMs,
                    rttAvgMs = summary.rttAvgMs,
                    rttMaxMs = summary.rttMaxMs,
                )
                TrafficRecord(row, null)
            } else {
                failed(startedWallMs, TrafficTest.PING, target, outcome.seconds, NO_REPLY, lossPct = 100.0)
            }
        }

        is PingOutcome.Failed ->
            failed(startedWallMs, TrafficTest.PING, target, outcome.seconds, errorText(outcome.failure, outcome.detail))
    }

    fun download(startedWallMs: Long, url: String, outcome: DownloadOutcome): TrafficRecord = when (outcome) {
        is DownloadOutcome.Completed -> when {
            outcome.httpCode != HTTP_OK -> failed(
                startedWallMs, TrafficTest.DOWNLOAD, url, outcome.seconds,
                errorText(NetFailure.HTTP_STATUS, outcome.httpCode.toString()),
                bytes = outcome.bytes.coerceAtLeast(0), httpCode = httpCode(outcome.httpCode),
            )

            outcome.bytes <= 0 -> failed(
                startedWallMs, TrafficTest.DOWNLOAD, url, outcome.seconds,
                errorText(NetFailure.IO, EMPTY_BODY), bytes = 0, httpCode = httpCode(outcome.httpCode),
            )

            else -> {
                val seconds = seconds(outcome.seconds)
                val row = TrafficRow(
                    timeUtcMs = startedWallMs,
                    test = TrafficTest.DOWNLOAD,
                    target = url,
                    ok = true,
                    seconds = seconds,
                    mbps = mbps(outcome.bytes, seconds),
                    bytes = outcome.bytes,
                    httpCode = HTTP_OK,
                )
                TrafficRecord(row, null)
            }
        }

        is DownloadOutcome.Failed -> failed(
            startedWallMs, TrafficTest.DOWNLOAD, url, outcome.seconds,
            errorText(outcome.failure, outcome.detail),
            bytes = outcome.bytes?.coerceAtLeast(0), httpCode = outcome.httpCode?.let { httpCode(it) },
        )
    }

    /** `bytes * 8 / seconds / 1 000 000`, with at least one millisecond of time so it is always finite. */
    fun mbps(bytes: Long, seconds: Double): Double = bytes * 8.0 / maxOf(seconds, MIN_SECONDS) / 1_000_000.0

    /** [NetFailure.errorText], plus `: detail` when there is one, on one line and at most [MAX_DETAIL_LENGTH] detail characters. */
    fun errorText(failure: NetFailure, detail: String?): String {
        val clean = detail
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_DETAIL_LENGTH)
            ?.trim()
        return if (clean.isNullOrEmpty()) failure.errorText else "${failure.errorText}: $clean"
    }

    private fun failed(
        startedWallMs: Long,
        test: TrafficTest,
        target: String,
        seconds: Double,
        error: String,
        lossPct: Double? = null,
        bytes: Long? = null,
        httpCode: Int? = null,
    ): TrafficRecord {
        val row = TrafficRow(
            timeUtcMs = startedWallMs,
            test = test,
            target = target,
            ok = false,
            seconds = seconds(seconds),
            lossPct = lossPct,
            bytes = bytes,
            httpCode = httpCode,
            error = error,
        )
        return TrafficRecord(row, SessionEvents.testFailed(startedWallMs, test, error))
    }

    private fun seconds(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0

    private fun httpCode(code: Int): Int? = code.takeIf { it in HTTP_CODE_RANGE }

    private const val HTTP_OK = 200
    private const val EMPTY_BODY = "empty body"
    private const val MIN_SECONDS = 0.001
    private val HTTP_CODE_RANGE = 100..599
}

/**
 * When tests run.
 *
 * - The first ping is due 10 s after [startElapsedMs], then every `pingIntervalMs` after the previous
 *   start; the first download 30 s after start, then every `downloadIntervalMs`.
 * - A ping is never due with a blank target or a count below 1.
 * - A download is never due while the remaining budget is below `downloadCapBytes`, with a cap below 1,
 *   or without a URL.
 * - Tests never overlap: [nextDue] returns at most one test, ping first.
 * - Intervals below [MIN_INTERVAL_MS] are raised to it, so a mistyped setting cannot spin.
 *
 * Not thread-safe: one runner owns it.
 *
 * Owner: workstream `service-and-tests`.
 */
class NetTestScheduler(private val settings: TestSettings, private val startElapsedMs: Long) {
    private val pingEnabled: Boolean = settings.pingTarget.isNotBlank() && settings.pingCount > 0
    private val pingIntervalMs: Long = settings.pingIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
    private val downloadIntervalMs: Long = settings.downloadIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
    private var nextPingAtMs: Long = startElapsedMs + FIRST_PING_DELAY_MS
    private var nextDownloadAtMs: Long = startElapsedMs + FIRST_DOWNLOAD_DELAY_MS
    private var usedBytes: Long = 0

    /** Bytes downloaded so far in this session. */
    val budgetUsedBytes: Long get() = usedBytes

    /** False once no test can ever be due again. */
    val hasWork: Boolean get() = pingEnabled || downloadAllowed()

    fun nextDue(nowElapsedMs: Long): TrafficTest? = when {
        pingEnabled && nowElapsedMs >= nextPingAtMs -> TrafficTest.PING
        downloadAllowed() && nowElapsedMs >= nextDownloadAtMs -> TrafficTest.DOWNLOAD
        else -> null
    }

    /** Records that [test] started (or was skipped) at [nowElapsedMs]; the next one is one interval later. */
    fun markStarted(test: TrafficTest, nowElapsedMs: Long) {
        when (test) {
            TrafficTest.PING -> nextPingAtMs = nowElapsedMs + pingIntervalMs
            TrafficTest.DOWNLOAD -> nextDownloadAtMs = nowElapsedMs + downloadIntervalMs
        }
    }

    /** Adds downloaded bytes to the session budget; negative values are ignored. */
    fun addBytes(bytes: Long) {
        if (bytes <= 0) return
        usedBytes = if (Long.MAX_VALUE - usedBytes < bytes) Long.MAX_VALUE else usedBytes + bytes
    }

    /** Milliseconds until the next test is due (0 when one is due now), or `Long.MAX_VALUE` when none will be. */
    fun millisUntilNext(nowElapsedMs: Long): Long {
        var next = Long.MAX_VALUE
        if (pingEnabled) next = minOf(next, nextPingAtMs)
        if (downloadAllowed()) next = minOf(next, nextDownloadAtMs)
        if (next == Long.MAX_VALUE) return Long.MAX_VALUE
        return (next - nowElapsedMs).coerceAtLeast(0)
    }

    private fun downloadAllowed(): Boolean =
        !settings.downloadUrl.isNullOrBlank() &&
            settings.downloadCapBytes > 0 &&
            settings.sessionBudgetBytes - usedBytes >= settings.downloadCapBytes

    companion object {
        const val FIRST_PING_DELAY_MS: Long = 10_000
        const val FIRST_DOWNLOAD_DELAY_MS: Long = 30_000
        const val MIN_INTERVAL_MS: Long = 1_000
    }
}

/**
 * Runs the tests for one session until cancelled. Skips a due test while [run]'s `isPaused` returns
 * true (privacy zone): the skipped test is next due one interval later. Emits every result through
 * `emit`, which the service forwards to the recorder.
 *
 * - The time of a row is the wall clock when the test started; its `seconds` come from the transport.
 * - A transport that throws (anything but cancellation) becomes a failed row with [NetFailure.IO], so one
 *   broken test never ends the others.
 * - Each download, failed or not, spends its bytes from the session budget.
 * - Between tests it sleeps at most [MAX_SLEEP_MS] at a time, so a clock that includes deep sleep keeps
 *   the schedule after the phone wakes.
 *
 * Owner: workstream `service-and-tests`.
 */
class NetTestRunner(
    private val settings: TestSettings,
    private val transport: NetTestTransport,
    private val clock: Clock,
) {
    suspend fun run(isPaused: () -> Boolean, emit: suspend (TrafficRecord) -> Unit) {
        val scheduler = NetTestScheduler(settings, clock.elapsedRealtimeMillis())
        while (true) {
            val now = clock.elapsedRealtimeMillis()
            val due = scheduler.nextDue(now)
            if (due == null) {
                if (!scheduler.hasWork) awaitCancellation()
                delay(scheduler.millisUntilNext(now).coerceIn(1, MAX_SLEEP_MS))
                continue
            }
            scheduler.markStarted(due, now)
            if (isPaused()) continue
            val record = when (due) {
                TrafficTest.PING -> ping()
                TrafficTest.DOWNLOAD -> download(scheduler)
            }
            emit(record)
        }
    }

    private suspend fun ping(): TrafficRecord {
        val target = settings.pingTarget.trim()
        val startedWallMs = clock.wallMillis()
        val startedElapsedMs = clock.elapsedRealtimeMillis()
        val outcome = try {
            transport.ping(target, settings.pingCount, settings.pingTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PingOutcome.Failed(NetFailure.IO, e.javaClass.simpleName, secondsSince(startedElapsedMs))
        }
        return TrafficRecords.ping(startedWallMs, target, outcome)
    }

    private suspend fun download(scheduler: NetTestScheduler): TrafficRecord {
        val url = settings.downloadUrl.orEmpty().trim()
        val startedWallMs = clock.wallMillis()
        val startedElapsedMs = clock.elapsedRealtimeMillis()
        val outcome = try {
            transport.download(url, settings.downloadCapBytes, DOWNLOAD_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DownloadOutcome.Failed(NetFailure.IO, e.javaClass.simpleName, secondsSince(startedElapsedMs), null, null)
        }
        val bytes = when (outcome) {
            is DownloadOutcome.Completed -> outcome.bytes
            is DownloadOutcome.Failed -> outcome.bytes ?: 0
        }
        scheduler.addBytes(bytes)
        return TrafficRecords.download(startedWallMs, url, outcome)
    }

    private fun secondsSince(startedElapsedMs: Long): Double =
        (clock.elapsedRealtimeMillis() - startedElapsedMs).coerceAtLeast(0) / 1000.0

    companion object {
        /** The longest one download may take; a slower transfer is measured over this time. */
        const val DOWNLOAD_TIMEOUT_MS: Long = 60_000

        /** The longest single sleep between schedule checks. */
        const val MAX_SLEEP_MS: Long = 1_000
    }
}

/**
 * ICMP echo packets for an `IPPROTO_ICMP` datagram socket: type 8, code 0. On Linux datagram ICMP
 * sockets the kernel sets the identifier and checksum, so [request] leaves the identifier zero; it still
 * fills in a valid checksum, which the kernel overwrites. [isReplyTo] matches on type 0 and sequence
 * only: the kernel delivers only replies to this socket's identifier, without the IP header.
 *
 * Owner: workstream `service-and-tests`.
 */
object IcmpEcho {
    const val TYPE_ECHO_REPLY: Int = 0
    const val TYPE_ECHO_REQUEST: Int = 8
    const val HEADER_BYTES: Int = 8

    /** The payload size `ping` uses by default, for a 64-byte ICMP message. */
    const val DEFAULT_PAYLOAD_BYTES: Int = 56

    fun request(sequence: Int, payload: ByteArray): ByteArray {
        require(sequence in 0..0xFFFF) { "ICMP sequence must fit in 16 bits" }
        val packet = ByteArray(HEADER_BYTES + payload.size)
        packet[0] = TYPE_ECHO_REQUEST.toByte()
        packet[1] = 0
        packet[6] = (sequence ushr 8).toByte()
        packet[7] = sequence.toByte()
        payload.copyInto(packet, HEADER_BYTES)
        val sum = checksum(packet, packet.size)
        packet[2] = (sum ushr 8).toByte()
        packet[3] = sum.toByte()
        return packet
    }

    fun isReplyTo(packet: ByteArray, length: Int, sequence: Int): Boolean {
        if (length < HEADER_BYTES || length > packet.size) return false
        val type = packet[0].toInt() and 0xFF
        val replySequence = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        return type == TYPE_ECHO_REPLY && replySequence == (sequence and 0xFFFF)
    }

    /** The RFC 1071 Internet checksum of the first [length] bytes. A packet carrying its checksum sums to 0. */
    fun checksum(bytes: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (bytes[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    /** A payload of [size] bytes counting up from 0; it carries nothing about the phone. */
    fun payload(size: Int = DEFAULT_PAYLOAD_BYTES): ByteArray = ByteArray(size) { it.toByte() }
}
