package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSubtitleSanitizationTest {
    @Test
    fun stripsUtf8Bom() {
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nمرحبا", sanitizeDownloadedSubtitle("\uFEFF1\n00:00:01,000 --> 00:00:02,000\nمرحبا"))
    }

    @Test
    fun repairsUtf8ArabicDecodedAsLatin1() {
        assertEquals("مرحبا", sanitizeDownloadedSubtitle("Ù\u2026Ø\u00b1Ø\u00adØ\u00a8Ø\u00a7"))
    }

    @Test
    fun prefersArabicLanguageTagsAndArabicNamedTracks() {
        assertTrue(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/a.srt", language = "ara")))
        assertTrue(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/a.srt", language = "en", name = "ترجمة عربية")))
        assertFalse(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/e.srt", language = "en", name = "English")))
    }
}