package com.grmemby.data.repository

import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.PlaybackRequest
import com.grmemby.data.network.trimTrailingSlash
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the low-frequency EMOS keepalive stream probe candidates.
 *
 * Keepalive must only succeed on a real provider media route. Do not manufacture
 * ordinary /Videos/{id}/stream or /Items/{id}/Download URLs here: those can make
 * an Emby-compatible server look active while the EMOS provider/dashboard never
 * receives the expected provider stream request.
 */
object EmosKeepAliveUrlCandidateBuilder {
    fun build(
        baseUrl: String,
        accessToken: String,
        itemId: String,
        mediaSource: MediaSource,
        playSessionId: String?,
        item: BaseItemDto? = null
    ): List<PlaybackRequest> {
        val urls = mutableListOf<String>()
        val sourceId = mediaSource.id?.usableProviderId()
        val itemMediaSources = item?.mediaSources.orEmpty()
        val rawHints = buildList {
            add(mediaSource.directStreamUrl)
            add(mediaSource.path)
            add(mediaSource.transcodingUrl)
            mediaSource.mediaStreams.orEmpty().forEach { stream ->
                add(stream.deliveryUrl)
                add(stream.path)
            }
            itemMediaSources.forEach { source ->
                add(source.directStreamUrl)
                add(source.path)
                add(source.transcodingUrl)
                source.mediaStreams.orEmpty().forEach { stream ->
                    add(stream.deliveryUrl)
                    add(stream.path)
                }
            }
            add(item?.path)
            item?.externalUrls.orEmpty().forEach { externalUrl -> add(externalUrl.url) }
            item?.mediaStreams.orEmpty().forEach { stream ->
                add(stream.deliveryUrl)
                add(stream.path)
            }
        }.mapNotNull { it?.trim()?.ifNotBlank() }

        fun addResolved(rawUrl: String?) {
            val resolved = resolveRawUrl(baseUrl, rawUrl) ?: return
            val route = routeTag(resolved)
            if (route == "emya") {
                urls += sanitizeEmosRouteUrl(resolved)
            }
        }

        rawHints.forEach(::addResolved)

        val providerIds = item?.providerIds.orEmpty()
        val explicitMediaIds = rawHints.queryParameterValues("media_id")
        val providerMediaPathHints = rawHints.mapNotNull(::extractProviderMediaIdHint)
        val providerMediaIds = (
            explicitMediaIds +
                providerIds.valuesForKeys(::isProviderMediaIdKey) +
                providerMediaPathHints +
                listOfNotNull(itemId.usableProviderId(), itemId.removePrefix("vl-").usableProviderId(), sourceId, sourceId?.removePrefix("vl-")?.usableProviderId())
            )
            .map { it.trim() }
            .filter { it.usableProviderId() != null }
            .distinct()
            .take(8)

        val providerTokens = (
            rawHints.queryParameterValues("token") +
                providerIds.valuesForKeys(::isProviderTokenKey) +
                itemMediaSources.flatMap { source -> listOfNotNull(source.openToken, source.liveStreamId) } +
                listOfNotNull(mediaSource.openToken, mediaSource.liveStreamId)
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)

        val providerPathHints = rawHints.mapNotNull(::extractProviderLine)
        val providerServers = (
            rawHints.queryParameterValues("server") +
                providerPathHints +
                providerIds.valuesForKeys(::isProviderServerKey) +
                itemMediaSources.mapNotNull { source -> source.liveStreamId } +
                listOfNotNull(mediaSource.liveStreamId)
            )
            .map { it.trim() }
            .filter { it.usableOptionalProviderParam() != null }
            .distinct()
            .take(3)

        // Hills/reference-player runtime traces show EMOS succeeds through the API
        // base path (/emby/emya/video) and includes both line and server parameters.
        // Preserve a provider-supplied line when present instead of dropping it.
        val providerLines: List<String?> = (
            rawHints.queryParameterValues("line") +
                providerIds.valuesForKeys(::isProviderLineKey) +
                providerPathHints
            )
            .map { it.trim() }
            .filter { it.usableProviderLineParam() != null }
            .distinct()
            .take(3)
            // Hills/reference-player traces show EMOS expects the line query name to be
            // present even when the value is the literal string "null". Some keepalive
            // PlaybackInfo media sources expose /emya/video without line, so synthesize
            // the reference-compatible sentinel rather than omitting the parameter and
            // letting the provider route return 422.
            .ifEmpty { listOf("null") }

        val providerTimeCandidates = (
            rawHints.queryParameterValues("time") +
                listOf(System.currentTimeMillis().toString())
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
        val providerMediaIdParamNames = listOf("media_id")
        val providerTokenParamNames = listOf("token")
        providerMediaIds.forEach { mediaId ->
            providerTokens.forEach { providerToken ->
                providerTimeCandidates.forEach { providerTime ->
                    providerMediaIdParamNames.forEach { mediaIdParamName ->
                        providerTokenParamNames.forEach { tokenParamName ->
                            providerLines.forEach { providerLine ->
                                providerServers.forEach { providerServer ->
                                    buildApiBaseUrl(
                                        baseUrl = baseUrl,
                                        encodedPath = "emya/video",
                                        queryParams = listOf(
                                            mediaIdParamName to mediaId,
                                            tokenParamName to providerToken,
                                            "line" to providerLine,
                                            "play_session_id" to playSessionId,
                                            "time" to providerTime,
                                            "server" to providerServer
                                        )
                                    )?.let { urls += it }
                                }
                                buildApiBaseUrl(
                                    baseUrl = baseUrl,
                                    encodedPath = "emya/video",
                                    queryParams = listOf(
                                        mediaIdParamName to mediaId,
                                        tokenParamName to providerToken,
                                        "line" to providerLine,
                                        "play_session_id" to playSessionId,
                                        "time" to providerTime
                                    )
                                )?.let { urls += it }
                            }
                        }
                    }
                }
            }
        }

        return urls
            .filter { url -> url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) }
            .distinct()
            .map { url -> PlaybackRequest(url = url, requestHeaders = emptyMap()) }
    }

