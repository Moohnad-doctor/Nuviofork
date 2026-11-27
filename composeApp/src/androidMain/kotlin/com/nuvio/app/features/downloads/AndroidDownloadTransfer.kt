package com.nuvio.app.features.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            if (item.sourceUrl.isHlsPlaylistUrl()) {
                return@async transferAndroidHlsDownload(item, directory, client, onHeaders, onProgress)
            }
            val partial = File(directory, "${item.fileName}.part")
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            var restarted = false

            while (true) {
                ensureActive()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.first != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = range?.second ?: contentLength?.let { startingBytes + it }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    onHeaders(totalBytes, responseValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    return@async partial
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

private fun String.isHlsPlaylistUrl(): Boolean = substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

private suspend fun transferAndroidHlsDownload(
    item: DownloadItem,
    directory: File,
    client: OkHttpClient,
    onHeaders: (Long?, String?) -> Unit,
    onProgress: (Long, Long?) -> Unit,
): File {
    val partial = File(directory, "${item.fileName}.part")
    partial.delete() // HLS segments are restarted as a complete playlist, never byte-range resumed.
    val playlistRequest = Request.Builder().url(item.sourceUrl).apply {
        item.sourceHeaders.forEach { (key, value) -> header(key, value) }
    }.build()
    val playlistResponse = client.newCall(playlistRequest).execute()
    val (playlistUrl, playlistText) = playlistResponse.use { response ->
        if (!response.isSuccessful) throw DownloadHttpException(response.code)
        val body = response.body?.string() ?: throw IOException("HLS playlist is empty")
        response.request.url to body
    }
    val playlistLines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val variantLine = playlistLines.indices.filter { playlistLines[it].startsWith("#EXT-X-STREAM-INF", true) }
        .mapNotNull { index ->
            val uri = playlistLines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@mapNotNull null
            val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(playlistLines[index])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            bandwidth to uri
        }.maxByOrNull { it.first }?.second
    val mediaUrl = variantLine?.let { playlistUrl.resolve(it) } ?: playlistUrl
        ?: throw IOException("HLS variant URL is invalid")
    val mediaText = if (variantLine == null) playlistText else {
        val request = Request.Builder().url(mediaUrl).apply { item.sourceHeaders.forEach { (key, value) -> header(key, value) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DownloadHttpException(response.code)
            response.body?.string() ?: throw IOException("HLS media playlist is empty")
        }
    }
    if (Regex("#EXT-X-KEY:(?!.*METHOD=NONE)", RegexOption.IGNORE_CASE).containsMatchIn(mediaText) ||
        mediaText.contains("#EXT-X-MAP", true) || mediaText.contains("#EXT-X-BYTERANGE", true)) {
        throw IOException("Encrypted, fragmented MP4, and byte-range HLS playlists are not supported yet")
    }
    val mediaBaseUrl = if (variantLine == null) playlistUrl else mediaUrl
    val segments = mediaText.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { mediaBaseUrl.resolve(it) }
        .toList()
    if (segments.isEmpty()) throw IOException("HLS playlist contains no media segments")
    onHeaders(null, null)
    var downloaded = 0L
    FileOutputStream(partial, false).use { output ->
        segments.forEach { segmentUrl ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(segmentUrl).apply {
                item.sourceHeaders.forEach { (key, value) -> header(key, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DownloadHttpException(response.code)
                val body = response.body ?: throw IOException("HLS segment is empty")
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, null)
                    }
                }
            }
        }
        output.fd.sync()
    }
    if (downloaded == 0L) throw IOException("HLS download produced an empty file")
    return partial
}

internal fun parseDownloadContentRange(value: String): Pair<Long, Long?>? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || (total != null && end >= total)) return null
    return start to total
}
