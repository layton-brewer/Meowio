package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object StillWatchingStorage {
    private val store = DesktopStorage.store("still_watching")

    actual fun load(profileId: Int): String? =
        store.getString(ProfileScopedKey.of("still_watching", profileId))

    actual fun save(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of("still_watching", profileId), payload)
    }
}
