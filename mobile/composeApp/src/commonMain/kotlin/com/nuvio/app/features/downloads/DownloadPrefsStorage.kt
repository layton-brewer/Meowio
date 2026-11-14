package com.nuvio.app.features.downloads

internal expect object DownloadPrefsStorage {
    fun load(): String?
    fun save(value: String)
}
