package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val videoFile = File(URI(localVideoUri))
    private val directory = File(videoFile.path + ".subtitles")

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
        arabicSidecarFile().delete()
    }

    private fun file(fileName: String): File {
        require(fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != "..")
        return if (fileName == "arabic.ar.srt") arabicSidecarFile() else File(directory, fileName)
    }

    private fun arabicSidecarFile(): File = File(videoFile.parentFile, videoFile.name + ".ar.srt")
}
