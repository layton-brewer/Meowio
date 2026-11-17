package com.nuvio.app.features.zap

import platform.Foundation.NSUserDefaults

internal actual object ZapSourceStorage {
    private const val key = "meowio_zap_sources"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun save(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}
