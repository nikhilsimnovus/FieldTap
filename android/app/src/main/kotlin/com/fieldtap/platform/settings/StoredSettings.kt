package com.fieldtap.platform.settings

import com.fieldtap.core.settings.AppSettings
import com.fieldtap.core.settings.AppSettingsCodec

/**
 * Settings decoded from the stored text. [repairedText] is the text to store when decoding had to
 * generate an install id (nothing stored yet, or unusable text); null when the stored text is fine.
 */
internal data class DecodedSettings(val settings: AppSettings, val repairedText: String?)

/**
 * The pure part of [DataStoreSettingsRepository]: decode the stored string and tell whether the install id
 * was just generated, so it can be stored and stays the same on every later read.
 *
 * Owner: workstream `platform-adapters`.
 */
internal object StoredSettings {
    fun decode(text: String?, newInstallId: () -> String): DecodedSettings {
        var generated: String? = null
        val settings = AppSettingsCodec.decode(text) { newInstallId().also { generated = it } }
        val usedGenerated = generated != null && settings.installId == generated
        return DecodedSettings(settings, if (usedGenerated) AppSettingsCodec.encode(settings) else null)
    }
}
