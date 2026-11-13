package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.livetv.LiveTvStorage
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live TV settings for the active profile. The feature is deliberately left out of settings sync,
 * because the playlist address and Xtream login are credentials that should stay on the device.
 */
@Singleton
class LiveTvDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : LiveTvStorage {
    companion object {
        private const val FEATURE = "live_tv"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    override suspend fun read(key: String): String? =
        store().data.first()[stringPreferencesKey(key)]

    override suspend fun write(key: String, value: String?) {
        store().edit { prefs ->
            val prefKey = stringPreferencesKey(key)
            if (value == null) prefs.remove(prefKey) else prefs[prefKey] = value
        }
    }
}
