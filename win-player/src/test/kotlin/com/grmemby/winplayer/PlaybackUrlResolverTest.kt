package com.grmemby.winplayer

import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.PlaybackInfoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackUrlResolverTest {
    @Test
    fun directStreamUrlIsMadeAbsoluteAndGetsApiKey() {
        val playback = PlaybackUrlResolver.resolve(
            baseUrl = "https://example.test/emby/",
            itemId = "item 1",
            accessToken = "test",
            playbackInfo = PlaybackInfoResponse(
                mediaSources = listOf(
                    MediaSource(
                        id = "ms1",
                        name = "Main",
                        directStreamUrl = "/Videos/item%201/stream?static=true",
                        requiredHttpHeaders = mapOf(
                            "User-Agent" to "GrmembyTest",
                            "X-Emby-Authorization" to "MediaBrowser Token=sample"
                        )
                    )
                ),
                playSessionId = "session1"
            )
        )

        assertTrue(playback.url.startsWith("https://example.test/emby/Videos/item%201/stream?static=true"))
        assertTrue(playback.url.contains("api_key=test"))
        assertEquals("ms1", playback.mediaSourceId)
        assertEquals(mapOf("User-Agent" to "GrmembyTest"), playback.requiredHeaders)
    }

    @Test
    fun fallbackStreamUrlUsesMediaSourceAndApiKey() {
        val url = PlaybackUrlResolver.buildFallbackStreamUrl(
            baseUrl = "https://example.test/emby",
            itemId = "abc def",
            mediaSourceId = "media source",
            accessToken = "test"
        )

        assertEquals(
            "https://example.test/emby/Videos/abc+def/stream?static=true&mediaSourceId=media+source&api_key=test",
            url
        )
    }

    @Test
    fun mpvCommandDoesNotLeakGenericAuthHeaders() {
        val spec = MpvCommandBuilder.build(
            mpvPath = "mpv.exe",
            playback = ResolvedPlayback(
                url = "https://example.test/video?api_key=test",
                mediaSourceId = "ms1",
                playSessionId = null,
                requiredHeaders = mapOf(
                    "User-Agent" to "UA",
                    "X-Test" to "ok",
                    "X-Comma" to "a,b"
                ),
                displayTitle = "Movie"
            )
        )

        val joined = spec.command.joinToString(" ")
        assertTrue(spec.command.contains("--user-agent=UA"))
        assertTrue(joined.contains("--http-header-fields=X-Test: ok"))
        assertTrue(spec.skippedHeaders.contains("X-Comma"))
        assertFalse(joined.contains("X-Emby-Authorization"))
    }
}
