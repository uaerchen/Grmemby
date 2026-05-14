package com.grmemby.roomserver

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val serverJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        roomServerModule()
    }.start(wait = true)
}

fun Application.roomServerModule(
    hub: RoomHub = RoomHub(),
    socketRegistry: RoomSocketRegistry = RoomSocketRegistry(),
    voiceSocketRegistry: VoiceSocketRegistry = VoiceSocketRegistry(),
) {
    install(ContentNegotiation) {
        json(serverJson)
    }
    install(WebSockets)
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    routing {
        get("/healthz") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/rooms") {
            call.respond(hub.listRooms())
        }

        post("/rooms") {
            val created = hub.createRoom(call.receive<CreateRoomRequest>())
            call.respond(HttpStatusCode.Created, created)
            socketRegistry.broadcastRoom(created.room)
        }

        get("/rooms/{roomId}") {
            val roomId = call.parameters["roomId"].orEmpty()
            val room = hub.getRoom(roomId)
            if (room == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found")) else call.respond(room)
        }

        post("/rooms/{roomId}/heartbeat") {
            val roomId = call.parameters["roomId"].orEmpty()
            val memberId = call.request.queryParameters["memberId"].orEmpty()
            if (memberId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("memberId is required"))
                return@post
            }
            val room = hub.touchMember(roomId, memberId)
            if (room == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found"))
            } else {
                call.respond(room)
                socketRegistry.broadcastRoom(room)
            }
        }

        post("/rooms/{roomId}/join") {
            val roomId = call.parameters["roomId"].orEmpty()
            val joined = runCatching { hub.joinRoom(roomId, call.receive<JoinRoomRequest>()) }.getOrElse { error ->
                if (error is IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(error.message ?: "加入房间失败，请添加房主的服务器后重试"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("room not found"))
                }
                return@post
            }
            call.respond(joined)
            socketRegistry.broadcastRoom(joined.room)
        }

        delete("/rooms/{roomId}") {
            val roomId = call.parameters["roomId"].orEmpty()
            val memberId = call.request.queryParameters["memberId"].orEmpty()
            if (memberId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("memberId is required"))
                return@delete
            }
            val removed = hub.disbandRoom(roomId, memberId)
            if (removed) {
                call.respond(RoomActionResponse(accepted = true))
                socketRegistry.broadcastDisband(roomId)
            } else {
                call.respond(HttpStatusCode.Forbidden, RoomActionResponse(accepted = false, room = hub.getRoom(roomId), reason = "only host can disband"))
            }
        }

        post("/rooms/{roomId}/leave") {
            val roomId = call.parameters["roomId"].orEmpty()
            val memberId = call.request.queryParameters["memberId"].orEmpty()
            if (memberId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("memberId is required"))
                return@post
            }
            val room = hub.leaveRoom(roomId, memberId)
            call.respond(RoomActionResponse(accepted = true, room = room))
            if (room == null) socketRegistry.broadcastDisband(roomId) else socketRegistry.broadcastRoom(room)
        }

        post("/rooms/{roomId}/select-media") {
            val roomId = call.parameters["roomId"].orEmpty()
            val request = call.receive<SelectMediaRequest>()
            val result = hub.selectMedia(roomId, request.memberId, request)
            if (result.accepted) {
                call.respond(result)
                result.room?.let { socketRegistry.broadcastRoom(it) }
            } else {
                call.respond(HttpStatusCode.Forbidden, result)
            }
        }

        post("/rooms/{roomId}/playback") {
            val roomId = call.parameters["roomId"].orEmpty()
            val request = call.receive<PlaybackUpdateRequest>()
            val memberId = request.memberId.orEmpty()
            if (memberId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("memberId is required"))
                return@post
            }
            val room = runCatching { hub.updatePlayback(roomId, memberId, request) }.getOrElse {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(it.message ?: "room not found"))
                return@post
            }
            call.respond(room)
            socketRegistry.broadcastRoom(room)
        }

        post("/rooms/{roomId}/chat") {
            val roomId = call.parameters["roomId"].orEmpty()
            val request = call.receive<SendChatMessageRequest>()
            if (request.memberId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("memberId is required"))
                return@post
            }
            val room = runCatching { hub.addChatMessage(roomId, request) }.getOrElse { error ->
                val status = if (error is IllegalArgumentException) HttpStatusCode.BadRequest else HttpStatusCode.NotFound
                call.respond(status, ErrorResponse(error.message ?: "room not found"))
                return@post
            }
            call.respond(room)
            socketRegistry.broadcastRoom(room)
        }

        webSocket("/ws/rooms/{roomId}") {
            val roomId = call.parameters["roomId"].orEmpty()
            val memberId = call.request.queryParameters["memberId"].orEmpty()
            val room = hub.getRoom(roomId)
            if (room == null || memberId.isBlank()) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "roomId/memberId invalid"))
                return@webSocket
            }
            socketRegistry.register(roomId, this)
            hub.touchMember(roomId, memberId)?.let { socketRegistry.sendRoom(this, it) }
            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val request = try {
                            serverJson.decodeFromString<PlaybackUpdateRequest>(frame.readText())
                        } catch (_: SerializationException) {
                            null
                        }
                        if (request != null) {
                            val updated = runCatching { hub.updatePlayback(roomId, memberId, request.copy(memberId = memberId)) }.getOrNull()
                            if (updated != null) socketRegistry.broadcastRoom(updated)
                        }
                    }
                }
            } finally {
                socketRegistry.unregister(roomId, this)
            }
        }

        webSocket("/ws/rooms/{roomId}/voice") {
            val roomId = call.parameters["roomId"].orEmpty()
            val memberId = call.request.queryParameters["memberId"].orEmpty()
            val room = hub.getRoom(roomId)
            if (room == null || memberId.isBlank() || room.members.none { it.id == memberId }) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "roomId/memberId invalid"))
                return@webSocket
            }
            voiceSocketRegistry.register(roomId, this)
            hub.touchMember(roomId, memberId)?.let { socketRegistry.broadcastRoom(it) }
            try {
                incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Text -> voiceSocketRegistry.broadcastVoiceText(roomId, this, frame.readText())
                        is Frame.Binary -> voiceSocketRegistry.broadcastVoiceBinary(roomId, this, frame.data)
                        else -> Unit
                    }
                }
            } finally {
                voiceSocketRegistry.unregister(roomId, this)
            }
        }
    }
}

