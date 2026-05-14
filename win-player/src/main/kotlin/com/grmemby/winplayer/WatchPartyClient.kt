package com.grmemby.winplayer

import com.grmemby.data.network.GrmembyJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable

class WatchPartyClient(
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(GrmembyJson) }
    }
) : Closeable {
    suspend fun createRoom(baseUrl: String, request: CreateRoomRequest): RoomCreatedResponse =
        httpClient.post(endpoint(baseUrl, "rooms")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireBody("create room")

    suspend fun joinRoom(baseUrl: String, roomId: String, request: JoinRoomRequest): RoomJoinedResponse =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/join")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireBody("join room")

    suspend fun getRoom(baseUrl: String, roomId: String): RoomDto =
        httpClient.get(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}"))
            .requireBody("get room")

    suspend fun heartbeat(baseUrl: String, roomId: String, memberId: String): RoomDto =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/heartbeat")) {
            parameter("memberId", memberId)
        }.requireBody("heartbeat")

    suspend fun selectMedia(baseUrl: String, roomId: String, request: SelectMediaRequest): RoomActionResponse =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/select-media")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireBody("select media")

    suspend fun updatePlayback(baseUrl: String, roomId: String, request: PlaybackUpdateRequest): RoomDto =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/playback")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireBody("update playback")

    suspend fun sendChat(baseUrl: String, roomId: String, request: SendChatMessageRequest): RoomDto =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/chat")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireBody("chat")

    suspend fun leaveRoom(baseUrl: String, roomId: String, memberId: String): RoomActionResponse =
        httpClient.post(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}/leave")) {
            parameter("memberId", memberId)
        }.requireBody("leave room")

    suspend fun disbandRoom(baseUrl: String, roomId: String, memberId: String): RoomActionResponse =
        httpClient.delete(endpoint(baseUrl, "rooms/${roomId.urlPathSegment()}")) {
            parameter("memberId", memberId)
        }.requireBody("disband room")

    private suspend inline fun <reified T> HttpResponse.requireBody(action: String): T {
        if (status.value !in 200..299) {
            val body = bodyAsText()
            error("Failed to $action: HTTP ${status.value} ${status.description} ${body.take(300)}")
        }
        return body()
    }

    override fun close() = httpClient.close()
}
