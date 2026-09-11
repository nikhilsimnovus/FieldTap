package com.fieldtap.platform.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fieldtap.app.SettingsRepository
import com.fieldtap.core.settings.AppSettings
import com.fieldtap.core.settings.AppSettingsCodec
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val TAG = "FieldTapSettings"

private val SETTINGS_KEY: Preferences.Key<String> = stringPreferencesKey(DataStoreSettingsRepository.KEY_NAME)

/** One DataStore per process for the file, as DataStore requires. */
private val Context.fieldTapSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreSettingsRepository.FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { exception ->
        Log.w(TAG, "The settings file could not be read and was reset", exception)
        emptyPreferences()
    },
)

/**
 * [SettingsRepository] on DataStore Preferences: one file `settings`, one string key `app_settings`
 * holding `AppSettingsCodec.encode(settings)`. The first read generates the install UUID
 * (`java.util.UUID.randomUUID()`) and stores it. App-specific storage, excluded from backup by
 * `allowBackup=false`.
 *
 * - The install id is stored only when the stored text is still what was read (compare and set), so a
 *   concurrent [update] is never overwritten; the value then returned is what the file holds, so every
 *   reader sees the same install id.
 * - A corrupt file is replaced by an empty one (a new install id, no consent: the disclosure shows again).
 *   If the file cannot be read at all, [settings] emits defaults with a temporary install id and ends.
 * - [update] runs its transform inside DataStore's atomic edit, so concurrent updates never lose each
 *   other's changes; an IOException reaches the caller.
 * - [settings] repeats no equal value.
 * - Constructing the repository touches no file.
 *
 * Owner: workstream `platform-adapters`.
 */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    private val store: DataStore<Preferences> get() = context.fieldTapSettingsStore

    override val settings: Flow<AppSettings> = flow {
        emitAll(
            store.data
                .catch { failure ->
                    if (failure !is IOException) throw failure
                    Log.e(TAG, "Settings could not be read; showing defaults", failure)
                    emit(emptyPreferences())
                }
                .map { preferences -> decodeStored(preferences) },
        )
    }.distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var updated: AppSettings? = null
        store.edit { preferences ->
            val current = StoredSettings.decode(preferences[SETTINGS_KEY], ::newInstallId).settings
            val next = transform(current)
            preferences[SETTINGS_KEY] = AppSettingsCodec.encode(next)
            updated = next
        }
        return checkNotNull(updated) { "DataStore finished an edit without running it" }
    }

    private suspend fun decodeStored(preferences: Preferences): AppSettings {
        val stored = preferences[SETTINGS_KEY]
        val decoded = StoredSettings.decode(stored, ::newInstallId)
        val repaired = decoded.repairedText ?: return decoded.settings
        val saved: Preferences = try {
            store.edit { current ->
                if (current[SETTINGS_KEY] == stored) current[SETTINGS_KEY] = repaired
            }
        } catch (e: IOException) {
            Log.e(TAG, "A new install id could not be saved", e)
            return decoded.settings
        }
        // What the file holds now: the repaired text, or what a concurrent update wrote first.
        return StoredSettings.decode(saved[SETTINGS_KEY], ::newInstallId).settings
    }

    companion object {
        /** The DataStore file name (`files/datastore/settings.preferences_pb`). */
        const val FILE_NAME: String = "settings"

        /** The one preferences key. */
        const val KEY_NAME: String = "app_settings"
    }
}

private fun newInstallId(): String = UUID.randomUUID().toString()
