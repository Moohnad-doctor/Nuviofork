package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(item: DownloadItem, localVideoUri: String): Unit = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        if (manifest.complete && saved.size == manifest.tracks.size) return@withContext
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        val addonSubtitles = loadAddonSubtitles(item.subtitleRequests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val allPending = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    saved.none { it.sourceUrl == subtitle.url }
            }
        val arabicCandidates = allPending.filter(::isArabicDownloadSubtitle)
        val preferredArabic = arabicCandidates.sortedBy { arabicSubtitleRank(it.language, it.name) }.take(1)
        val pending = preferredArabic + allPending.filterNot(::isArabicDownloadSubtitle)
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        val body = try {
                            withTimeoutOrNull(15_000L) {
                                httpGetTextWithHeaders(subtitle.url, subtitle.headers.orEmpty())
                            }?.let(::sanitizeDownloadedSubtitle)
                                ?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        subtitle to body
                    }
                }.awaitAll()
            }
            for ((subtitle, body) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) continue
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                val fileName = if (isArabicDownloadSubtitle(subtitle)) {
                    if (extension.equals("srt", true)) "arabic.ar.srt" else "arabic.ar.$extension"
                } else {
                    "${nextFileIndex++}.$extension"
                }
                try {
                    storage.write(fileName, body)
                    saved += DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        runCatching {
            storage.write("manifest.json", json.encodeToString(SubtitleManifest(complete = true, tracks = saved)))
        }
        Unit
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

internal fun isArabicDownloadSubtitle(subtitle: StreamSubtitle): Boolean =
    isArabicDownloadSubtitle(subtitle.language, subtitle.name)

private fun isArabicDownloadSubtitle(language: String, name: String?): Boolean {
    val tag = language.trim().lowercase().replace('_', '-')
    return tag == "ar" || tag == "ara" || tag.startsWith("ar-") ||
        name.orEmpty().any { it in '\u0600'..'\u06FF' }
}

private fun arabicSubtitleRank(language: String, name: String?): Int = when (language.trim().lowercase()) {
    "ar" -> 0
    "ara" -> 1
    else -> if (name.orEmpty().any { it in '\u0600'..'\u06FF' }) 2 else 3
}

internal fun sanitizeDownloadedSubtitle(input: String): String {
    val withoutBom = input.removePrefix("\uFEFF")
    val looksLikeUtf8DecodedAsLatin1 = withoutBom.count { it == 'Ã' || it == 'Ø' || it == 'Ù' || it == 'Â' } >= 2
    if (!looksLikeUtf8DecodedAsLatin1) return withoutBom
    val cp1252 = "\u20AC\u0081\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u008D\u017D\u008F\u0090\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u017E\u0178"
    val bytes = ByteArray(withoutBom.length)
    for (index in withoutBom.indices) {
        val code = withoutBom[index].code
        val byteValue = when {
            code <= 0xFF -> code
            cp1252.indexOf(withoutBom[index]) >= 0 -> 0x80 + cp1252.indexOf(withoutBom[index])
            else -> return withoutBom
        }
        bytes[index] = byteValue.toByte()
    }
    val repaired = bytes.decodeToString()
    val originalArabic = withoutBom.count { it in '\u0600'..'\u06FF' }
    val repairedArabic = repaired.count { it in '\u0600'..'\u06FF' }
    return (if (repairedArabic > originalArabic) repaired else withoutBom).removePrefix("\uFEFF")
}

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
