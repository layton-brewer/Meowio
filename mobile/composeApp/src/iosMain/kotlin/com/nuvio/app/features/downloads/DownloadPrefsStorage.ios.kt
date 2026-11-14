package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

internal actual object DownloadPrefsStorage {
    private const val key = "meowio_download_prefs"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun save(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}
