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
        assertEquals("مرحبا", sanitizeDownloadedSubtitle("\u00D9\u2026\u00D8\u00B1\u00D8\u00AD\u00D8\u00A8\u00D8\u00A7"))
    }

    @Test
    fun prefersArabicLanguageTagsAndArabicNamedTracks() {
        assertTrue(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/a.srt", language = "ara")))
        assertTrue(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/a.srt", language = "en", name = "ترجمة عربية")))
        assertFalse(isArabicDownloadSubtitle(StreamSubtitle(url = "https://example.test/e.srt", language = "en", name = "English")))
    }
}