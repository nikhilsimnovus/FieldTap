package com.fieldtap.core.settings

import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.core.privacy.PrivacyZone

/**
 * Everything the app remembers between launches. Stored as one JSON string in DataStore by
 * com.fieldtap.platform.settings.DataStoreSettingsRepository.
 *
 * Owner: workstream `location-privacy-core`.
 */
data class AppSettings(
    /** Random UUID made once per installation; `device.key` is `app:` plus it. Never ANDROID_ID. */
    val installId: String,
    /** Null until the disclosure is accepted. */
    val consent: ConsentRecord? = null,
    val tests: TestSettings = TestSettings(),
    val zones: List<PrivacyZone> = emptyList(),
    /** Whether the Live screen's walk-mode toggle starts on. */
    val walkModeDefault: Boolean = false,
    /** Whether the Start dialog's "run ping and download tests" starts ticked. Off by default. */
    val testsDefaultOn: Boolean = false,
    /** When the readiness check last ran; see `ReadinessPolicy.requiredBeforeSession`. */
    val readinessLastRunUtcMs: Long? = null,
    /** `started_utc` of the most recent session started on this install. */
    val lastSessionStartedUtcMs: Long? = null,
)

/**
 * JSON for [AppSettings]. Unknown keys are ignored and missing keys take defaults, so settings
 * survive app updates in both directions. A document that is not JSON, or has no usable
 * `install_id`, decodes to defaults with [newInstallId]'s value (consent is then absent, so the
 * disclosure is shown again rather than assumed).
 *
 * Tests: round trip; unknown key; missing tests object; corrupt text; zones list order kept.
 *
 * Owner: workstream `location-privacy-core`.
 */
object AppSettingsCodec {
    fun encode(settings: AppSettings): String = TODO("location-privacy-core")

    fun decode(text: String?, newInstallId: () -> String): AppSettings = TODO("location-privacy-core")
}
