package com.grmemby.winplayer

import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.PlaybackInfoResponse
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Pure URL/header selection for the Windows player. Keeps playback auth in the URL when possible. */
object PlaybackUrlResolver {
    fun resolve(
        baseUrl: String,
        itemId: String,
        accessToken: String,
        playbackInfo: PlaybackInfoResponse
    ): ResolvedPlayback {
        val source = playbackInfo.mediaSources
            ?.firstOrNull { it.supportsDirectPlay == true || !it.directStreamUrl.isNullOrBlank() }
            ?: playbackInfo.mediaSources?.firstOrNull()
            ?: error("PlaybackInfo has no media source")

        val candidateUrl = listOfNotNull(
            source.directStreamUrl?.takeIf { it.isNotBlank() },
            source.transcodingUrl?.takeIf { it.isNotBlank() },
            source.path?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        ).firstOrNull()

        val url = if (candidateUrl != null) {
            ensureApiKeyIfMissing(absoluteUrl(baseUrl, candidateUrl), accessToken)
        } else {
            buildFallbackStreamUrl(baseUrl, itemId, source.id, accessToken)
        }

        return ResolvedPlayback(
            url = url,
            mediaSourceId = source.id,
            playSessionId = playbackInfo.playSessionId,
            requiredHeaders = safeRequiredHeaders(source),
            displayTitle = source.name ?: source.container ?: itemId
        )
    }

    internal fun buildFallbackStreamUrl(
        baseUrl: String,
        itemId: String,
        mediaSourceId: String?,
        accessToken: String
    ): String {
        val params = buildList {
            add("static" to "true")
            mediaSourceId?.takeIf { it.isNotBlank() }?.let { add("mediaSourceId" to it) }
            add("api_key" to accessToken)
        }.joinToString("&") { (name, value) -> "${name.urlEncode()}=${value.urlEncode()}" }
        return "${baseUrl.trimEnd('/')}/Videos/${itemId.urlEncode()}/stream?$params"
    }

    internal fun absoluteUrl(baseUrl: String, url: String): String = when {
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("/") -> baseUrl.trimEnd('/') + url
        else -> baseUrl.trimEnd('/') + "/" + url
    }

    internal fun ensureApiKeyIfMissing(url: String, accessToken: String): String {
        if (url.contains("api_key=", ignoreCase = true) || accessToken.isBlank()) return url
        return try {
            val uri = URI(url)
            if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                val separator = if (uri.rawQuery.isNullOrBlank()) "?" else "&"
                url + separator + "api_key=" + accessToken.urlEncode()
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun safeRequiredHeaders(source: MediaSource): Map<String, String> = source.requiredHttpHeaders
        .orEmpty()
        .mapKeys { it.key.trim() }
        .mapValues { it.value.trim() }
        .filterKeys { it.isNotBlank() }
        .filterValues { it.isNotBlank() && '\n' !in it && '\r' !in it }
        .filterKeys { key ->
            key.equals("Authorization", ignoreCase = true).not() &&
                key.equals("X-Emby-Authorization", ignoreCase = true).not() &&
                key.equals("X-MediaBrowser-Token", ignoreCase = true).not()
        }
}

data class ResolvedPlayback(
    val url: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val requiredHeaders: Map<String, String>,
    val displayTitle: String
)

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
