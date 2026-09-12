package com.fieldtap.platform.capability

import java.io.File

/**
 * Passive `tcpdump` presence: which of the known install paths exist on disk (`File.exists`, no `exec`, no
 * su). **Availability only — the app never captures.** Used to union with the su-side `which`/known-path
 * hits when the deep run builds [com.fieldtap.core.capability.CaptureTooling] (deep-root-spec §4); the app
 * process cannot usually stat `/data/local/tmp` without root, so a false here is not proof of absence — the
 * su-side hits are authoritative.
 *
 * The filtering is factored into the pure [scan], JVM-tested with a fake predicate; [knownPathHits] supplies
 * the real `File.exists` probe.
 *
 * Owner: workstream `deep-root-core`.
 */
class TcpdumpScanner {
    /** The subset of [KNOWN_PATHS] that exist for this app, in that list's order. */
    fun knownPathHits(): List<String> = scan(KNOWN_PATHS) { path ->
        try {
            File(path).exists()
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        /** The common `tcpdump` install paths, matching the su-side `DEEP_SCRIPT` loop. */
        val KNOWN_PATHS: List<String> = listOf(
            "/system/bin/tcpdump",
            "/system/xbin/tcpdump",
            "/vendor/bin/tcpdump",
            "/data/local/tmp/tcpdump",
        )

        /** The paths in [candidates] for which [present] is true, in [candidates] order. */
        fun scan(candidates: List<String>, present: (String) -> Boolean): List<String> =
            candidates.filter(present)
    }
}
