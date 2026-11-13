package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal actual object LiveTvPlatformStorage {
    private const val preferencesName = "nuvio_live_tv"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(key: String): String? =
        preferences?.getString(ProfileScopedKey.of("live_tv_$key"), null)

    actual fun save(key: String, value: String?) {
        val editor = preferences?.edit() ?: return
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) editor.remove(scopedKey) else editor.putString(scopedKey, value)
        editor.apply()
    }
}

internal actual fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        }
    } catch (e: IOException) {
        // A cut-off download still holds usable programmes.
        if (out.size() == 0) return null
    }
    return out.toByteArray()
}

internal actual fun liveTvCountryCode(): String? =
    java.util.Locale.getDefault().country.takeIf(String::isNotBlank)
