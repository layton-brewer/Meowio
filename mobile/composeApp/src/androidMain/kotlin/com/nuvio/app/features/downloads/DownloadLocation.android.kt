package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Where new downloads go on Android. The default is a folder the app owns. The user can also pick any
 * folder on the phone, SD card or USB drive with Android's folder picker; the app then keeps lasting
 * access to it and writes downloads straight into it. If that access is ever lost (folder deleted,
 * card removed, access revoked) new downloads fall back to the default folder.
 */
internal actual object DownloadLocation {
    private const val PREFS = "meowio_download_prefs"
    private const val KEY = "location"
    private const val NAMES_PREFS = "meowio_download_tree_names"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private fun context(): Context? = DownloadsPlatformDownloader.contextOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }

    private fun defaultDirectory(context: Context) = File(context.filesDir, "downloads")

    /**
     * Plain folder used for unfinished files and saved subtitles. This is the chosen folder when it
     * is a normal path, otherwise the app's default folder.
     */
    fun directory(context: Context): File {
        val chosen = saved(context)?.takeUnless(::isContentUri)?.let(::File)
            ?.takeIf { it.isDirectory || it.mkdirs() }
        return chosen ?: defaultDirectory(context)
    }

    /** Folder where subtitles of downloads in a picked folder are kept. */
    fun subtitleDirectory(context: Context): File = defaultDirectory(context)

    /** Where new downloads are written right now. */
    fun target(context: Context): AndroidDownloadTarget =
        saved(context)?.takeIf(::isContentUri)?.let { AndroidDownloadTarget.fromLocation(context, it) }
            ?: AndroidDownloadTarget.Folder(directory(context))

    /** The picked folder's tree URI, if one is saved (used to open the picker where the user left off). */
    fun savedTreeUri(context: Context): Uri? =
        saved(context)?.takeIf(::isContentUri)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Remembers the file name of a download saved in a picked folder, so its subtitles can be found. */
    fun rememberTreeFileName(context: Context, uri: String, fileName: String) {
        val names = context.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE)
        if (names.getString(uri, null) != fileName) names.edit().putString(uri, fileName).apply()
    }

    fun treeFileName(context: Context, uri: String): String? =
        context.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE).getString(uri, null)
            ?: runCatching {
                DocumentsContract.getDocumentId(Uri.parse(uri)).substringAfterLast(':').substringAfterLast('/')
            }.getOrNull()?.takeIf { it.isNotBlank() }

    fun forgetTreeFileName(context: Context, uri: String) {
        context.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE).edit().remove(uri).apply()
    }

    actual fun currentPath(): String {
        val ctx = context() ?: return ""
        val saved = saved(ctx)
        if (saved != null && isContentUri(saved)) {
            val target = target(ctx)
            return if (target is AndroidDownloadTarget.Tree) {
                readableTree(ctx, target.tree)
            } else {
                "${directory(ctx).absolutePath} (lost access to the chosen folder, using the default)"
            }
        }
        return directory(ctx).absolutePath
    }

    actual fun isCustom(): Boolean {
        val ctx = context() ?: return false
        return saved(ctx) != null
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
                "SD card / USB drive (Android/data folder)"
            }
            list += DownloadLocationOption(File(base, "downloads").absolutePath, label)
        }
        return list
    }

    actual fun select(path: String?) {
        val ctx = context() ?: return
        val previous = saved(ctx)
        if (path != null && isContentUri(path) && AndroidDownloadTarget.fromLocation(ctx, path) == null) return
        val editor = prefs(ctx).edit()
        if (path == null || path == defaultDirectory(ctx).absolutePath) editor.remove(KEY) else editor.putString(KEY, path)
        editor.apply()
        if (previous != null && previous != path && isContentUri(previous)) releaseTree(ctx, previous)
    }

    /**
     * Gives up access to a folder that is no longer the download folder, unless finished downloads
     * still live in it (they need the access to keep playing).
     */
    private fun releaseTree(context: Context, tree: String) {
        val stillUsed = DownloadsRepository.uiState.value.items.any { item ->
            item.localFileUri?.startsWith(treeDocumentPrefix(tree)) == true
        } || DownloadsPlatformDownloader.managedTransfers().any { it.targetLocation == tree }
        if (stillUsed) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(tree),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun treeDocumentPrefix(tree: String): String = "$tree/document/"

    private fun readableTree(context: Context, tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull().orEmpty()
        if (tree.authority == EXTERNAL_STORAGE_AUTHORITY && id.contains(':')) {
            val volume = id.substringBefore(':')
            val folder = id.substringAfter(':').trim('/')
            val root = when {
                volume.equals("primary", ignoreCase = true) -> "Phone storage"
                volume.equals("home", ignoreCase = true) -> "Phone storage/Documents"
                else -> "SD card / USB drive ($volume)"
            }
            return if (folder.isEmpty()) root else "$root/$folder"
        }
        val name = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return name ?: id.ifBlank { tree.toString() }
    }

    actual val canBrowse: Boolean = true

    actual fun browse(): String? {
        val ctx = context() ?: return null
        return runBlocking { DownloadFolderPickerActivity.launch(ctx).await() }
    }
}
