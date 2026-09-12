package com.fieldtap.platform.capability

import com.fieldtap.core.capability.RootDetector
import java.io.File

/**
 * Which system-ish paths are writable to this app. Pure stat only (`File.canWrite`), never `exec` and
 * never a write — a writable `/system` (or the like) is a tell of a modified system, not something to
 * touch. On a normal phone none are writable.
 *
 * The filtering is factored into the pure [scan], JVM-tested with a fake predicate; [scanWritable]
 * supplies the real `canWrite` probe.
 *
 * Owner: workstream `capability-core`.
 */
class WritablePathProbe {
    /** The subset of [RootDetector.WRITABLE_PATHS] this app can write to, in that list's order. */
    fun scanWritable(): List<String> = scan(RootDetector.WRITABLE_PATHS) { path ->
        try {
            File(path).canWrite()
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        /** The paths in [candidates] for which [writable] is true, in [candidates] order. */
        fun scan(candidates: List<String>, writable: (String) -> Boolean): List<String> =
            candidates.filter(writable)
    }
}
