package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale

internal actual object LiveTvPlatformStorage {
    actual fun load(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("live_tv_$key"))

    actual fun save(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(scopedKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = scopedKey)
        }
    }
}

// Guide files packed with gzip are skipped on iOS for now; plain guides and playlists work.
internal actual fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray? = null

internal actual fun liveTvCountryCode(): String? =
    NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String

internal actual suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean = false

internal actual suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean? = null
