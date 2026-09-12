package com.fieldtap.core.capability

/**
 * Parses `getenforce` output, which prints exactly one word. Whitespace- and case-insensitive; anything
 * unrecognised, or null, is [SelinuxMode.UNKNOWN].
 *
 * Owner: workstream `capability-core`.
 */
object SelinuxParser {
    fun parse(output: String?): SelinuxMode = when (output?.trim()?.lowercase()) {
        "enforcing" -> SelinuxMode.ENFORCING
        "permissive" -> SelinuxMode.PERMISSIVE
        "disabled" -> SelinuxMode.DISABLED
        else -> SelinuxMode.UNKNOWN
    }
}

/**
 * Parses `ls -l /dev/diag` into a [DiagDevice].
 *
 * - "Permission denied" / "Access denied" → [DiagDevice.PERMISSION_DENIED] (checked first: a denial hides
 *   whether the node exists, and is the honest answer).
 * - "No such file" / "cannot access" → [DiagDevice.ABSENT].
 * - A first token that is a character-device mode string (starts with `c`, e.g. `crw-rw----`) →
 *   [DiagDevice.PRESENT], even under an SELinux label or a trailing ACL `+`.
 * - A block device or any other/unrecognised line, or null → [DiagDevice.UNKNOWN].
 *
 * Owner: workstream `capability-core`.
 */
object DiagParser {
    private val CHAR_DEVICE_MODE = Regex("^c[-rwxsStT]{9}")

    fun device(lsOutput: String?): DiagDevice {
        val text = lsOutput?.trim().orEmpty()
        if (text.isEmpty()) return DiagDevice.UNKNOWN
        val lower = text.lowercase()
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return DiagDevice.PERMISSION_DENIED
        }
        if (lower.contains("no such file") || lower.contains("cannot access")) {
            return DiagDevice.ABSENT
        }
        val firstToken = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.substringBefore(' ')
            .orEmpty()
        return if (CHAR_DEVICE_MODE.containsMatchIn(firstToken)) DiagDevice.PRESENT else DiagDevice.UNKNOWN
    }
}

/**
 * Reads root from an `id` or `whoami` line: `uid=0` anywhere, or a trimmed value equal to `root`, proves
 * root. Null or anything else is not root.
 *
 * Owner: workstream `capability-core`.
 */
object SuOutputParser {
    fun isRoot(idOrWhoami: String?): Boolean {
        val text = idOrWhoami?.trim() ?: return false
        if (text.isEmpty()) return false
        return text.contains("uid=0") || text.equals("root", ignoreCase = true)
    }
}
