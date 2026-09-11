package com.fieldtap.nettest

import android.content.Context
import android.net.Network
import com.fieldtap.core.nettest.DownloadOutcome
import com.fieldtap.core.nettest.NetTestTransport
import com.fieldtap.core.nettest.PingOutcome
import com.fieldtap.core.time.Clock

/**
 * The cellular network, requested explicitly: `ConnectivityManager.requestNetwork` with
 * `TRANSPORT_CELLULAR` and `NET_CAPABILITY_INTERNET`, released after each test. Null after
 * [acquire]'s timeout: the caller writes a failed row saying "no cellular network". Never falls back
 * to the default network, which may be Wi-Fi.
 *
 * Owner: workstream `service-and-tests`.
 */
class CellularNetworks(private val context: Context) {
    suspend fun acquire(timeoutMs: Long): Network? = TODO("service-and-tests")
}

/**
 * [NetTestTransport] on cellular.
 *
 * - [ping]: `Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` (IPv4 target) bound with
 *   `Network.bindSocket(FileDescriptor)`, [com.fieldtap.core.nettest.IcmpEcho] packets, 1 s apart, each
 *   waiting up to `timeoutMs`; the target resolved with `Network.getAllByName`. An `EACCES` from socket
 *   creation is `SOCKET_NOT_PERMITTED`. Never runs `/system/bin/ping` (a child process cannot be bound).
 * - [download]: `Network.openConnection(URL)` (HTTPS only), counts body bytes until `capBytes`, then
 *   disconnects; `seconds` from the clock's elapsed time.
 *
 * Owner: workstream `service-and-tests`.
 */
class CellularTestTransport(
    private val networks: CellularNetworks,
    private val clock: Clock,
) : NetTestTransport {
    override suspend fun ping(target: String, count: Int, timeoutMs: Long): PingOutcome = TODO("service-and-tests")

    override suspend fun download(url: String, capBytes: Long, timeoutMs: Long): DownloadOutcome =
        TODO("service-and-tests")
}
