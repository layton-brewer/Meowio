package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory = subtitleDirectoryFor(localVideoUri)

    actual fun read(fileName: String): String? =
        runCatching { AtomicFile(file(fileName)).readFully().decodeToString() }.getOrNull()

    actual fun write(fileName: String, text: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle directory" }
        val file = AtomicFile(file(fileName))
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf { it.isFile }?.toURI()?.toString()

    actual fun remove() {
        directory.deleteRecursively()
    }

    private fun file(fileName: String): File {
        require(fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != "..")
        return File(directory, fileName)
    }
}

/**
 * Subtitles sit next to a download in a `.subtitles` folder. Downloads saved in a folder the user
 * picked keep their subtitles in the app's own download folder instead, under the same file name.
 */
private fun subtitleDirectoryFor(localVideoUri: String): File {
    if (isContentUri(localVideoUri)) {
        val context = checkNotNull(DownloadsPlatformDownloader.contextOrNull()) { "Downloads are not initialized" }
        val fileName = DownloadLocation.treeFileName(context, localVideoUri)
            ?.takeIf { File(it).name == it && it != "." && it != ".." }
            ?: localVideoUri.hashCode().toUInt().toString(16)
        return File(DownloadLocation.subtitleDirectory(context), "$fileName.subtitles")
    }
    return File(File(URI(localVideoUri)).path + ".subtitles")
}
