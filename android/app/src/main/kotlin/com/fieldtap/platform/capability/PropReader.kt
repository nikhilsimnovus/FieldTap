package com.fieldtap.platform.capability

import android.os.Build
import android.util.Log
import com.fieldtap.core.capability.RootDetector
import java.io.IOException

/**
 * Reads system properties for the passive root assessment. Runs `getprop` once (no su — reading
 * properties needs no root) and parses its `[key]: [value]` lines, keeping only [RootDetector.PropKeys].
 * `Build.TAGS` is read directly. An [IOException] from `exec` yields an empty prop map.
 *
 * The parse is factored into the pure [parse], JVM-tested; only [readProps] touches the platform.
 *
 * Owner: workstream `capability-core`.
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
