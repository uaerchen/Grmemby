package com.grmemby.app.watchparty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class WatchPartyRepository {
    private val baseUrl = WatchPartyEndpoints.httpBaseUrl()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun createRoom(
        name: String?,
        hostName: String,
        memberId: String? = null,
        serverUrl: String? = null,
        serverName: String? = null
    ): RoomCreatedResponse = post(
        path = "/rooms",
        body = CreateRoomRequest(
            name = name,
            hostName = hostName,
            memberId = memberId?.takeIf { it.isNotBlank() },
            serverUrl = serverUrl?.takeIf { it.isNotBlank() },
            serverName = serverName?.takeIf { it.isNotBlank() }
        )
    )

    suspend fun joinRoom(
        roomId: String,
        name: String,
        memberId: String? = null,
        serverUrl: String? = null,
        serverName: String? = null
    ): RoomJoinedResponse = post(
        path = "/rooms/${roomId.trim()}/join",
        body = JoinRoomRequest(
            name = name,
            memberId = memberId?.takeIf { it.isNotBlank() },
            serverUrl = serverUrl?.takeIf { it.isNotBlank() },
            serverName = serverName?.takeIf { it.isNotBlank() }
        )
    )

    suspend fun getRoom(roomId: String): RoomDto = request(
        Request.Builder()
            .url("$baseUrl/rooms/${roomId.trim()}")
            .get()
            .build()
    )

    suspend fun heartbeat(roomId: String, memberId: String): RoomDto = request(
        Request.Builder()
            .url("$baseUrl/rooms/${roomId.trim()}/heartbeat?memberId=${query(memberId)}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
    )

    suspend fun leaveRoom(roomId: String, memberId: String): RoomActionResponse = request(
        Request.Builder()
            .url("$baseUrl/rooms/${roomId.trim()}/leave?memberId=${query(memberId)}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
    )

    suspend fun disbandRoom(roomId: String, memberId: String): RoomActionResponse = request(
        Request.Builder()
            .url("$baseUrl/rooms/${roomId.trim()}?memberId=${query(memberId)}")
            .delete()
            .build()
    )

    suspend fun selectMedia(
        roomId: String,
        memberId: String,
        itemId: String,
        title: String?
    ): RoomActionResponse = post(
        path = "/rooms/${roomId.trim()}/select-media",
        body = SelectMediaRequest(memberId = memberId, itemId = itemId, title = title)
    )

    suspend fun updatePlayback(
        roomId: String,
        memberId: String,
        mediaId: String?,
        event: PlaybackEvent,
        positionMs: Long,
        isPlaying: Boolean
    ): RoomDto = post(
        path = "/rooms/${roomId.trim()}/playback",
        body = PlaybackUpdateRequest(
            memberId = memberId,
            mediaId = mediaId?.takeIf { it.isNotBlank() },
            event = event,
            positionMs = positionMs.coerceAtLeast(0L),
            isPlaying = isPlaying
        )
    )

    suspend fun sendChatMessage(
        roomId: String,
        memberId: String,
        content: String
    ): RoomDto = post(
        path = "/rooms/${roomId.trim()}/chat",
        body = SendChatMessageRequest(
            memberId = memberId,
            content = content
        )
    )

    private suspend inline fun <reified T, reified R> post(path: String, body: T): R {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(json.encodeToString(body).toRequestBody(jsonMediaType))
            .build()
        return request(request)
    }

    private suspend inline fun <reified T> request(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $responseBody")
            }
            json.decodeFromString<T>(responseBody)
        }
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
}
