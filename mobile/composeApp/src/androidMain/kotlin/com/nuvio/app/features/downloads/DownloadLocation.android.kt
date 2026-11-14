package com.nuvio.app.features.downloads

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Android keeps downloads in folders the app owns, so playback and resume keep working without extra
 * permissions. The choices are the phone's internal storage and any SD card or USB drive that is mounted.
 */
internal actual object DownloadLocation {
    private const val PREFS = "meowio_download_prefs"
    private const val KEY = "location"

    private fun context(): Context? = DownloadsPlatformDownloader.contextOrNull()

    private fun defaultDirectory(context: Context) = File(context.filesDir, "downloads")

    /** The folder new downloads are written to; falls back to the default if the chosen volume is gone. */
    fun directory(context: Context): File {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val chosen = saved?.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isDirectory || it.mkdirs() }
        return chosen ?: defaultDirectory(context)
    }

    actual fun currentPath(): String = context()?.let { directory(it).absolutePath }.orEmpty()

    actual fun isCustom(): Boolean {
        val ctx = context() ?: return false
        return directory(ctx).absolutePath != defaultDirectory(ctx).absolutePath
    }

    actual fun options(): List<DownloadLocationOption> {
        val ctx = context() ?: return emptyList()
        val list = mutableListOf(
            DownloadLocationOption(defaultDirectory(ctx).absolutePath, "Private app storage (default)"),
        )
        ctx.getExternalFilesDirs(null).filterNotNull().forEachIndexed { index, base ->
            if (Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return@forEachIndexed
            val label = if (index == 0) {
                "Phone storage (Android/data folder, visible to file managers)"
            } else {
                "SD card / USB drive"
            }
            list += DownloadLocationOption(File(base, "downloads").absolutePath, label)
        }
        return list
    }

    actual fun select(path: String?) {
        val ctx = context() ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (path == null) prefs.remove(KEY) else prefs.putString(KEY, path)
        prefs.apply()
    }

    actual val canBrowse: Boolean = false

    actual fun browse(): String? = null
}
