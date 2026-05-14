package com.grmemby.winplayer

import com.grmemby.data.model.AuthenticationRequest
import com.grmemby.data.model.AuthenticationResult
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.PlaybackInfoResponse
import com.grmemby.data.model.PlaybackProgressRequest
import com.grmemby.data.model.PlaybackStartRequest
import com.grmemby.data.model.PlaybackStoppedRequest
import com.grmemby.data.model.QueryResult
import com.grmemby.data.model.ServerInfo
import com.grmemby.data.network.GrmembyJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable

class JellyfinDesktopClient(
    private val clientVersion: String = "1.4",
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(GrmembyJson) }
    }
) : Closeable {

    suspend fun publicInfo(baseUrl: String): ServerInfo = httpClient.get(endpoint(baseUrl, "System/Info/Public"))
        .requireBody("public system info")

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        deviceId: String
    ): AuthenticationResult {
        val response = httpClient.post(endpoint(baseUrl, "Users/AuthenticateByName")) {
            grmembyHeaders(deviceId = deviceId, token = null)
            contentType(ContentType.Application.Json)
            setBody(AuthenticationRequest(username = username, password = password))
        }
        return response.requireBody("authenticate")
    }

    suspend fun playbackInfo(
        baseUrl: String,
        itemId: String,
        userId: String,
        accessToken: String,
        deviceId: String,
        maxStreamingBitrate: Int
    ): PlaybackInfoResponse {
        val response = httpClient.get(endpoint(baseUrl, "Items/${itemId.urlPathSegment()}/PlaybackInfo")) {
            grmembyHeaders(deviceId = deviceId, token = accessToken)
            parameter("userId", userId)
            parameter("maxStreamingBitrate", maxStreamingBitrate)
            parameter("enableDirectPlay", true)
            parameter("enableDirectStream", true)
            parameter("enableTranscoding", true)
        }
        return response.requireBody("playback info")
    }

    suspend fun latestItems(baseUrl: String, userId: String, accessToken: String, deviceId: String, parentId: String? = null): List<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Items/Latest")) {
            grmembyHeaders(deviceId, accessToken)
            parentId?.let { parameter("parentId", it) }
            parameter("includeItemTypes", "Movie,Series,Episode")
            parameter("limit", 60)
            parameter("fields", commonFields)
        }.requireBody("latest items")

    suspend fun resumeItems(baseUrl: String, userId: String, accessToken: String, deviceId: String): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Items/Resume")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("recursive", true)
            parameter("includeItemTypes", "Movie,Episode")
            parameter("limit", 60)
            parameter("fields", commonFields)
        }.requireBody("resume items")

    suspend fun nextUp(baseUrl: String, userId: String, accessToken: String, deviceId: String): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Shows/NextUp")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("userId", userId)
            parameter("limit", 60)
            parameter("fields", commonFields)
            parameter("enableUserData", true)
        }.requireBody("next up")

    suspend fun userViews(baseUrl: String, userId: String, accessToken: String, deviceId: String): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Views")) {
            grmembyHeaders(deviceId, accessToken)
        }.requireBody("user views")

    suspend fun userItems(
        baseUrl: String,
        userId: String,
        accessToken: String,
        deviceId: String,
        parentId: String? = null,
        includeTypes: String? = "Movie,Series,Episode",
        startIndex: Int = 0,
        limit: Int = 100
    ): QueryResult<BaseItemDto> = httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Items")) {
        grmembyHeaders(deviceId, accessToken)
        parentId?.let { parameter("parentId", it) }
        includeTypes?.let { parameter("includeItemTypes", it) }
        parameter("recursive", parentId == null)
        parameter("sortBy", "SortName")
        parameter("sortOrder", "Ascending")
        parameter("startIndex", startIndex)
        parameter("limit", limit)
        parameter("fields", commonFields)
        parameter("enableImages", true)
        parameter("imageTypeLimit", 1)
    }.requireBody("user items")

    suspend fun search(baseUrl: String, userId: String, accessToken: String, deviceId: String, term: String): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Items")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("searchTerm", term)
            parameter("recursive", true)
            parameter("includeItemTypes", "Movie,Series,Episode")
            parameter("limit", 80)
            parameter("fields", commonFields)
            parameter("enableImages", true)
            parameter("imageTypeLimit", 1)
        }.requireBody("search")

    suspend fun item(baseUrl: String, userId: String, accessToken: String, deviceId: String, itemId: String): BaseItemDto =
        httpClient.get(endpoint(baseUrl, "Users/${userId.urlPathSegment()}/Items/${itemId.urlPathSegment()}")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("fields", "People,Studios,Genres,Overview,ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,UserData,Chapters,MediaSources,Path")
        }.requireBody("item")

    suspend fun seasons(baseUrl: String, userId: String, accessToken: String, deviceId: String, seriesId: String): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Shows/${seriesId.urlPathSegment()}/Seasons")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("userId", userId)
            parameter("fields", commonFields)
        }.requireBody("seasons")

    suspend fun episodes(baseUrl: String, userId: String, accessToken: String, deviceId: String, seriesId: String, seasonId: String? = null): QueryResult<BaseItemDto> =
        httpClient.get(endpoint(baseUrl, "Shows/${seriesId.urlPathSegment()}/Episodes")) {
            grmembyHeaders(deviceId, accessToken)
            parameter("userId", userId)
            seasonId?.let { parameter("seasonId", it) }
            parameter("fields", commonFields)
        }.requireBody("episodes")

    suspend fun markFavorite(baseUrl: String, userId: String, accessToken: String, deviceId: String, itemId: String, favorite: Boolean) {
        val path = "Users/${userId.urlPathSegment()}/FavoriteItems/${itemId.urlPathSegment()}"
        val response = if (favorite) httpClient.post(endpoint(baseUrl, path)) { grmembyHeaders(deviceId, accessToken) }
        else httpClient.delete(endpoint(baseUrl, path)) { grmembyHeaders(deviceId, accessToken) }
        response.requireUnit("favorite")
    }

    suspend fun markPlayed(baseUrl: String, userId: String, accessToken: String, deviceId: String, itemId: String, played: Boolean) {
        val path = "Users/${userId.urlPathSegment()}/PlayedItems/${itemId.urlPathSegment()}"
        val response = if (played) httpClient.post(endpoint(baseUrl, path)) { grmembyHeaders(deviceId, accessToken) }
        else httpClient.delete(endpoint(baseUrl, path)) { grmembyHeaders(deviceId, accessToken) }
        response.requireUnit("played")
    }

    suspend fun reportPlaybackStart(baseUrl: String, accessToken: String, deviceId: String, request: PlaybackStartRequest) {
        httpClient.post(endpoint(baseUrl, "Sessions/Playing")) {
            grmembyHeaders(deviceId, accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireUnit("report playback start")
    }

    suspend fun reportPlaybackProgress(baseUrl: String, accessToken: String, deviceId: String, request: PlaybackProgressRequest) {
        httpClient.post(endpoint(baseUrl, "Sessions/Playing/Progress")) {
            grmembyHeaders(deviceId, accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireUnit("report playback progress")
    }

    suspend fun reportPlaybackStopped(baseUrl: String, accessToken: String, deviceId: String, request: PlaybackStoppedRequest) {
        httpClient.post(endpoint(baseUrl, "Sessions/Playing/Stopped")) {
            grmembyHeaders(deviceId, accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireUnit("report playback stopped")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.grmembyHeaders(
        deviceId: String,
        token: String?
    ) {
        val authValue = buildString {
            append("MediaBrowser ")
            append("Client=\"Grmemby Win\", ")
            append("Device=\"Windows\", ")
            append("DeviceId=\"").append(deviceId).append("\", ")
            append("Version=\"").append(clientVersion).append("\"")
            if (!token.isNullOrBlank()) append(", Token=\"").append(token).append("\"")
        }
        header("X-Emby-Authorization", authValue)
        if (!token.isNullOrBlank()) header("X-MediaBrowser-Token", token)
    }

    private suspend inline fun <reified T> HttpResponse.requireBody(action: String): T {
        if (status.value !in 200..299) {
            val body = bodyAsText()
            error("Failed to $action: HTTP ${status.value} ${status.description} ${body.take(300)}")
        }
        return body()
    }

    private suspend fun HttpResponse.requireUnit(action: String) {
        if (status.value !in 200..299) {
            val body = bodyAsText()
            error("Failed to $action: HTTP ${status.value} ${status.description} ${body.take(300)}")
        }
    }

    override fun close() = httpClient.close()

    companion object {
        private const val commonFields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,SeasonId,Genres,CommunityRating,ProductionYear,Overview,RunTimeTicks,UserData,MediaSources,PrimaryImageAspectRatio"
    }
}

internal fun endpoint(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

internal fun String.urlPathSegment(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
