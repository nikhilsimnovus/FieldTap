package com.fieldtap.core.nettest

import com.fieldtap.core.time.Clock
import com.fieldtap.format.EventRow
import com.fieldtap.format.TrafficRow
import com.fieldtap.format.TrafficTest

/**
 * Targets and limits for the opt-in tests. Tests are off by default and chosen per session.
 *
 * Owner: workstream `service-and-tests`.
 */
data class TestSettings(
    val pingTarget: String = "8.8.8.8",
    val pingIntervalMs: Long = 60_000,
    val pingCount: Int = 5,
    val pingTimeoutMs: Long = 2_000,
    /** HTTPS URL of a file on our own host. Null disables the download test. */
    val downloadUrl: String? = null,
    val downloadIntervalMs: Long = 300_000,
    /** The download stops counting at this many bytes (10 MB). */
    val downloadCapBytes: Long = 10_000_000,
    /** Downloads stop for the rest of the session once this much was used. */
    val sessionBudgetBytes: Long = 100_000_000,
)

/** Why a test failed. [errorText] is written to traffic.csv `error` and the test_failed detail. */
enum class NetFailure(val errorText: String) {
    NO_CELLULAR_NETWORK("no cellular network"),
    TIMEOUT("timeout"),
    DNS("host not found"),
    HTTP_STATUS("http status not 200"),
    SOCKET_NOT_PERMITTED("icmp socket not permitted"),
    IO("network error"),
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
        /** True when the cap stopped it. */
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
 * Owner: workstream `service-and-tests`.
 */
object PingStats {
    fun summarize(sent: Int, rttsMs: List<Double>): PingSummary = TODO("service-and-tests")
}

/** One test result ready to write: the traffic.csv row and, when it failed, its test_failed event. */
data class TrafficRecord(val row: TrafficRow, val failure: EventRow?)

/**
 * Turns outcomes into [TrafficRecord]s.
 *
 * - Ping replies: `ok` 1 when at least one reply came back, loss and RTTs filled; zero replies: `ok` 0,
 *   `error` `no reply`, loss 100.0.
 * - Download completed with HTTP 200: `ok` 1, `mbps` = bytes * 8 / seconds / 1 000 000, bytes, http_code.
 * - Any failure: `ok` 0, `error` from [NetFailure.errorText] (plus detail), plus
 *   `SessionEvents.testFailed`.
 *
 * Owner: workstream `service-and-tests`.
 */
object TrafficRecords {
    fun ping(startedWallMs: Long, target: String, outcome: PingOutcome): TrafficRecord = TODO("service-and-tests")

    fun download(startedWallMs: Long, url: String, outcome: DownloadOutcome): TrafficRecord = TODO("service-and-tests")
}

/**
 * When tests run.
 *
 * - The first ping is due 10 s after [startElapsedMs], then every `pingIntervalMs` after the previous
 *   start; the first download 30 s after start, then every `downloadIntervalMs`.
 * - A download is never due while the remaining budget is below `downloadCapBytes`, or without a URL.
 * - Tests never overlap: [nextDue] returns at most one test, ping first.
 *
 * Owner: workstream `service-and-tests`.
 */
class NetTestScheduler(private val settings: TestSettings, private val startElapsedMs: Long) {
    val budgetUsedBytes: Long get() = TODO("service-and-tests")

    fun nextDue(nowElapsedMs: Long): TrafficTest? = TODO("service-and-tests")

    fun markStarted(test: TrafficTest, nowElapsedMs: Long): Unit = TODO("service-and-tests")

    fun addBytes(bytes: Long): Unit = TODO("service-and-tests")

    fun millisUntilNext(nowElapsedMs: Long): Long = TODO("service-and-tests")
}

/**
 * Runs the tests for one session until cancelled. Skips a due test while [run]'s `isPaused` returns
 * true (privacy zone). Emits every result through `emit`, which the service forwards to the recorder.
 *
 * Owner: workstream `service-and-tests`.
 */
class NetTestRunner(
    private val settings: TestSettings,
    private val transport: NetTestTransport,
    private val clock: Clock,
) {
    suspend fun run(isPaused: () -> Boolean, emit: suspend (TrafficRecord) -> Unit): Unit = TODO("service-and-tests")
}

/**
 * ICMP echo packets for an `IPPROTO_ICMP` datagram socket: type 8, code 0. On Linux datagram ICMP
 * sockets the kernel sets the identifier and checksum, so [request] may leave both zero, and
 * [isReplyTo] matches on type 0 and sequence only.
 *
 * Owner: workstream `service-and-tests`.
 */
object IcmpEcho {
    fun request(sequence: Int, payload: ByteArray): ByteArray = TODO("service-and-tests")

    fun isReplyTo(packet: ByteArray, length: Int, sequence: Int): Boolean = TODO("service-and-tests")
}
