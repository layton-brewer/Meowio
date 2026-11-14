package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DownloadPrefsStorage {
    private val store = DesktopStorage.store("meowio_download_prefs")

    actual fun load(): String? = store.getString("prefs")

    actual fun save(value: String) {
        store.putString("prefs", value)
    }
}
