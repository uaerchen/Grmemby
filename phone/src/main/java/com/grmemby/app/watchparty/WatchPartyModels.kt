package com.grmemby.app.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(
    val name: String? = null,
    val hostName: String = "Host",
    val memberId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null
)

@Serializable
data class JoinRoomRequest(
    val name: String = "Guest",
    val memberId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null
)

@Serializable
data class SelectMediaRequest(
    val memberId: String,
    val itemId: String,
    val title: String? = null
)

@Serializable
data class PlaybackUpdateRequest(
    val memberId: String,
    val mediaId: String? = null,
    val event: PlaybackEvent = PlaybackEvent.PROGRESS,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false
)

@Serializable
data class SendChatMessageRequest(
    val memberId: String,
    val content: String
)

@Serializable
enum class PlaybackEvent {
    @SerialName("play")
    PLAY,

    @SerialName("pause")
    PAUSE,

    @SerialName("seek")
    SEEK,

    @SerialName("progress")
    PROGRESS,

    @SerialName("prepare")
    PREPARE,

    @SerialName("ready")
    READY,

    @SerialName("exit")
    EXIT
}

@Serializable
data class RoomCreatedResponse(
    val room: RoomDto,
    val memberId: String
)

@Serializable
data class RoomJoinedResponse(
    val room: RoomDto,
    val memberId: String
)

@Serializable
data class RoomActionResponse(
    val accepted: Boolean,
    val room: RoomDto? = null,
    val reason: String? = null
)

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
    val updatedAt: Long
)

@Serializable
data class RoomMemberDto(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val readyMediaId: String? = null,
    val readyAt: Long? = null
)

@Serializable
data class MediaSelectionDto(
    val itemId: String,
    val title: String? = null,
    val selectedBy: String,
    val selectedAt: Long
)

@Serializable
data class PlaybackStateDto(
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val event: PlaybackEvent = PlaybackEvent.PROGRESS,
    val updatedBy: String? = null,
    val updatedAt: Long = 0L
)

@Serializable
data class RoomChatMessageDto(
    val id: String,
    val memberId: String,
    val senderName: String,
    val content: String,
    val createdAt: Long
)

fun RoomDto.sameServerJoinFailureMessage(
    activeServerUrl: String?,
    savedServerUrls: List<String> = emptyList()
): String? {
    val requiredServerUrl = serverUrl?.takeIf { it.isNotBlank() } ?: return null
    if (watchPartySameServer(requiredServerUrl, activeServerUrl)) return null
    val requiredServerName = serverName?.trim()?.takeIf { it.isNotBlank() } ?: "房主的服务器"
    val hasRequiredServerSaved = savedServerUrls.any { savedServerUrl ->
        watchPartySameServer(requiredServerUrl, savedServerUrl)
    }
    return if (hasRequiredServerSaved) {
        "加入房间失败，请切换到${requiredServerName}后重试"
    } else {
        "加入房间失败，请添加${requiredServerName}后重试"
    }
}

fun watchPartySameServer(left: String?, right: String?): Boolean {
    val normalizedLeft = canonicalWatchPartyServerUrl(left) ?: return false
    val normalizedRight = canonicalWatchPartyServerUrl(right) ?: return false
    return normalizedLeft == normalizedRight
}

private fun canonicalWatchPartyServerUrl(url: String?): String? {
    var normalized = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    while (normalized.endsWith("/")) {
        normalized = normalized.dropLast(1)
    }
    return normalized.lowercase().removeSuffix("/emby")
}

fun RoomDto.shouldNavigateGuestToPlayer(
    hasNavigatedToPlayer: Boolean,
    isHost: Boolean
): Boolean {
    if (isHost || hasNavigatedToPlayer) return false
    val selectedMediaId = media?.itemId?.takeIf { it.isNotBlank() } ?: return false
    return selectedMediaId.isNotBlank() && (
        playback.isPlaying ||
            playback.event == PlaybackEvent.PLAY ||
            playback.event == PlaybackEvent.PREPARE
        )
}


/**
 * Process-wide watch-party session handoff used when a room is created before
 * entering the player. The player adopts this session and selects the opened
 * media as the room media for the host.
 */
data class ActiveWatchPartySession(
    val roomId: String,
    val memberId: String,
    val isHost: Boolean,
    val roomName: String? = null,
    val inviteText: String = "",
    val startPlaybackOnNextPlayer: Boolean = false
)

private const val WATCH_PARTY_INVITE_PREFIX = "Grmemby 一起看房间："

fun copyableWatchPartyInviteText(roomId: String?, inviteText: String? = null): String {
    val cleanedInvite = inviteText
        ?.trim()
        ?.removePrefix(WATCH_PARTY_INVITE_PREFIX)
        ?.takeIf { it.isNotBlank() }
    return cleanedInvite ?: roomId.orEmpty().trim()
}

fun formatWatchPartyBannerText(roomId: String?, memberCount: Int): String? {
    val cleanRoomId = roomId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val safeMemberCount = memberCount.coerceAtLeast(1)
    return "房间$cleanRoomId · 人数$safeMemberCount"
}

fun ActiveWatchPartySession?.shouldPublishPrepareFromDetailPlay(): Boolean {
    val session = this ?: return false
    return session.isHost &&
        session.roomId.isNotBlank() &&
        session.memberId.isNotBlank() &&
        session.startPlaybackOnNextPlayer
}

const val WATCH_PARTY_READY_WAIT_TIMEOUT_TICKS = 8

fun shouldShowWatchPartyTopBanner(session: ActiveWatchPartySession?): Boolean {
    return session?.isHost == true &&
        session.roomId.isNotBlank() &&
        session.memberId.isNotBlank()
}

fun shouldForceWatchPartyPlaybackAfterReadyWait(waitTicks: Int): Boolean {
    return waitTicks >= WATCH_PARTY_READY_WAIT_TIMEOUT_TICKS
}

fun shouldShowPlayerLoadingOverlay(isLoading: Boolean, playWhenReady: Boolean): Boolean {
    return isLoading && playWhenReady
}

fun RoomDto.areAllWatchPartyMembersReadyFor(mediaId: String?): Boolean {
    val targetMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return false
    if (members.isEmpty()) return false
    return members.all { member -> member.readyMediaId == targetMediaId }
}

fun sanitizeWatchPartyErrorMessage(message: String?, roomDestroyed: Boolean = false): String {
    if (roomDestroyed) return "房间已销毁，请返回主页。"
    val original = message.orEmpty().trim()
    if (original.startsWith("加入房间失败")) return original
    val normalized = original.lowercase()
    return if (
        normalized.contains("404") ||
        normalized.contains("not found") ||
        normalized.contains("room not found") ||
        normalized.contains("gone") ||
        normalized.contains("410") ||
        normalized.contains("destroy") ||
        normalized.contains("房间不存在") ||
        normalized.contains("房间已销毁")
    ) {
        "房间已销毁，请返回主页。"
    } else {
        "一起看连接失败，请稍候再试"
    }
}

object WatchPartySessionStore {
    private val _activeSession = MutableStateFlow<ActiveWatchPartySession?>(null)
    val activeSession: StateFlow<ActiveWatchPartySession?> = _activeSession.asStateFlow()

    fun set(session: ActiveWatchPartySession) {
        _activeSession.value = session
    }

    fun get(): ActiveWatchPartySession? = _activeSession.value

    fun clear(roomId: String? = null) {
        if (roomId == null || _activeSession.value?.roomId == roomId) {
            _activeSession.value = null
        }
    }
}
