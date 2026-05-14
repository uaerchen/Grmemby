package com.grmemby.winplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(
    val name: String? = null,
    val hostName: String = "Host",
    val memberId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null,
)

@Serializable
data class JoinRoomRequest(
    val name: String = "Guest",
    val memberId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null,
)

@Serializable
data class SelectMediaRequest(
    val memberId: String,
    val itemId: String,
    val title: String? = null,
)

@Serializable
data class PlaybackUpdateRequest(
    val memberId: String? = null,
    val mediaId: String? = null,
    val event: PlaybackEvent = PlaybackEvent.PROGRESS,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
)

@Serializable
data class SendChatMessageRequest(
    val memberId: String,
    val content: String,
)

@Serializable
enum class PlaybackEvent {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("seek") SEEK,
    @SerialName("progress") PROGRESS,
    @SerialName("prepare") PREPARE,
    @SerialName("ready") READY,
    @SerialName("exit") EXIT,
}

@Serializable
data class RoomCreatedResponse(val room: RoomDto, val memberId: String)

@Serializable
data class RoomJoinedResponse(val room: RoomDto, val memberId: String)

@Serializable
data class RoomActionResponse(val accepted: Boolean, val room: RoomDto? = null, val reason: String? = null)

@Serializable
data class RoomDto(
    val id: String,
    val name: String,
    val hostMemberId: String,
    val hostName: String,
    val serverUrl: String? = null,
    val serverName: String? = null,
    val media: MediaSelectionDto? = null,
    val playback: PlaybackStateDto = PlaybackStateDto(),
    val members: List<RoomMemberDto> = emptyList(),
    val chatMessages: List<RoomChatMessageDto> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class RoomMemberDto(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val readyMediaId: String? = null,
    val readyAt: Long? = null,
)

@Serializable
data class MediaSelectionDto(
    val itemId: String,
    val title: String? = null,
    val selectedBy: String,
    val selectedAt: Long,
)

@Serializable
data class PlaybackStateDto(
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val event: PlaybackEvent = PlaybackEvent.PROGRESS,
    val updatedBy: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class RoomChatMessageDto(
    val id: String,
    val memberId: String,
    val senderName: String,
    val content: String,
    val createdAt: Long,
)
