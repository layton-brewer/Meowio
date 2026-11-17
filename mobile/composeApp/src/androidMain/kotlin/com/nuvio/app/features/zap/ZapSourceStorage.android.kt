package com.nuvio.app.features.zap

import android.content.Context
import android.content.SharedPreferences

internal actual object ZapSourceStorage {
    private const val preferencesName = "meowio_zap_sources"
    private const val key = "keys"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(key, null)

    actual fun save(value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }
}
