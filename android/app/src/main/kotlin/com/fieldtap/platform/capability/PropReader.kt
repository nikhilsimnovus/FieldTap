package com.fieldtap.platform.capability

import android.os.Build
import android.util.Log
import com.fieldtap.core.capability.RootDetector
import java.io.IOException

/**
 * Reads system properties for the passive root assessment and the deep modem readout. Runs `getprop` once
 * (no su — reading properties needs no root) and parses its `[key]: [value]` lines, keeping **only**
 * [RootDetector.PropKeys] — an allow-list of non-identifier keys (the root-signal props plus the modem/RIL
 * surface props `gsm.version.ril-impl`, `ro.baseband`, `ro.hardware`). Any key outside the allow-list — in
 * particular any identifier (IMEI/IMSI/ICCID/phone number/serial/`ANDROID_ID`/ad id) — is dropped, so no
 * identifier can enter the map even if `getprop` reports one (deep-root-spec §0.5, §7). `Build.TAGS` is read
 * directly. An [IOException] from `exec` yields an empty prop map.
 *
 * The parse is factored into the pure [parse], JVM-tested; only [readProps] touches the platform.
 *
 * Owner: workstream `deep-root-core`.
 */
class PropReader {
    /** `getprop` output parsed to only [RootDetector.PropKeys], plus `Build.TAGS`. */
    fun read(): PropsAndTags = PropsAndTags(readProps(), Build.TAGS.orEmpty())

    private fun readProps(): Map<String, String> = try {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        parse(text, RootDetector.PropKeys)
    } catch (e: IOException) {
        Log.w(TAG, "getprop could not be read; treating properties as unknown", e)
        emptyMap()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "getprop was interrupted; treating properties as unknown", e)
        emptyMap()
    }

    companion object {
        private const val TAG = "FieldTapCapability"
        private val LINE = Regex("""^\[([^\]]+)]:\s*\[(.*)]$""")

        /**
         * Parses `getprop`'s `[key]: [value]` lines, keeping only [keys]. A key that appears more than
         * once keeps its first value; a key not in [keys] is dropped.
         */
        fun parse(raw: String, keys: List<String>): Map<String, String> {
            val wanted = keys.toSet()
            val out = LinkedHashMap<String, String>()
            for (line in raw.lineSequence()) {
                val match = LINE.matchEntire(line.trim()) ?: continue
                val key = match.groupValues[1]
                if (key in wanted && key !in out) {
                    out[key] = match.groupValues[2]
                }
            }
            return out
        }
    }
}

/** The passive property inputs: the wanted `getprop` values and `Build.TAGS`. */
data class PropsAndTags(val props: Map<String, String>, val buildTags: String)
