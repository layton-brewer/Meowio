package com.nuvio.app.features.downloads

internal data class DownloadLocationOption(val path: String, val label: String)

/**
 * Where new downloads are saved. Items that are already downloaded keep working because each one
 * remembers its own file location; only new downloads use the current choice.
 */
internal expect object DownloadLocation {
    /** Absolute path of the folder new downloads go to. */
    fun currentPath(): String

    /** True when the folder was changed from the app default. */
    fun isCustom(): Boolean

    /** Ready-made choices (storage volumes). Empty when the platform uses a folder dialog or has no choice. */
    fun options(): List<DownloadLocationOption>

    /** Saves [path] as the download folder, or restores the default when null. */
    fun select(path: String?)

    /** True when [browse] can open a native folder dialog. */
    val canBrowse: Boolean

    /** Shows a native folder dialog; returns the picked folder or null if cancelled. Blocking. */
    fun browse(): String?
}
