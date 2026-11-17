package com.nuvio.app.features.zap

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ZapSourceStorage {
    private val store = DesktopStorage.store("meowio_zap_sources")

    actual fun load(): String? = store.getString("keys")

    actual fun save(value: String) {
        store.putString("keys", value)
    }
}
