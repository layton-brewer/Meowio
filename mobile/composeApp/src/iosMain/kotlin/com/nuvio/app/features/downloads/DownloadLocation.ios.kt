package com.nuvio.app.features.downloads

/**
 * iOS only lets an app write inside its own container. Downloads live in the app's Documents folder,
 * which is shown in the Files app.
 */
internal actual object DownloadLocation {
    actual fun currentPath(): String = "Files app > Meowio > nuvio_downloads"

    actual fun isCustom(): Boolean = false

    actual fun options(): List<DownloadLocationOption> = emptyList()

    actual fun select(path: String?) = Unit

    actual val canBrowse: Boolean = false

    actual fun browse(): String? = null
}
