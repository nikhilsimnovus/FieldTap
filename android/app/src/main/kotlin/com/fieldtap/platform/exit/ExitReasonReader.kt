package com.fieldtap.platform.exit

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.fieldtap.core.session.ExitRecord

/**
 * `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, max)` as [ExitRecord]s, newest first.
 * Empty on any exception, and for a [max] below 1 (Android would read 0 as "all"). Descriptions are kept as
 * Android gives them and never contain session data.
 *
 * Owner: workstream `platform-adapters`.
 */
class ExitReasonReader(private val context: Context) {
    fun recent(max: Int = 16): List<ExitRecord> {
        if (max <= 0) return emptyList()
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
                ExitRecord(
                    pid = info.pid,
                    timestampWallMs = info.timestamp,
                    reason = info.reason,
                    description = info.description,
                )
            }
            ExitRecords.newestFirst(records)
        } catch (e: Exception) {
            Log.w(TAG, "Android's process exit reasons could not be read", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "FieldTapExit"
    }
}

/**
 * Ordering of exit records, pure.
 *
 * Owner: workstream `platform-adapters`.
 */
internal object ExitRecords {
    /** Newest timestamp first; records with equal timestamps keep Android's order. */
    fun newestFirst(records: List<ExitRecord>): List<ExitRecord> = records.sortedByDescending { it.timestampWallMs }
}
