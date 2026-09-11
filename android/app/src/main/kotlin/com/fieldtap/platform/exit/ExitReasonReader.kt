package com.fieldtap.platform.exit

import android.content.Context
import com.fieldtap.core.session.ExitRecord

/**
 * `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, max)` as [ExitRecord]s, newest first.
 * Empty on any exception. Descriptions are kept as Android gives them and never contain session data.
 *
 * Owner: workstream `platform-adapters`.
 */
class ExitReasonReader(private val context: Context) {
    fun recent(max: Int = 16): List<ExitRecord> = TODO("platform-adapters")
}
