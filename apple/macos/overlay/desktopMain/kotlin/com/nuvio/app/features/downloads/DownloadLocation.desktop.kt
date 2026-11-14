package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

internal actual object DownloadLocation {
    private val store = DesktopStorage.store("meowio_download_location")
    private const val KEY = "path"

    private fun defaultDirectory(): File =
        File(DesktopStorage.rootDir.resolve("downloads").toUri())

    /** The folder new downloads are written to; falls back to the default if the chosen one is unusable. */
    fun directory(): File {
        val saved = store.getString(KEY)?.takeIf { it.isNotBlank() }?.let(::File)
        val dir = saved?.takeIf { it.isDirectory || it.mkdirs() } ?: defaultDirectory()
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    actual fun currentPath(): String = directory().absolutePath

    actual fun isCustom(): Boolean = !store.getString(KEY).isNullOrBlank()

    actual fun options(): List<DownloadLocationOption> = emptyList()

    actual fun select(path: String?) {
        store.putString(KEY, path.orEmpty())
    }

    actual val canBrowse: Boolean = true

    actual fun browse(): String? {
        var result: String? = null
        val show = Runnable {
            val chooser = JFileChooser(directory()).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose download folder"
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result = chooser.selectedFile?.absolutePath
            }
        }
        runCatching { SwingUtilities.invokeAndWait(show) }
        return result
    }
}
