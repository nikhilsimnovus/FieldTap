package com.fieldtap.format

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Session directory names: `<yyyyMMdd-HHmmss of started_utc, UTC, truncated to the second>_<slug>`.
 *
 * [slugify] is `fieldtap.session.slugify` exactly:
 * `re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip()).strip("-")[:48] or "session"`.
 * 1. trim white space from both ends; 2. replace every run of characters outside
 * `A-Z a-z 0-9 . _ -` with one `-`; 3. strip `-` from both ends; 4. keep the first 48 characters
 * (this can leave a trailing `-`); 5. if nothing is left, `session`.
 * Kotlin's `trim()` and Python's `strip()` disagree on a few white-space code points, but any code
 * point one of them keeps becomes `-` in step 2 and is stripped in step 3, so the results are equal.
 *
 * Examples that tests must pin: `Mall walk (north path)` -> `Mall-walk-north-path`;
 * `Caf` followed by an e with acute accent, a space, an en dash and ` lobby` -> `Caf-lobby`;
 * three spaces -> `session`; a 60-character name cut at 48.
 *
 * Owner: workstream `format`.
 */
object SessionDirName {
    /** `directory.pattern` in columns.json: the whole name, matched with [Regex.matches]. */
    val PATTERN: Regex = Regex(Schema.DIRECTORY_PATTERN)

    /** `directory.slug_replace_pattern` in columns.json; guarded by SchemaDriftTest. */
    internal const val SLUG_REPLACE_PATTERN: String = "[^A-Za-z0-9._-]+"

    /** `directory.time_format_kotlin` in columns.json; guarded by SchemaDriftTest. */
    internal const val TIME_FORMAT: String = "yyyyMMdd-HHmmss"

    private const val SLUG_REPLACEMENT: String = "-"
    private const val SLUG_STRIP: Char = '-'
    private const val TIME_LENGTH: Int = 15

    private val slugReplace: Regex = Regex(SLUG_REPLACE_PATTERN)

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(TIME_FORMAT, Locale.ROOT).withZone(ZoneOffset.UTC)

    // uuuu with STRICT resolution: rejects 20260230 instead of adjusting it to 20260228.
    private val timeParser: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    /** The directory slug of a session name; see the steps above. Never empty. */
    fun slugify(name: String): String =
        slugReplace.replace(name.trim(), SLUG_REPLACEMENT)
            .trim(SLUG_STRIP)
            .take(Schema.SLUG_MAX_LENGTH)
            .ifEmpty { Schema.SLUG_EMPTY }

    /** The directory name for a session started at [startedUtcMs] (Unix ms) called [name]. */
    fun of(startedUtcMs: Long, name: String): String =
        timeFormatter.format(Instant.ofEpochMilli(startedUtcMs)) + "_" + slugify(name)

    /** The start second encoded in a directory name, as Unix ms; null if the name does not match [PATTERN]. */
    fun startedSecondMs(dirName: String): Long? {
        if (!PATTERN.matches(dirName)) return null
        return try {
            LocalDateTime.parse(dirName.substring(0, TIME_LENGTH), timeParser).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }
}
