package com.fieldtap.platform.capability

import com.fieldtap.core.capability.RootDetector
import java.io.File

/**
 * Which su binaries exist on disk. Pure stat only (`File.exists`), never `exec` — checking existence must
 * not run anything. Besides the fixed [RootDetector.SU_PATHS], the `/debug_ram*` family is expanded by
 * listing `/` and matching the `debug_ram` prefix (some engineering firmware keeps a su there).
 *
 * The globbing is factored into the pure [debugRamSuCandidates], JVM-tested; only [scan] touches disk.
 *
 * Owner: workstream `capability-core`.
 */
class SuBinaryScanner {
    /** The su paths that exist, in [RootDetector.SU_PATHS] order followed by any `/debug_ram*` matches. */
    fun scan(): List<String> {
        val rootEntries = File("/").list()?.toList().orEmpty()
        val candidates = LinkedHashSet(RootDetector.SU_PATHS)
        candidates += debugRamSuCandidates(rootEntries)
        return candidates.filter { path ->
            try {
                File(path).exists()
            } catch (e: SecurityException) {
                false
            }
        }
    }

    companion object {
        private const val DEBUG_RAM_PREFIX = "debug_ram"

        /**
         * From the names of entries directly under `/`, the candidate su paths `/<name>/su` for every name
         * beginning with `debug_ram` (so `/debug_ram/su`, `/debug_ram2/su`, ...).
         */
        fun debugRamSuCandidates(rootEntryNames: List<String>): List<String> =
            rootEntryNames
                .filter { it.startsWith(DEBUG_RAM_PREFIX) }
                .map { "/$it/su" }
    }
}
