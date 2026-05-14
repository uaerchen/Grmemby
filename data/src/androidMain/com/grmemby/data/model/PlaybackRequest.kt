package com.grmemby.data.model

import android.net.Uri
import android.util.Log
import com.grmemby.data.network.ServerType
import com.grmemby.data.network.trimTrailingSlash
import com.grmemby.data.util.buildServerUrl
import com.grmemby.data.util.getServerUrl
import com.grmemby.data.util.removeQueryParameter
import java.net.URI

private const val API_KEY_QUERY_PARAM = "api_key"

data class PlaybackRequest(
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap()
) {
    fun authorizeRelatedUrl(relatedUrl: String): String {
        val apiKey = url.apiKey() ?: return relatedUrl
        if (relatedUrl.apiKey() != null) return relatedUrl
        return Uri.parse(relatedUrl).buildUpon()
            .appendQueryParameter(API_KEY_QUERY_PARAM, apiKey)
            .build()
            .toString()
    }
}

private fun String.apiKey(): String? {
    val uri = runCatching { URI.create(this) }.getOrNull() ?: return null
    return uri.rawQuery
        ?.split('&')
        ?.firstOrNull { queryPart -> queryPart.substringBefore('=').equals(API_KEY_QUERY_PARAM, ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
}

internal data class PlaybackAuthContext(
    val serverUrl: String,
    val serverType: ServerType?,
    val accessToken: String?,
    val deviceId: String,
    val clientVersion: String
)

internal data class PlaybackStreamOptions(
    val maxStreamingBitrate: Int? = null,
    val maxStreamingHeight: Int? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
    val includeAccessToken: Boolean = false
)

internal object PlaybackUrlBuilder {
    fun playbackInfoUrls(
        serverUrl: String,
        playbackInfo: PlaybackInfoResponse
    ): PlaybackInfoResponse {
        return playbackInfo.copy(
            mediaSources = playbackInfo.mediaSources?.map { mediaSource ->
                mediaSource.copy(
                    directStreamUrl = finalUrl(
                        baseUrl = serverUrl,
                        url = mediaSource.directStreamUrl
                    ) ?: mediaSource.directStreamUrl,
                    transcodingUrl = finalUrl(
                        baseUrl = serverUrl,
                        url = mediaSource.transcodingUrl
                    ) ?: mediaSource.transcodingUrl,
                    mediaStreams = mediaSource.mediaStreams?.map { mediaStream ->
                        mediaStream.copy(
                            deliveryUrl = resolvePlaybackUrl(
                                serverUrl = serverUrl,
                                url = mediaStream.deliveryUrl
                            ),
                            path = finalUrl(
                                baseUrl = serverUrl,
                                url = mediaStream.path
                            )
                        )
                    },
                    mediaAttachments = mediaSource.mediaAttachments?.map { mediaAttachment ->
                        mediaAttachment.copy(
                            deliveryUrl = resolvePlaybackUrl(
                                serverUrl = serverUrl,
                                url = mediaAttachment.deliveryUrl
                            )
                        )
                    }
                )
            }
        )
    }

    fun createLocalPlaybackRequest(
        authContext: PlaybackAuthContext,
        itemId: String,
        playbackInfo: PlaybackInfoResponse,
        options: PlaybackStreamOptions
    ): Result<PlaybackRequest> {
        return buildStreamingUrl(
            authContext = authContext,
            itemId = itemId,
            playbackInfo = playbackInfo,
            options = options
        ).mapCatching { streamingUrl ->
            val headers = buildPlaybackRequestHeaders(
                authContext = authContext,
                mediaSource = playbackInfo.mediaSources?.firstOrNull(),
                playbackUrl = streamingUrl
            )
            logPlaybackRequestShape(
                streamingUrl = streamingUrl,
                mediaSource = playbackInfo.mediaSources?.firstOrNull(),
                headers = headers
            )
            PlaybackRequest(
                url = streamingUrl,
                requestHeaders = headers
            )
        }
    }

    fun createCastStreamingUrl(
        authContext: PlaybackAuthContext,
        itemId: String,
        playbackInfo: PlaybackInfoResponse,
        options: PlaybackStreamOptions
    ): Result<String> {
        return buildStreamingUrl(
            authContext = authContext,
            itemId = itemId,
            playbackInfo = playbackInfo,
            options = options.copy(includeAccessToken = true)
        )
    }

    private fun buildPlaybackRequestHeaders(
        authContext: PlaybackAuthContext,
        mediaSource: MediaSource? = null,
        playbackUrl: String? = null
    ): Map<String, String> {
        val requiredHeaders = mediaSource?.requiredHttpHeaders.orEmpty()
        if (playbackUrl?.apiKey() != null) {
            return requiredHeaders
        }
        val shouldSendServerAuth = (playbackUrl == null || sameOrigin(authContext.serverUrl, playbackUrl)) &&
            !isEmosDirectMediaUrl(playbackUrl)
        if (authContext.accessToken.isNullOrBlank() || !shouldSendServerAuth) {
            return requiredHeaders
        }

        val authHeader = AuthHeaderDto.fromServerType(
            serverType = authContext.serverType,
            deviceId = authContext.deviceId,
            version = authContext.clientVersion,
            accessToken = authContext.accessToken
        ).asHeaderValue()

        return mapOf(
            "X-Emby-Authorization" to authHeader
        ) + requiredHeaders
    }

    private fun buildStreamingUrl(
        authContext: PlaybackAuthContext,
        itemId: String,
        playbackInfo: PlaybackInfoResponse,
        options: PlaybackStreamOptions
    ): Result<String> {
        return try {
            val mediaSource = playbackInfo.mediaSources?.firstOrNull()
                ?: return Result.failure(Exception("No media source available"))
            val normalizedSubtitleStreamIndex = normalizeSubtitleStreamIndex(options.subtitleStreamIndex)
            val selectedAudioStream = getSelectedAudioStream(
                mediaSource = mediaSource,
                requestedAudioStreamIndex = options.audioStreamIndex
            )
            val hasQualityCap = (options.maxStreamingBitrate ?: 0) > 0 || (options.maxStreamingHeight ?: 0) > 0
            val needsAudioTranscoding = needsAudioTranscode(
                audioTranscodeMode = options.audioTranscodeMode,
                selectedAudioStream = selectedAudioStream
            )

            val serverTranscodingUrl = !mediaSource.transcodingUrl.isNullOrBlank() &&
                (
                    hasQualityCap ||
                        needsAudioTranscoding ||
                        (mediaSource.supportsDirectPlay != true &&
                            mediaSource.supportsDirectStream != true)
                )
            if (serverTranscodingUrl) {
                val resolvedTranscodingUrl = getServerUrl(
                    baseUrl = authContext.serverUrl,
                    url = mediaSource.transcodingUrl
                )
                if (!resolvedTranscodingUrl.isNullOrBlank()) {
                    val selectedTranscodingUrl = if (authContext.serverType == ServerType.JELLYFIN) {
                        resolvedTranscodingUrl
                    } else {
                        applyTranscodingSelectionOverrides(
                            streamingUrl = resolvedTranscodingUrl,
                            audioStreamIndex = options.audioStreamIndex,
                            audioTranscodeMode = options.audioTranscodeMode,
                            sourceVideoBitrate = mediaSource.bitrate,
                            preserveOriginalVideo = !hasQualityCap && needsAudioTranscoding
                        )
                    }
                    return Result.success(
                        appendToken(
                            url = selectedTranscodingUrl,
                            authContext = authContext,
                            options = options
                        )
                    )
                }
            }

            if (!hasQualityCap && !needsAudioTranscoding) {
                preferredProviderDirectMediaUrl(
                    serverUrl = authContext.serverUrl,
                    mediaSource = mediaSource
                )?.let { directUrl ->
                    return Result.success(
                        appendToken(
                            url = directUrl,
                            authContext = authContext,
                            options = options
                        )
                    )
                }

            }

            val streamQueryParams = mutableListOf<Pair<String, String?>>()
            streamQueryParams.add("mediaSourceId" to mediaSource.id)
            options.audioStreamIndex?.let { streamQueryParams.add("audioStreamIndex" to it.toString()) }
            normalizeSubtitleStreamIndex(options.subtitleStreamIndex)?.let {
                streamQueryParams.add("subtitleStreamIndex" to it.toString())
            }
            streamQueryParams.add("PlaySessionId" to playbackInfo.playSessionId)
            streamQueryParams.add("DeviceId" to authContext.deviceId)
            if (options.includeAccessToken) {
                authContext.accessToken?.takeIf { it.isNotBlank() }?.let { accessToken ->
                    streamQueryParams.add(API_KEY_QUERY_PARAM to accessToken)
                }
            }

            if (hasQualityCap) {
                return Result.failure(
                    Exception("Negotiated transcoding URL not available for item $itemId")
                )
            }

            val streamingUrl = buildStreamUrl(
                serverUrl = authContext.serverUrl,
                itemId = itemId,
                queryParams = streamQueryParams,
                useStaticStream = mediaSource.supportsDirectPlay == true
            )

            Result.success(streamingUrl)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun normalizeSubtitleStreamIndex(subtitleStreamIndex: Int?): Int? {
        return subtitleStreamIndex?.takeIf { it >= 0 }
    }

    private fun resolvePlaybackUrl(
        serverUrl: String,
        url: String?
    ): String? {
        return finalUrl(serverUrl, url)?.let { parsedUrl ->
            removeQueryParameter(parsedUrl, API_KEY_QUERY_PARAM)
        }
    }

    private fun finalUrl(baseUrl: String, url: String?): String? {
        val resolved = resolveEmosDirectMediaUrl(baseUrl, url)
            ?: getServerUrl(baseUrl = baseUrl, url = url)
            ?: url
        return sanitizeKnownProviderDirectMediaUrl(resolved)
    }

    private fun resolveEmosDirectMediaUrl(baseUrl: String, url: String?): String? {
        val rawUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val uri = parseUriOrNull(rawUrl) ?: return null
        if (uri.isAbsolute || !uri.host.isNullOrBlank()) return null
        if (!isEmosDirectMediaUrl(rawUrl)) return null
        return buildApiBaseResolvedUrl(baseUrl = baseUrl, rawUrl = rawUrl)
    }

    private fun buildApiBaseResolvedUrl(baseUrl: String, rawUrl: String): String? {
        val baseUri = parseUriOrNull(baseUrl) ?: return null
        val scheme = baseUri.scheme?.takeIf { it.isNotBlank() } ?: return null
        val authority = baseUri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
        val basePath = baseUri.rawPath.orEmpty().trim('/').takeIf { it.isNotBlank() }
        val rawPathAndQuery = rawUrl.trimStart('/')
        val rawPath = rawPathAndQuery.substringBefore('?').trim('/')
        val prefixed = if (!basePath.isNullOrBlank() && !rawPath.equals(basePath, ignoreCase = true) && !rawPath.startsWith("$basePath/", ignoreCase = true)) {
            "$basePath/$rawPathAndQuery"
        } else {
            rawPathAndQuery
        }
        return "$scheme://$authority/$prefixed"
    }

    private fun appendToken(
        url: String,
        authContext: PlaybackAuthContext,
        options: PlaybackStreamOptions
    ): String {
        if (!options.includeAccessToken) return url
        if (!sameOrigin(authContext.serverUrl, url)) return url
        if (isEmosDirectMediaUrl(url)) return url
        val apiKey = authContext.accessToken?.takeIf { it.isNotBlank() } ?: return url
        if (url.apiKey() != null) return url
        return Uri.parse(url).buildUpon()
            .appendQueryParameter(API_KEY_QUERY_PARAM, apiKey)
            .build()
            .toString()
    }

    private fun preferredProviderDirectMediaUrl(
        serverUrl: String,
        mediaSource: MediaSource
    ): String? {
        resolveKnownProviderDirectPlaybackUrl(
            serverUrl = serverUrl,
            url = mediaSource.directStreamUrl
        )?.let { return it }

        return resolveKnownProviderDirectPlaybackUrl(
            serverUrl = serverUrl,
            url = mediaSource.path
        )
    }

    private fun resolveKnownProviderDirectPlaybackUrl(serverUrl: String, url: String?): String? {
        val rawUrl = url?.takeIf { it.isNotBlank() } ?: return null
        if (!isKnownProviderDirectMediaUrl(rawUrl)) return null
        val resolvedUrl = finalUrl(baseUrl = serverUrl, url = rawUrl) ?: rawUrl
        return resolvedUrl.takeIf(::isHttpUrl)
    }

    private fun sanitizeKnownProviderDirectMediaUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        if (!isEmosDirectMediaUrl(url)) return url
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
            .filter { part -> !isInvalidEmosQueryPart(part) }
            .joinToString("&")
        return if (filteredQuery.isBlank()) {
            pathAndOrigin + fragment
        } else {
            "$pathAndOrigin?$filteredQuery$fragment"
        }
    }

    private fun isInvalidEmosQueryPart(part: String): Boolean {
        val rawName = part.substringBefore('=', missingDelimiterValue = "")
        val name = decodeQueryComponent(rawName).lowercase()
        if (name !in setOf("line", "server")) return false
        val rawValue = part.substringAfter('=', missingDelimiterValue = "")
        val value = decodeQueryComponent(rawValue).trim().lowercase()
        return when (name) {
            // Hills/reference-player traces show EMOS requires the query name to be
            // present and sends line=null as a real provider value. Dropping it makes
            // /emya/video fail with 422 even when media_id/token/server are correct.
            "line" -> value.isBlank() || value in setOf("none", "undefined", "invalid", "0")
            else -> value.isBlank() || value in setOf("null", "none", "undefined", "invalid", "0")
        }
    }

    private fun decodeQueryComponent(value: String): String {
        return runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }

    private fun isKnownProviderDirectMediaUrl(url: String?): Boolean {
        return isEmosDirectMediaUrl(url) || isOriginalStrmDirectMediaUrl(url)
    }

    private fun logPlaybackRequestShape(
        streamingUrl: String,
        mediaSource: MediaSource?,
        headers: Map<String, String>
    ) {
        val uri = parseUriOrNull(streamingUrl) ?: return
        val path = uri.rawPath.orEmpty().lowercase()
        val route = when {
            "/emya/" in path -> "emya"
            "/videos/" in path -> "videos"
            "/items/" in path && "/download" in path -> "download"
            "transcode" in path || "transcoding" in path -> "transcode"
            else -> "stream"
        }
        val params = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part -> part.substringBefore('=', missingDelimiterValue = "").takeIf { it.isNotBlank() } }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        runCatching {
            Log.i(
                "PlaybackTrace",
                "request route=$route path=${redactedPlaybackPath(uri.rawPath.orEmpty())} params=$params ${emyaParamValueShape(uri, mediaSource)} headerNames=${headers.keys.sorted()} sourceIdHash=${mediaSource?.id?.hashCode() ?: 0} directShape=${redactedUrlShape(mediaSource?.directStreamUrl)} pathShape=${redactedUrlShape(mediaSource?.path)} transcodeShape=${redactedUrlShape(mediaSource?.transcodingUrl)}"
            )
        }
    }

    private fun redactedUrlShape(url: String?): String {
        val uri = url?.takeIf { it.isNotBlank() }?.let(::parseUriOrNull) ?: return "none"
        val params = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part -> part.substringBefore('=', missingDelimiterValue = "").takeIf { it.isNotBlank() } }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        return "path=${redactedPlaybackPath(uri.rawPath.orEmpty())};params=$params;absolute=${uri.isAbsolute}"
    }

    private fun emyaParamValueShape(uri: URI, mediaSource: MediaSource?): String {
        val path = uri.rawPath.orEmpty().lowercase()
        if (!(path == "/emya/video" || path.endsWith("/emya/video"))) return ""
        val sourcePathId = mediaSource?.path
            ?.substringBefore('?')
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
        val sourceId = mediaSource?.id?.takeIf { it.isNotBlank() }
        fun value(name: String): String? = uri.rawQuery
            ?.split('&')
            ?.firstOrNull { part -> part.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        fun kind(raw: String?): String {
            val decoded = raw?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
                ?: return "blank"
            return when {
                sourcePathId != null && decoded == sourcePathId -> "sourcePath"
                sourceId != null && decoded == sourceId -> "sourceId"
                decoded == "null" || decoded == "none" || decoded == "undefined" -> "invalidLiteral"
                decoded.startsWith("/") -> "path${decoded.length}"
                else -> "op${decoded.length}"
            }
        }
        val mediaId = value("media_id")
        val vm = value("vm")
        val token = value("token")
        val apiKey = value("api_key")
        val line = value("line")
        val server = value("server")
        val mediaRef = mediaId ?: vm
        return "ev{tok=${kind(token)},ak=${kind(apiKey)},mi=${kind(mediaId)},vm=${kind(vm)},ln=${kind(line)},sv=${kind(server)},le=${!line.isNullOrBlank() && line == mediaRef},se=${!server.isNullOrBlank() && server == mediaRef}}"
    }

    private fun redactedPlaybackPath(path: String): String {
        val normalized = path.ifBlank { "/" }
        return when {
            normalized.lowercase().endsWith("/emya/video") -> normalized
            normalized.lowercase().contains("/videos/") -> normalized.replace(Regex("(?i)(/videos/)[^/]+"), "$1[REDACTED]")
            normalized.lowercase().contains("/items/") -> normalized.replace(Regex("(?i)(/items/)[^/]+"), "$1[REDACTED]")
            else -> normalized
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val uri = parseUriOrNull(url) ?: return false
        return uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
    }

    private fun isEmosDirectMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = parseUriOrNull(url) ?: return false
        val path = uri.rawPath.orEmpty().lowercase()
        return (path == "/emya/video" || path.endsWith("/emya/video")) &&
            uri.hasQueryParameter("media_id") &&
            uri.hasQueryParameter("token")
    }

    private fun isOriginalStrmDirectMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = parseUriOrNull(url) ?: return false
        val path = uri.rawPath.orEmpty().lowercase()
        return path.startsWith("/videos/") && path.endsWith("/original.strm")
    }

    private fun parseUriOrNull(url: String): URI? {
        return runCatching { URI.create(url) }.getOrNull()
    }

    private fun URI.hasQueryParameter(name: String): Boolean {
        return rawQuery
            ?.split('&')
            ?.any { queryPart -> queryPart.substringBefore('=').equals(name, ignoreCase = true) }
            ?: false
    }

    private fun sameOrigin(serverUrl: String, playbackUrl: String): Boolean {
        val serverUri = parseUriOrNull(serverUrl) ?: return true
        val playbackUri = parseUriOrNull(playbackUrl) ?: return true
        if (playbackUri.scheme.isNullOrBlank() || playbackUri.host.isNullOrBlank()) return true
        return serverUri.scheme.equals(playbackUri.scheme, ignoreCase = true) &&
            serverUri.host.equals(playbackUri.host, ignoreCase = true) &&
            effectivePort(serverUri) == effectivePort(playbackUri)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun buildStreamUrl(
        serverUrl: String,
        itemId: String,
        queryParams: List<Pair<String, String?>>,
        useStaticStream: Boolean
    ): String {
        val finalQueryParams = if (useStaticStream) {
            buildList {
                add("static" to "true")
                addAll(queryParams)
            }
        } else {
            queryParams
        }
        return buildServerUrl(
            baseUrl = serverUrl,
            encodedPath = "Videos/$itemId/stream",
            queryParams = finalQueryParams
        )
    }

    private fun getSelectedAudioStream(
        mediaSource: MediaSource,
        requestedAudioStreamIndex: Int?
    ): MediaStream? {
        val audioStreams = mediaSource.mediaStreams
            ?.filter { stream -> stream.type.equals("Audio", ignoreCase = true) }
            .orEmpty()
        if (audioStreams.isEmpty()) {
            return null
        }

        val targetIndex = requestedAudioStreamIndex
            ?: mediaSource.defaultAudioStreamIndex
            ?: audioStreams.firstOrNull { it.isDefault == true }?.index

        return audioStreams.firstOrNull { stream -> stream.index == targetIndex }
            ?: audioStreams.first()
    }

    private fun needsAudioTranscode(
        audioTranscodeMode: AudioTranscodeMode,
        selectedAudioStream: MediaStream?
    ): Boolean {
        val codec = selectedAudioStream?.codec?.lowercase()
        return when (audioTranscodeMode) {
            AudioTranscodeMode.STEREO -> {
                val channels = selectedAudioStream?.channels ?: 2
                channels > 2 || codec !in setOf("aac", "mp3", "opus", "vorbis", "pcm")
            }
            AudioTranscodeMode.SURROUND_5_1 -> codec != "eac3"
            else -> false
        }
    }

    private fun applyTranscodingSelectionOverrides(
        streamingUrl: String,
        audioStreamIndex: Int?,
        audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
        sourceVideoBitrate: Int? = null,
        preserveOriginalVideo: Boolean = false
    ): String {
        val sourceUri = Uri.parse(streamingUrl)
        val builder = sourceUri.buildUpon().clearQuery()
        val overrideParams = linkedMapOf<String, String>()

        audioStreamIndex?.let {
            overrideParams["AudioStreamIndex"] = it.toString()
        }

        if (preserveOriginalVideo) {
            overrideParams["allowVideoStreamCopy"] = "true"
            overrideParams["allowAudioStreamCopy"] = "false"
            sourceVideoBitrate?.takeIf { it > 0 }?.let { bitrate ->
                overrideParams["VideoBitrate"] = bitrate.toString()
            }
            when (audioTranscodeMode) {
                AudioTranscodeMode.STEREO -> {
                    overrideParams["AudioCodec"] = "aac"
                    overrideParams["TranscodingMaxAudioChannels"] = "2"
                }
                AudioTranscodeMode.SURROUND_5_1 -> {
                    overrideParams["AudioCodec"] = "eac3"
                    overrideParams["TranscodingMaxAudioChannels"] = "6"
                }
                else -> Unit
            }
        } else if (audioTranscodeMode == AudioTranscodeMode.PASSTHROUGH) {
            overrideParams["allowAudioStreamCopy"] = "true"
        }

        sourceUri.queryParameterNames
            .filterNot { queryName ->
                overrideParams.keys.any { key ->
                    queryName.equals(key, ignoreCase = true)
                }
            }
            .forEach { queryName ->
                sourceUri.getQueryParameters(queryName).forEach { queryValue ->
                    builder.appendQueryParameter(queryName, queryValue)
                }
            }

        overrideParams.forEach { (key, value) ->
            builder.appendQueryParameter(key, value)
        }

        return builder.build().toString()
    }
}
