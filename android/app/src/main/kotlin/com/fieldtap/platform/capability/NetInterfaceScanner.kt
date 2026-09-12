package com.fieldtap.platform.capability

import android.util.Log
import java.io.File

/**
 * Lists the network-interface names under `/sys/class/net`, which is world-readable, so **no su is needed**.
 * It reads only entry **names** (e.g. `rmnet_data0`, `wlan0`, `lo`) — never an address, MAC or route — and
 * returns them newline-joined for [com.fieldtap.core.capability.ModemInterfaceParser] to filter. Used as the
 * passive fallback for modem interfaces and pcap-capable-interface presence when the su-side list is empty
 * (deep-root-spec §4).
 *
 * Owner: workstream `deep-root-core`.
 */
class NetInterfaceScanner {
    /** The `/sys/class/net` entry names joined by `\n`, or "" when the directory cannot be listed. */
    fun list(): String = try {
        File(SYS_CLASS_NET).list()?.joinToString("\n").orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not list $SYS_CLASS_NET; treating interfaces as unknown", e)
        ""
    }

    private companion object {
        const val TAG = "FieldTapCapability"
        const val SYS_CLASS_NET = "/sys/class/net"
    }
}
