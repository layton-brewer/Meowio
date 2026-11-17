package com.nuvio.app.features.zap

/** Remembers which catalogs Surprise Me draws from, as one newline-separated string. */
internal expect object ZapSourceStorage {
    fun load(): String?
    fun save(value: String)
}
