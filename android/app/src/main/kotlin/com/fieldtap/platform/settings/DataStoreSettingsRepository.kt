package com.fieldtap.platform.settings

import android.content.Context
import com.fieldtap.app.SettingsRepository
import com.fieldtap.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * [SettingsRepository] on DataStore Preferences: one file `settings`, one string key `app_settings`
 * holding `AppSettingsCodec.encode(settings)`. The first read generates the install UUID
 * (`java.util.UUID.randomUUID()`) and stores it. App-specific storage, excluded from backup by
 * `allowBackup=false`.
 *
 * Owner: workstream `platform-adapters`.
 */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = TODO("platform-adapters")

    override suspend fun current(): AppSettings = TODO("platform-adapters")

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = TODO("platform-adapters")
}