    private fun Map<String, String>.valuesForKeys(predicate: (String) -> Boolean): List<String> {
        return entries.mapNotNull { (key, value) -> value.takeIf { predicate(key) }?.usableProviderId() }
    }

    private fun List<String>.queryParameterValues(name: String): List<String> {
        return mapNotNull { raw -> queryParameterValue(raw, name) }
    }

    private fun queryParameterValue(rawUrlOrPath: String, name: String): String? {
        val query = runCatching { URI.create(rawUrlOrPath).rawQuery }.getOrNull()
            ?: rawUrlOrPath.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotBlank() }
            ?: return null
        return query.split('&')
            .firstOrNull { part -> part.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractProviderLine(rawUrlOrPath: String): String? {
        val explicitLine = queryParameterValue(rawUrlOrPath, "line")?.ifNotBlank()
        if (!explicitLine.isNullOrBlank()) return explicitLine
        return extractShortProviderPath(rawUrlOrPath)
    }

    private fun extractProviderMediaIdHint(rawUrlOrPath: String): String? {
        return extractShortProviderPath(rawUrlOrPath)
    }

    private fun extractShortProviderPath(rawUrlOrPath: String): String? {
        if (rawUrlOrPath.startsWith("http://", ignoreCase = true) || rawUrlOrPath.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val path = rawUrlOrPath.substringBefore('?').trim('/').ifNotBlank() ?: return null
        val lowerPath = path.lowercase()
        if (lowerPath == "emya/video" || lowerPath.endsWith("/emya/video")) return null
        if (lowerPath.startsWith("videos/") || lowerPath.startsWith("items/")) return null
        if (lowerPath in setOf("movie", "movies", "tv", "series", "shows", "show", "folder", "folders", "collection", "collections")) return null
        if (lowerPath.startsWith("movie/") || lowerPath.startsWith("movies/") || lowerPath.startsWith("tv/") || lowerPath.startsWith("series/") || lowerPath.startsWith("shows/")) return null
        return path
    }

    private fun isProviderMediaIdKey(key: String): Boolean {
        val lower = key.lowercase()
        return listOf("vm", "emos", "emya", "media", "video", "source", "resource", "vod").any(lower::contains)
    }

    private fun isProviderTokenKey(key: String): Boolean {
        val lower = key.lowercase()
        return listOf("token", "auth", "sign", "signature", "key").any(lower::contains)
    }

    private fun isProviderServerKey(key: String): Boolean {
        val lower = key.lowercase()
        return listOf("server", "host", "source", "line").any(lower::contains)
    }

    private fun isProviderLineKey(key: String): Boolean {
        val lower = key.lowercase()
        return listOf("line", "server", "source").any(lower::contains)
    }

    private fun resolveRawUrl(baseUrl: String, rawUrl: String?): String? {
        val raw = rawUrl?.ifNotBlank() ?: return null
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val path = raw.substringBefore('?').lowercase()
        return if ((path == "/emya/video" || path.endsWith("/emya/video")) && raw.startsWith('/')) {
            buildApiBaseResolvedRawUrl(baseUrl = baseUrl, rawUrl = raw)
        } else {
            runCatching {
                URI.create(trimTrailingSlash(baseUrl, trailingSlash = true))
                    .resolve(raw)
                    .toString()
            }.getOrNull()
        }
    }

    private fun routeTag(url: String): String {
        val path = runCatching { URI.create(url).rawPath.orEmpty().lowercase() }.getOrDefault("")
        return when {
            "/emya/" in path || path.endsWith("/emya/video") -> "emya"
            "/videos/" in path -> "videos"
            else -> "stream"
        }
    }

    private fun isOriginalStrmRoute(url: String): Boolean {
        val path = runCatching { URI.create(url).rawPath.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/videos/") && path.endsWith("/original.strm")
    }

    private fun sanitizeEmosRouteUrl(url: String): String {
        val withoutFragment = url.substringBefore('#')
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
            .takeIf { '#' in url }
            ?.let { "#$it" }
            .orEmpty()
        val pathAndOrigin = withoutFragment.substringBefore('?')
        val rawQuery = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        if (rawQuery.isBlank()) return url
        val filteredQuery = rawQuery
            .split('&')
            .filter { part -> !isInvalidOptionalProviderQueryPart(part) }
            .joinToString("&")
        return if (filteredQuery.isBlank()) pathAndOrigin + fragment else "$pathAndOrigin?$filteredQuery$fragment"
    }

    private fun isInvalidOptionalProviderQueryPart(part: String): Boolean {
        val name = part.substringBefore('=', missingDelimiterValue = "").lowercase()
        if (name == "api_key" || name == "vm") return true
        if (name != "server" && name != "line") return false
        val value = part.substringAfter('=', missingDelimiterValue = "")
        return if (name == "line") {
            value.usableProviderLineParam() == null
        } else {
            value.usableOptionalProviderParam() == null
        }
    }

    private fun buildApiBaseUrl(
        baseUrl: String,
        encodedPath: String,
        queryParams: List<Pair<String, String?>>
    ): String? {
        val uri = runCatching { URI.create(baseUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.ifNotBlank() ?: return null
        val authority = uri.rawAuthority?.ifNotBlank() ?: return null
        val basePath = uri.rawPath.orEmpty().trim('/').ifNotBlank()
        val path = listOfNotNull(basePath, encodedPath.trim('/').ifNotBlank()).joinToString("/")
        return buildUrl(
            baseUrl = "$scheme://$authority",
            encodedPath = path,
            queryParams = queryParams
        )
    }

    private fun buildApiBaseResolvedRawUrl(baseUrl: String, rawUrl: String): String? {
        val uri = runCatching { URI.create(baseUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.ifNotBlank() ?: return null
        val authority = uri.rawAuthority?.ifNotBlank() ?: return null
        val basePath = uri.rawPath.orEmpty().trim('/').ifNotBlank()
        val rawPathAndQuery = rawUrl.trimStart('/')
        val rawPath = rawPathAndQuery.substringBefore('?').trim('/')
        val prefixed = if (!basePath.isNullOrBlank() && !rawPath.equals(basePath, ignoreCase = true) && !rawPath.startsWith("$basePath/", ignoreCase = true)) {
            "$basePath/$rawPathAndQuery"
        } else {
            rawPathAndQuery
        }
        return "$scheme://$authority/$prefixed"
    }

    private fun appendApiKeyIfMissing(url: String, accessToken: String): String {
        if (accessToken.isBlank()) return url
        val query = runCatching { URI.create(url).rawQuery }.getOrNull()
        val hasApiKey = query
            ?.split('&')
            ?.any { part -> part.substringBefore('=').equals("api_key", ignoreCase = true) }
            ?: false
        if (hasApiKey) return url
        val separator = if ('?' in url) '&' else '?'
        return "$url$separator${encode("api_key")}=${encode(accessToken)}"
    }

    private fun buildUrl(
        baseUrl: String,
        encodedPath: String,
        queryParams: List<Pair<String, String?>>
    ): String {
        val query = queryParams
            .mapNotNull { (key, value) -> value?.usableQueryValue(key)?.let { encode(key) to encode(it) } }
            .joinToString("&") { (key, value) -> "$key=$value" }
        val url = "${trimTrailingSlash(baseUrl)}/${encodedPath.trimStart('/')}"
        return if (query.isBlank()) url else "$url?$query"
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private fun String.ifNotBlank(): String? = takeIf { it.isNotBlank() }

    private fun String.usableQueryValue(key: String): String? {
        val trimmed = trim().ifNotBlank() ?: return null
        return when {
            key.equals("line", ignoreCase = true) -> trimmed.usableProviderLineParam()
            key.equals("server", ignoreCase = true) -> trimmed.usableOptionalProviderParam()
            else -> trimmed
        }
    }

    private fun String?.usableProviderLineParam(): String? {
        val normalized = this?.trim() ?: return null
        val lower = normalized.lowercase()
        return normalized.takeIf {
            // EMOS/Hills sends line=null intentionally; keep the parameter present.
            it.isNotBlank() && lower != "none" && lower != "undefined" && lower != "invalid" && lower != "0"
        }
    }

    private fun String?.usableOptionalProviderParam(): String? {
        val normalized = this?.trim() ?: return null
        val lower = normalized.lowercase()
        return normalized.takeIf {
            it.isNotBlank() && lower != "none" && lower != "null" && lower != "undefined" && lower != "invalid" && lower != "0"
        }
    }

    private fun String?.usableProviderId(): String? {
        val normalized = this?.trim() ?: return null
        val lower = normalized.lowercase()
        return normalized.takeIf {
            it.isNotBlank() && lower != "none" && lower != "null" && lower != "undefined" && lower != "0"
        }
    }
}
