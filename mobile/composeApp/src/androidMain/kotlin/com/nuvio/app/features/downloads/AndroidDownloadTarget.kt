package com.nuvio.app.features.downloads

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * A folder downloads are written into: either a plain folder the app can reach with file paths, or a
 * folder the user picked with Android's folder picker (any folder on the phone, SD card or USB drive),
 * which is reached through its document tree URI.
 */
internal sealed interface AndroidDownloadTarget {
    /** Stable text that identifies this folder, saved with each transfer so resume finds its partial file. */
    val location: String

    /** Bytes already in the unfinished `.part` file, or 0. */
    fun partialLength(fileName: String): Long

    /** Opens the `.part` file for writing, appending when [append] is true. */
    fun openPartial(fileName: String, append: Boolean): FileOutputStream

    /** Renames the `.part` file to [fileName] and returns the finished file's URI. */
    fun finish(fileName: String): String

    /** URI of an already finished file with this name, or null. */
    fun finishedUri(fileName: String): String?

    /** Size of the finished file at [uri]. */
    fun finishedLength(uri: String): Long

    fun deletePartial(fileName: String)

    class Folder(val directory: File) : AndroidDownloadTarget {
        override val location: String get() = directory.absolutePath

        private fun partial(fileName: String) = File(directory, "$fileName.part")

        override fun partialLength(fileName: String): Long = partial(fileName).takeIf(File::isFile)?.length() ?: 0L

        override fun openPartial(fileName: String, append: Boolean): FileOutputStream {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            return FileOutputStream(partial(fileName), append)
        }

        override fun finish(fileName: String): String {
            val destination = File(directory, fileName)
            if (!partial(fileName).renameTo(destination)) throw IOException("Could not finalize the downloaded file")
            return destination.toURI().toString()
        }

        override fun finishedUri(fileName: String): String? =
            File(directory, fileName).takeIf(File::isFile)?.toURI()?.toString()

        override fun finishedLength(uri: String): Long = File(java.net.URI(uri)).length()

        override fun deletePartial(fileName: String) {
            partial(fileName).delete()
        }
    }

    class Tree(private val context: Context, val tree: Uri) : AndroidDownloadTarget {
        override val location: String get() = tree.toString()

        private val resolver get() = context.contentResolver
        private val folderId: String = DocumentsContract.getTreeDocumentId(tree)
        private val folderUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)

        private fun child(name: String): Uri? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            return runCatching {
                resolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == name) {
                            return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        }
                    }
                    null
                }
            }.getOrNull()
        }

        private fun sizeOf(uri: Uri): Long = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)

        override fun partialLength(fileName: String): Long = child("$fileName.part")?.let(::sizeOf) ?: 0L

        override fun openPartial(fileName: String, append: Boolean): FileOutputStream {
            val name = "$fileName.part"
            val uri = child(name)
                ?: DocumentsContract.createDocument(resolver, folderUri, "application/octet-stream", name)
                ?: throw IOException("Cannot create a file in the download folder")
            val descriptor = resolver.openFileDescriptor(uri, if (append) "wa" else "rwt")
                ?: throw FileNotFoundException("Cannot open the download file")
            return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        }

        override fun finish(fileName: String): String {
            val partial = child("$fileName.part") ?: throw IOException("Downloaded file is missing")
            child(fileName)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            val finished = DocumentsContract.renameDocument(resolver, partial, fileName) ?: partial
            val uri = finished.toString()
            DownloadLocation.rememberTreeFileName(context, uri, fileName)
            return uri
        }

        override fun finishedUri(fileName: String): String? = child(fileName)?.toString()?.also {
            DownloadLocation.rememberTreeFileName(context, it, fileName)
        }

        override fun finishedLength(uri: String): Long = sizeOf(Uri.parse(uri))

        override fun deletePartial(fileName: String) {
            child("$fileName.part")?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }

        /** True while the app still holds write access to the folder and the folder still exists. */
        fun isUsable(): Boolean {
            val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (!granted) return false
            return runCatching {
                resolver.query(
                    folderUri,
                    arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                    null, null, null,
                )?.use { cursor ->
                    cursor.moveToFirst() &&
                        (cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
                } == true
            }.getOrDefault(false)
        }
    }

    companion object {
        /** The target saved as [location], or null if that folder can no longer be used. */
        fun fromLocation(context: Context, location: String?): AndroidDownloadTarget? {
            if (location.isNullOrBlank()) return null
            return if (location.startsWith("content:")) {
                runCatching { Tree(context, Uri.parse(location)) }.getOrNull()?.takeIf { it.isUsable() }
            } else {
                File(location).takeIf { it.isDirectory || it.mkdirs() }?.let(::Folder)
            }
        }
    }
}

internal fun isContentUri(value: String?): Boolean = value?.startsWith("content:", ignoreCase = true) == true

/** Deletes a finished download that lives in a picked folder. */
internal fun deleteTreeDocument(context: Context, uri: String): Boolean = runCatching {
    DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
}.getOrDefault(false)

/** True when the document at [uri] still exists and can be read. */
internal fun treeDocumentExists(context: Context, uri: String): Boolean = runCatching {
    context.contentResolver.query(
        Uri.parse(uri),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        null, null, null,
    )?.use { it.moveToFirst() } == true
}.getOrDefault(false)
