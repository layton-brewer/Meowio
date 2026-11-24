package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object StillWatchingStorage {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("still_watching", Context.MODE_PRIVATE)
    }

    actual fun load(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of("still_watching", profileId), null)

    actual fun save(profileId: Int, payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of("still_watching", profileId), payload)?.apply()
    }
}
