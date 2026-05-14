package com.grmemby.app.playback

import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.MediaSourceInfo
import com.grmemby.data.model.PlaybackInfoResponse
import com.grmemby.data.model.PlaybackRequest
import com.grmemby.data.network.ServerType
import com.grmemby.data.repository.EmosKeepAliveUrlCandidateBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmosPlaybackUrlBuilderTest {
    @Test
    fun emyaDirectStreamUrlResolvesUnderApiBaseLikeHillsReferencePlayer() {
        val playbackInfo = PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    directStreamUrl = "/emya/video?media_id=m1&token=t1&line=l1&server=s1"
                )
            )
        )

        val resolved = invokePlaybackInfoUrls(
            serverUrl = "https://example.test/emby",
            playbackInfo = playbackInfo
        )

        assertEquals(
            "https://example.test/emby/emya/video?media_id=m1&token=t1&line=l1&server=s1",
            resolved.mediaSources?.single()?.directStreamUrl
        )
    }

    @Test
    fun emyaDirectStreamUrlDropsInvalidLineLiteralBeforePlayback() {
        val playbackInfo = PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    directStreamUrl = "/emya/video?media_id=m1&token=t1&line=none&server=s1"
                )
            )
        )

        val resolved = invokePlaybackInfoUrls(
            serverUrl = "https://example.test/emby",
            playbackInfo = playbackInfo
        )

        val directStreamUrl = resolved.mediaSources?.single()?.directStreamUrl.orEmpty()
        assertEquals(
            "https://example.test/emby/emya/video?media_id=m1&token=t1&server=s1",
            directStreamUrl
        )
        assertFalse(directStreamUrl.contains("line=none"))
    }

    @Test
    fun emyaDirectStreamUrlPreservesNullLineLiteralRequiredByHillsReferencePlayer() {
        val playbackInfo = PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    directStreamUrl = "/emya/video?media_id=m1&token=t1&line=null&server=s1"
                )
            )
        )

        val resolved = invokePlaybackInfoUrls(
            serverUrl = "https://example.test/emby",
            playbackInfo = playbackInfo
        )

        assertEquals(
            "https://example.test/emby/emya/video?media_id=m1&token=t1&line=null&server=s1",
            resolved.mediaSources?.single()?.directStreamUrl
        )
    }

    @Test
    fun normalVideosDirectStreamUrlStillResolvesAtOriginForEmyaReadmeCompatibility() {
        val playbackInfo = PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = "source-1",
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    directStreamUrl = "/videos/uuid/original.strm"
                )
            )
        )

        val resolved = invokePlaybackInfoUrls(
            serverUrl = "https://example.test/emby",
            playbackInfo = playbackInfo
        )

        assertEquals(
            "https://example.test/videos/uuid/original.strm",
            resolved.mediaSources?.single()?.directStreamUrl
        )
    }

    @Test
    fun normalServerDirectStreamUrlIsNotTreatedAsProviderDirectMediaUrl() {
        assertFalse(invokeIsKnownProviderDirectMediaUrl("/Videos/item-1/direct.mkv"))
        assertFalse(invokeIsKnownProviderDirectMediaUrl("https://9club.test/emby/Videos/item-1/direct.mkv"))
        assertTrue(invokeIsKnownProviderDirectMediaUrl("/emya/video?media_id=m1&token=t1&line=l1&server=s1"))
        assertTrue(invokeIsKnownProviderDirectMediaUrl("/videos/uuid/original.strm"))
    }

    @Test
    fun emyaDirectStreamUrlOverridesConstructedStreamUrlWithoutGenericAuthHeaders() {
        val playbackInfo = PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = "source-1",
                    container = "mkv",
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    directStreamUrl = "/emya/video?media_id=m1&token=t1&line=l1&server=s1"
                )
            ),
            playSessionId = "play-session-1"
        )

        val request = invokeCreateLocalPlaybackRequest(
            serverUrl = "https://example.test/emby",
            itemId = "item-1",
            playbackInfo = playbackInfo
        )

        assertEquals(
            "https://example.test/emby/emya/video?media_id=m1&token=t1&line=l1&server=s1",
            request.url
        )
        assertFalse(request.url.contains("api_key="))
        assertFalse(request.requestHeaders.containsKey("Authorization"))
        assertFalse(request.requestHeaders.containsKey("X-Emby-Authorization"))
    }

    @Test
    fun apiKeyQueryParameterNameIsNotRedactedPlaceholder() {
        val holderClass = Class.forName("com.grmemby.data.model.PlaybackRequestKt")
        val field = holderClass.getDeclaredField("API_KEY_QUERY_PARAM")
        field.isAccessible = true

        assertEquals("api_key", field.get(null))
    }

    @Test
    fun emosKeepAliveFallbackPrefersNativeVmMediaIdFromProviderIds() {
        val requests = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "vl-library-item-id",
            mediaSource = MediaSource(
                id = "vl-media-source-id",
                path = "provider-line-a",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(
                id = "vl-library-item-id",
                providerIds = mapOf("vm" to "vm-native-resource-id")
            )
        )
        val urls = requests.map { request -> request.url }
        val firstEmya = urls.first { url -> url.contains("/emya/video") }

        assertTrue(firstEmya.startsWith("https://example.test/emby/emya/video?"))
        assertTrue(firstEmya.contains("media_id=vm-native-resource-id"))
        assertTrue(firstEmya.contains("token=provider-token"))
        assertTrue(firstEmya.contains("line=provider-line-a"))
        assertTrue(urls.indexOfFirst { it.contains("media_id=vm-native-resource-id") } < urls.indexOfFirst { it.contains("media_id=vl-library-item-id") })
    }

    @Test
    fun emosKeepAliveFallbackDoesNotManufactureGenericVideosOrDownloadSuccessUrls() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "vl-library-item-id",
            mediaSource = MediaSource(
                id = "vl-media-source-id",
                path = "provider-line-a",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(id = "vl-library-item-id")
        ).map { request -> request.url }

        assertFalse(urls.any { url -> url.contains("/Videos/vl-library-item-id/stream") })
        assertFalse(urls.any { url -> url.contains("/Items/vl-library-item-id/Download") })
        assertFalse(urls.any { url -> url.contains("/videos/vl-library-item-id/original.strm") })
        assertFalse(urls.any { url -> url.contains("api_key=") })
        assertTrue(urls.any { url -> url.contains("/emya/video") })
    }

    @Test
    fun emosKeepAliveFallbackUsesCatalogMediaSourceProviderHintsWhenPlaybackInfoOnlyHasGenericVideosRoute() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "ve-catalog-item",
            mediaSource = MediaSource(
                id = "generic-playback-source",
                path = "/Videos/ve-catalog-item/stream"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(
                id = "ve-catalog-item",
                mediaSources = listOf(
                    MediaSourceInfo(
                        id = "catalog-provider-source",
                        directStreamUrl = "/emya/video?media_id=provider-media-id&token=provider-token&line=l1&server=s1",
                        openToken = "provider-token"
                    )
                )
            )
        ).map { request -> request.url }

        assertTrue(urls.any { url -> url.startsWith("https://example.test/emby/emya/video?") })
        assertTrue(urls.any { url -> url.contains("media_id=provider-media-id") })
        assertTrue(urls.any { url -> url.contains("token=provider-token") })
        assertTrue(urls.any { url -> url.contains("server=s1") })
        assertFalse(urls.any { url -> url.contains("/Videos/ve-catalog-item/stream") })
        assertFalse(urls.any { url -> url.contains("api_key=") })
        assertTrue(urls.any { url -> url.contains("line=l1") })
    }

    @Test
    fun emosKeepAliveFallbackPreservesProviderTimeWhenTokenCameFromDirectStreamUrl() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "ve-catalog-item",
            mediaSource = MediaSource(
                id = "catalog-provider-source",
                directStreamUrl = "/emya/video?media_id=provider-media-id&token=provider-token&line=l1&time=1234567890&server=s1",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(id = "ve-catalog-item")
        ).map { request -> request.url }

        val firstGenerated = urls.first { url ->
            url.contains("/emya/video") &&
                url.contains("media_id=provider-media-id") &&
                url.contains("token=provider-token")
        }
        assertTrue(firstGenerated.contains("time=1234567890"))
        val preservedTimeIndex = urls.indexOfFirst { it.contains("time=1234567890") }
        val generatedCurrentTimeIndex = urls.indexOfFirst { url ->
            url.contains("/emya/video") && !url.contains("time=1234567890")
        }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        assertTrue(preservedTimeIndex in 0 until generatedCurrentTimeIndex)
    }

    @Test
    fun emosKeepAliveFallbackDropsInvalidLineLiteralCandidates() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "vl-library-item-id",
            mediaSource = MediaSource(
                id = "provider-media-id",
                directStreamUrl = "/emya/video?media_id=provider-media-id&token=provider-token&line=none&server=s1",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(id = "vl-library-item-id")
        ).map { request -> request.url }

        assertTrue(urls.any { url -> url.contains("/emya/video") })
        assertFalse(urls.any { url -> url.contains("line=none") })
        assertTrue(urls.any { url -> url.contains("server=s1") })
    }

    @Test
    fun emosKeepAliveFallbackPreservesNullLineLiteralRequiredByHillsReferencePlayer() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "vl-library-item-id",
            mediaSource = MediaSource(
                id = "provider-media-id",
                directStreamUrl = "/emya/video?media_id=provider-media-id&token=provider-token&line=null&server=s1",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(id = "vl-library-item-id")
        ).map { request -> request.url }

        assertTrue(urls.any { url -> url.contains("/emya/video") })
        assertTrue(urls.any { url -> url.contains("line=null") })
        assertTrue(urls.any { url -> url.contains("server=s1") })
    }

    @Test
    fun emosKeepAliveFallbackSynthesizesNullLineWhenProviderRouteOmitsLine() {
        val urls = EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = "https://example.test/emby",
            accessToken = "test",
            itemId = "vl-library-item-id",
            mediaSource = MediaSource(
                id = "provider-media-id",
                directStreamUrl = "/emya/video?media_id=provider-media-id&token=provider-token&server=s1",
                openToken = "provider-token"
            ),
            playSessionId = "play-session-1",
            item = BaseItemDto(id = "vl-library-item-id")
        ).map { request -> request.url }

        assertTrue(urls.any { url -> url.startsWith("https://example.test/emby/emya/video?") })
        assertTrue(urls.any { url -> url.contains("line=null") })
        assertTrue(urls.any { url -> url.contains("server=s1") })
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokePlaybackInfoUrls(
        serverUrl: String,
        playbackInfo: PlaybackInfoResponse
    ): PlaybackInfoResponse {
        val builderClass = Class.forName("com.grmemby.data.model.PlaybackUrlBuilder")
        val instance = builderClass.getField("INSTANCE").get(null)
        val method = builderClass.methods.single { method ->
            method.name.startsWith("playbackInfoUrls") && method.parameterTypes.size == 2
        }
        return method.invoke(instance, serverUrl, playbackInfo) as PlaybackInfoResponse
    }

    private fun invokeIsKnownProviderDirectMediaUrl(url: String): Boolean {
        val builderClass = Class.forName("com.grmemby.data.model.PlaybackUrlBuilder")
        val instance = builderClass.getField("INSTANCE").get(null)
        val method = builderClass.getDeclaredMethod("isKnownProviderDirectMediaUrl", String::class.java)
        method.isAccessible = true
        return method.invoke(instance, url) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeCreateLocalPlaybackRequest(
        serverUrl: String,
        itemId: String,
        playbackInfo: PlaybackInfoResponse,
        includeAccessToken: Boolean = false
    ): PlaybackRequest {
        val builderClass = Class.forName("com.grmemby.data.model.PlaybackUrlBuilder")
        val instance = builderClass.getField("INSTANCE").get(null)
        val method = builderClass.methods.single { method ->
            method.name.startsWith("createLocalPlaybackRequest") && method.parameterTypes.size == 4
        }
        val result = method.invoke(
            instance,
            createAuthContext(serverUrl),
            itemId,
            playbackInfo,
            createStreamOptions(includeAccessToken = includeAccessToken)
        )
        if (result is PlaybackRequest) {
            return result
        }
        val exception = result?.javaClass
            ?.declaredFields
            ?.firstOrNull { field -> field.name == "exception" }
            ?.apply { isAccessible = true }
            ?.get(result) as? Throwable
        throw AssertionError("Expected successful PlaybackRequest but got $result", exception)
    }

    private fun createAuthContext(serverUrl: String): Any {
        val authClass = Class.forName("com.grmemby.data.model.PlaybackAuthContext")
        val constructor = authClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.size == 5
        }
        constructor.isAccessible = true
        return constructor.newInstance(
            serverUrl,
            ServerType.EMBY,
            "access-token-1",
            "device-1",
            "1.0.0"
        )
    }

    private fun createStreamOptions(includeAccessToken: Boolean = false): Any {
        val optionsClass = Class.forName("com.grmemby.data.model.PlaybackStreamOptions")
        val constructor = optionsClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.size == 6
        }
        constructor.isAccessible = true
        return constructor.newInstance(
            null,
            null,
            null,
            null,
            AudioTranscodeMode.AUTO,
            includeAccessToken
        )
    }
}
