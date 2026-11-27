package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

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
            result = if (isMac) browseMac() else browseWithChooser()
        }
        runCatching {
            if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
        }
        return result?.takeIf { File(it).let { dir -> dir.isDirectory && dir.canWrite() } }
    }

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** The macOS Finder folder dialog. */
    private fun browseMac(): String? {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val start = directory()
        return try {
            val dialog = FileDialog(null as Frame?, "Choose download folder", FileDialog.LOAD)
            dialog.directory = start.parentFile?.absolutePath
            dialog.file = start.name
            dialog.isVisible = true
            val name = dialog.file ?: return null
            File(dialog.directory ?: return null, name).absolutePath
        } finally {
            if (previous == null) System.clearProperty("apple.awt.fileDialogForDirectories")
            else System.setProperty("apple.awt.fileDialogForDirectories", previous)
        }
    }

    /** Windows (and Linux) folder dialog, drawn in the system style. */
    private fun browseWithChooser(): String? {
        val start = directory()
        val previousLook = UIManager.getLookAndFeel()
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        try {
            val chooser = JFileChooser(start).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose download folder"
                approveButtonText = "Use this folder"
                isAcceptAllFileFilterUsed = false
            }
            return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else null
        } finally {
            runCatching { UIManager.setLookAndFeel(previousLook) }
        }
    }
}