class RoomSocketRegistry {
    private val sockets = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    fun register(roomId: String, session: DefaultWebSocketServerSession) {
        sockets.compute(roomId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
        }
    }

    fun unregister(roomId: String, session: DefaultWebSocketServerSession) {
        sockets[roomId]?.remove(session)
    }

    suspend fun sendRoom(session: DefaultWebSocketServerSession, room: RoomDto) {
        session.send(Frame.Text(serverJson.encodeToString(RoomDto.serializer(), room)))
    }

    suspend fun broadcastRoom(room: RoomDto) {
        val payload = Frame.Text(serverJson.encodeToString(RoomDto.serializer(), room))
        sockets[room.id]?.forEach { session ->
            runCatching { session.send(payload.copy()) }
        }
    }

    suspend fun broadcastDisband(roomId: String) {
        val payload = Frame.Text("{\"type\":\"disbanded\",\"roomId\":\"$roomId\"}")
        sockets.remove(roomId)?.forEach { session ->
            runCatching { session.send(payload.copy()) }
        }
    }
}

class VoiceSocketRegistry {
    private val sockets = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    fun register(roomId: String, session: DefaultWebSocketServerSession) {
        sockets.compute(roomId) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
        }
    }

    fun unregister(roomId: String, session: DefaultWebSocketServerSession) {
        sockets[roomId]?.remove(session)
    }

    suspend fun broadcastVoiceText(roomId: String, sender: DefaultWebSocketServerSession, payload: String) {
        sockets[roomId]?.forEach { session ->
            if (session !== sender) {
                runCatching { session.send(Frame.Text(payload)) }
            }
        }
    }

    suspend fun broadcastVoiceBinary(roomId: String, sender: DefaultWebSocketServerSession, payload: ByteArray) {
        sockets[roomId]?.forEach { session ->
            if (session !== sender) {
                runCatching { session.send(Frame.Binary(true, payload.copyOf())) }
            }
        }
    }
}
