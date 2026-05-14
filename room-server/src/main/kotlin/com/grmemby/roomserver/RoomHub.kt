package com.grmemby.roomserver

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class RoomHub(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { secureId() },
    private val roomIdFactory: () -> String = { secureRoomId() },
) {
    private val rooms = ConcurrentHashMap<String, RoomDto>()

    fun createRoom(request: CreateRoomRequest): RoomCreatedResponse = synchronized(rooms) {
        val now = clock()
        val roomId = generateUniqueRoomId()
        val hostId = request.memberId?.takeIf { it.isNotBlank() } ?: idFactory()
        val host = RoomMemberDto(
            id = hostId,
            name = request.hostName.ifBlank { "Host" },
            isHost = true,
            joinedAt = now,
            lastSeenAt = now,
        )
        val room = RoomDto(
            id = roomId,
            name = request.name?.takeIf { it.isNotBlank() } ?: "Grmemby Room $roomId",
            hostMemberId = hostId,
            hostName = host.name,
            serverUrl = request.serverUrl?.takeIf { it.isNotBlank() },
            serverName = request.serverName?.takeIf { it.isNotBlank() },
            members = listOf(host),
            createdAt = now,
            updatedAt = now,
        )
        rooms[roomId] = room
        RoomCreatedResponse(room = room, memberId = hostId)
    }

    private fun generateUniqueRoomId(): String {
        repeat(MaxRoomIdAttempts) {
            val candidate = roomIdFactory()
            if (!rooms.containsKey(candidate)) return candidate
        }
        throw IllegalStateException("no available room ids")
    }

    fun joinRoom(roomId: String, request: JoinRoomRequest): RoomJoinedResponse = synchronized(rooms) {
        val room = rooms[roomId] ?: throw NoSuchElementException("room not found")
        validateSameServer(room, request.serverUrl)
        val now = clock()
        val requestedMemberId = request.memberId?.takeIf { it.isNotBlank() }
        val existingMember = requestedMemberId?.let { memberId -> room.members.firstOrNull { it.id == memberId } }
        if (existingMember != null) {
            val updatedMembers = room.members.map { member ->
                if (member.id == existingMember.id) {
                    member.copy(
                        name = request.name.takeIf { it.isNotBlank() } ?: member.name,
                        lastSeenAt = now,
                    )
                } else {
                    member
                }
            }
            val updated = room.copy(members = updatedMembers, updatedAt = now)
            rooms[roomId] = updated
            return@synchronized RoomJoinedResponse(room = updated, memberId = existingMember.id)
        }
        val member = RoomMemberDto(
            id = requestedMemberId ?: idFactory(),
            name = request.name.ifBlank { "Guest" },
            isHost = false,
            joinedAt = now,
            lastSeenAt = now,
        )
        val updated = room.copy(
            members = room.members.filterNot { it.id == member.id } + member,
            updatedAt = now,
        )
        rooms[roomId] = updated
        RoomJoinedResponse(room = updated, memberId = member.id)
    }

    fun listRooms(): List<RoomDto> = rooms.values.sortedByDescending { it.updatedAt }

    private fun validateSameServer(room: RoomDto, requestedServerUrl: String?) {
        val roomServerUrl = room.serverUrl?.takeIf { it.isNotBlank() } ?: return
        val guestServerUrl = requestedServerUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(room.sameServerJoinFailureMessage())
        if (!sameServer(roomServerUrl, guestServerUrl)) {
            throw IllegalArgumentException(room.sameServerJoinFailureMessage())
        }
    }

    private fun RoomDto.sameServerJoinFailureMessage(): String {
        val requiredServerName = serverName?.trim()?.takeIf { it.isNotBlank() }
            ?: "房主的服务器"
        return "加入房间失败，请添加${requiredServerName}后重试"
    }

    private fun sameServer(left: String?, right: String?): Boolean {
        val normalizedLeft = canonicalServer(left) ?: return false
        val normalizedRight = canonicalServer(right) ?: return false
        return normalizedLeft == normalizedRight
    }

    private fun canonicalServer(url: String?): String? {
        var normalized = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        while (normalized.endsWith("/")) normalized = normalized.dropLast(1)
        return normalized.lowercase().removeSuffix("/emby")
    }

    fun getRoom(roomId: String): RoomDto? = rooms[roomId]

    fun touchMember(roomId: String, memberId: String): RoomDto? = synchronized(rooms) {
        val room = rooms[roomId] ?: return@synchronized null
        val now = clock()
        if (room.members.none { it.id == memberId }) return@synchronized null
        val updatedMembers = room.members.map { member ->
            if (member.id == memberId) member.copy(lastSeenAt = now) else member
        }
        val updated = room.copy(members = updatedMembers, updatedAt = now)
        rooms[roomId] = updated
        updated
    }

    fun leaveRoom(roomId: String, memberId: String): RoomDto? = synchronized(rooms) {
        val room = rooms[roomId] ?: return@synchronized null
        if (memberId == room.hostMemberId) {
            rooms.remove(roomId)
            return@synchronized null
        }
        val now = clock()
        val updatedMembers = room.members.filterNot { it.id == memberId }
        if (updatedMembers.isEmpty()) {
            rooms.remove(roomId)
            null
        } else {
            val updated = room.copy(members = updatedMembers, updatedAt = now)
            rooms[roomId] = updated
            updated
        }
    }

    fun disbandRoom(roomId: String, memberId: String): Boolean = synchronized(rooms) {
        val room = rooms[roomId] ?: return@synchronized false
        if (memberId != room.hostMemberId) return@synchronized false
        rooms.remove(roomId)
        true
    }

    fun selectMedia(roomId: String, memberId: String, request: SelectMediaRequest): RoomActionResponse = synchronized(rooms) {
        val room = rooms[roomId] ?: return@synchronized RoomActionResponse(false, reason = "room not found")
        if (memberId != room.hostMemberId) {
            return@synchronized RoomActionResponse(false, room = room, reason = "only host can select media")
        }
        val now = clock()
        val updated = room.copy(
            media = MediaSelectionDto(
                itemId = request.itemId,
                title = request.title,
                selectedBy = memberId,
                selectedAt = now,
            ),
            members = room.members.map { member ->
                if (member.id == memberId) {
                    member.copy(readyMediaId = request.itemId, readyAt = now, lastSeenAt = now)
                } else {
                    member.copy(readyMediaId = null, readyAt = null)
                }
            },
            playback = PlaybackStateDto(
                event = PlaybackEvent.PAUSE,
                isPlaying = false,
                updatedBy = memberId,
                updatedAt = now
            ),
            updatedAt = now,
        )
        rooms[roomId] = updated
        RoomActionResponse(true, room = updated)
    }

    fun updatePlayback(roomId: String, memberId: String, request: PlaybackUpdateRequest): RoomDto = synchronized(rooms) {
        val room = rooms[roomId] ?: throw NoSuchElementException("room not found")
        require(room.members.any { it.id == memberId }) { "member not in room" }
        val now = clock()
        val currentMediaId = room.media?.itemId
        val requestMediaId = request.mediaId?.takeIf { it.isNotBlank() }
        if (requestMediaId != null && requestMediaId != currentMediaId) {
            return@synchronized room
        }
        if (request.event == PlaybackEvent.READY) {
            val updatedMembers = room.members.map { member ->
                if (member.id == memberId) {
                    member.copy(readyMediaId = currentMediaId, readyAt = now, lastSeenAt = now)
                } else {
                    member
                }
            }
            val updated = room.copy(members = updatedMembers, updatedAt = now)
            rooms[roomId] = updated
            return@synchronized updated
        }
        val playback = PlaybackStateDto(
            positionMs = request.positionMs.coerceAtLeast(0L),
            isPlaying = request.isPlaying,
            event = request.event,
            updatedBy = memberId,
            updatedAt = now,
        )
        val updated = room.copy(playback = playback, updatedAt = now)
        rooms[roomId] = updated
        updated
    }

    fun addChatMessage(roomId: String, request: SendChatMessageRequest): RoomDto = synchronized(rooms) {
        val room = rooms[roomId] ?: throw NoSuchElementException("room not found")
        val member = room.members.firstOrNull { it.id == request.memberId }
            ?: throw IllegalArgumentException("member not in room")
        val content = request.content.trim().take(MaxChatMessageLength)
        require(content.isNotBlank()) { "content is required" }
        val now = clock()
        val message = RoomChatMessageDto(
            id = idFactory(),
            memberId = member.id,
            senderName = member.name.ifBlank { "成员" },
            content = content,
            createdAt = now,
        )
        val updatedMembers = room.members.map { current ->
            if (current.id == member.id) current.copy(lastSeenAt = now) else current
        }
        val updated = room.copy(
            members = updatedMembers,
            chatMessages = (room.chatMessages + message).takeLast(MaxChatMessages),
            updatedAt = now,
        )
        rooms[roomId] = updated
        updated
    }

    companion object {
        private const val RoomIdDigits = 4
        private const val MaxRoomIdAttempts = 10_000
        private const val MaxChatMessages = 80
        private const val MaxChatMessageLength = 240
        private val random = SecureRandom()
        private val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray()

        private fun secureRoomId(): String = random
            .nextInt(10_000)
            .toString()
            .padStart(RoomIdDigits, '0')

        private fun secureId(length: Int = 8): String = buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.size)])
            }
        }
    }
}
