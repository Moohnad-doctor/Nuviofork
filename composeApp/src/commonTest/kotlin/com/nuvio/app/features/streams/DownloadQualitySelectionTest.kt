package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadQualitySelectionTest {
    private fun stream(name: String, url: String) = StreamItem(
        name = name,
        url = url,
        addonName = "test",
        addonId = "test",
    )

    @Test
    fun exactRequestedQualityWins() {
        val hd = stream("1080p", "https://cdn.example/1080.mp4")
        val sd = stream("480p", "https://cdn.example/480.mp4")
        assertEquals(hd, selectDownloadQualityStream(listOf(sd, hd), 1080))
    }

    @Test
    fun closestAvailableQualityIsUsedAsFallback() {
        val hd = stream("720p", "https://cdn.example/720.mp4")
        val sd = stream("480p", "https://cdn.example/480.mp4")
        assertEquals(hd, selectDownloadQualityStream(listOf(sd, hd), 1080))
    }

    @Test
    fun highestQualityChoosesMaximumResolution() {
        val hd = stream("1080p", "https://cdn.example/1080.mp4")
        val ultra = stream("2160p", "https://cdn.example/2160.mp4")
        assertEquals(ultra, selectDownloadQualityStream(listOf(hd, ultra), null))
    }

    @Test
    fun nonHttpTorrentSourcesAreNotSelected() {
        val torrent = stream("1080p", "magnet:?xt=urn:btih:abc")
        assertNull(selectDownloadQualityStream(listOf(torrent), 1080))
    }
}
