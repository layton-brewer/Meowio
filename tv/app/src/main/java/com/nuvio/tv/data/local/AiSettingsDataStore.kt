package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the viewer's own key for the AI assistant. It lives only on this device: the feature is
 * deliberately left out of account sync and of settings backups.
 */
@Singleton
class AiSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val apiKeyKey = stringPreferencesKey("ai_api_key")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val apiKey: Flow<String> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { it[apiKeyKey].orEmpty() }
    }

    suspend fun currentApiKey(): String = apiKey.first()

    suspend fun setApiKey(value: String) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(apiKeyKey) else prefs[apiKeyKey] = trimmed
        }
    }

    private companion object {
        const val FEATURE = "ai_assistant"
    }
}
