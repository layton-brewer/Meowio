package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

internal actual object DownloadPrefsStorage {
    private const val preferencesName = "meowio_download_prefs"
    private const val key = "prefs"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(key, null)

    actual fun save(value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }
}
