package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class EpisodeShuffleProfile(
    val profileId: Int,
    val available: Boolean = false,
    val shows: Map<String, EpisodeShuffleSettings> = emptyMap()
) {
    fun settings(contentId: String, contentType: String): EpisodeShuffleSettings {
        val saved = shows[contentId] ?: EpisodeShuffleSettings()
        return saved.copy(enabled = saved.enabled && available &&
            (contentType.equals("series", true) || contentType.equals("tv", true)))
    }
}

@Singleton
class EpisodeShuffleStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val layoutPreferences: LayoutPreferenceDataStore
) {
    val profiles = profileManager.activeProfileId.flatMapLatest(::observeProfile)

    fun observeProfile(profileId: Int) = combine(
        factory.get(profileId, "episode_shuffle").data,
        layoutPreferences.randomEpisodeEnabledForProfile(profileId)
    ) { preferences, available ->
        EpisodeShuffleProfile(profileId, available, readShuffleSettings(preferences))
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * Shuffle lasts one viewing session: it ends when the user leaves the player or the show
     * (there is no Stop shuffle button). Turns it off for every show of [profileId].
     */
    fun endSessions(profileId: Int = profileManager.activeProfileId.value) {
        scope.launch {
            runCatching {
                factory.get(profileId, "episode_shuffle").edit { prefs ->
                    prefs.asMap().keys.map { it.name }.filter { it.startsWith("enabled:") }.forEach { name ->
                        prefs[booleanPreferencesKey(name)] = false
                    }
                }
            }
        }
    }

    suspend fun save(contentId: String, settings: EpisodeShuffleSettings, profileId: Int) {
        if (contentId.isBlank()) return
        factory.get(profileId, "episode_shuffle").edit {
            it[booleanPreferencesKey("enabled:$contentId")] = settings.enabled
            it[booleanPreferencesKey("watched:$contentId")] = settings.includeWatched
        }
    }
}

internal fun readShuffleSettings(preferences: Preferences): Map<String, EpisodeShuffleSettings> =
    preferences.asMap().keys.mapNotNull { key ->
        key.name.takeIf { it.startsWith("enabled:") || it.startsWith("watched:") }
            ?.substringAfter(':')?.takeIf(String::isNotBlank)
    }.distinct().associateWith { id ->
        EpisodeShuffleSettings(
            enabled = preferences[booleanPreferencesKey("enabled:$id")] ?: false,
            includeWatched = preferences[booleanPreferencesKey("watched:$id")] ?: false
        )
    }
