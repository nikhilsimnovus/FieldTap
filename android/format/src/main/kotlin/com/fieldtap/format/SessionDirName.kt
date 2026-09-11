package com.fieldtap.format

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
 * `Café – lobby` -> `Caf-lobby`; three spaces -> `session`; a 60-character name cut at 48.
 *
 * Owner: workstream `format`.
 */
object SessionDirName {
    val PATTERN: Regex = Regex(Schema.DIRECTORY_PATTERN)

    fun slugify(name: String): String = TODO("format")

    /** The directory name for a session started at [startedUtcMs] (Unix ms) called [name]. */
    fun of(startedUtcMs: Long, name: String): String = TODO("format")

    /** The start second encoded in a directory name, as Unix ms; null if the name does not match [PATTERN]. */
    fun startedSecondMs(dirName: String): Long? = TODO("format")
}
