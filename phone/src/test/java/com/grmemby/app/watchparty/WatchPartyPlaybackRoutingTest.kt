package com.grmemby.app.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartyPlaybackRoutingTest {
    @Test
    fun guestWaitingRoomNavigatesWhenHostPublishesPrepare() {
        val room = watchPartyRoom(
            playback = PlaybackStateDto(
                event = PlaybackEvent.PREPARE,
                isPlaying = false,
                updatedBy = "host",
                updatedAt = 2_000L
            )
        )

        assertTrue(room.shouldNavigateGuestToPlayer(hasNavigatedToPlayer = false, isHost = false))
    }

    @Test
    fun guestWaitingRoomDoesNotNavigateAgainAfterAlreadyNavigated() {
        val room = watchPartyRoom(
            playback = PlaybackStateDto(
                event = PlaybackEvent.PREPARE,
                isPlaying = false,
                updatedBy = "host",
                updatedAt = 2_000L
            )
        )

        assertFalse(room.shouldNavigateGuestToPlayer(hasNavigatedToPlayer = true, isHost = false))
    }

    @Test
    fun hostWaitingRoomNeverAutoNavigatesFromPrepare() {
        val room = watchPartyRoom(
            playback = PlaybackStateDto(
                event = PlaybackEvent.PREPARE,
                isPlaying = false,
                updatedBy = "host",
                updatedAt = 2_000L
            )
        )

        assertFalse(room.shouldNavigateGuestToPlayer(hasNavigatedToPlayer = false, isHost = true))
    }

    @Test
    fun topBannerShowsRoomCodeAndMemberCount() {
        assertEquals("房间1234 · 人数2", formatWatchPartyBannerText("1234", 2))
        assertEquals("房间1234 · 人数1", formatWatchPartyBannerText(" 1234 ", 0))
    }

    @Test
    fun topBannerIsHiddenWithoutRoomCode() {
        assertNull(formatWatchPartyBannerText(null, 2))
        assertNull(formatWatchPartyBannerText("", 2))
    }

    @Test
    fun copyableInviteTextContainsOnlyRoomCode() {
        assertEquals("1234", copyableWatchPartyInviteText("1234"))
        assertEquals("1234", copyableWatchPartyInviteText("1234", "Grmemby 一起看房间：1234"))
        assertEquals("1234", copyableWatchPartyInviteText("fallback", " 1234 "))
    }

    @Test
    fun detailPlayPublishesPrepareOnlyForHostRoomSession() {
        val hostSession = ActiveWatchPartySession(
            roomId = "1234",
            memberId = "host",
            isHost = true,
            startPlaybackOnNextPlayer = true
        )
        val guestSession = hostSession.copy(memberId = "guest", isHost = false)

        assertTrue(hostSession.shouldPublishPrepareFromDetailPlay())
        assertFalse(guestSession.shouldPublishPrepareFromDetailPlay())
        assertFalse(null.shouldPublishPrepareFromDetailPlay())
    }

    @Test
    fun topBannerOnlyShowsForHostSession() {
        val hostSession = ActiveWatchPartySession(roomId = "1234", memberId = "host", isHost = true)
        val guestSession = hostSession.copy(memberId = "guest", isHost = false)

        assertTrue(shouldShowWatchPartyTopBanner(hostSession))
        assertFalse(shouldShowWatchPartyTopBanner(guestSession))
        assertFalse(shouldShowWatchPartyTopBanner(null))
    }

    @Test
    fun hostStartsOnlyAfterEveryMemberReportsReadyForSelectedMedia() {
        val readyRoom = watchPartyRoom(
            members = listOf(
                RoomMemberDto("host", "Host", isHost = true, joinedAt = 1_000L, lastSeenAt = 1_000L, readyMediaId = "episode-2"),
                RoomMemberDto("guest", "Guest", isHost = false, joinedAt = 1_000L, lastSeenAt = 1_000L, readyMediaId = "episode-2")
            )
        )
        val guestNotReadyRoom = readyRoom.copy(
            members = readyRoom.members.map { member ->
                if (member.id == "guest") member.copy(readyMediaId = null) else member
            }
        )
        val staleReadyRoom = readyRoom.copy(
            members = readyRoom.members.map { member ->
                member.copy(readyMediaId = "episode-1")
            }
        )

        assertTrue(readyRoom.areAllWatchPartyMembersReadyFor("episode-2"))
        assertFalse(guestNotReadyRoom.areAllWatchPartyMembersReadyFor("episode-2"))
        assertFalse(staleReadyRoom.areAllWatchPartyMembersReadyFor("episode-2"))
    }

    @Test
    fun hostReadyBarrierFallsBackInsteadOfWaitingForever() {
        assertFalse(shouldForceWatchPartyPlaybackAfterReadyWait(0))
        assertFalse(shouldForceWatchPartyPlaybackAfterReadyWait(WATCH_PARTY_READY_WAIT_TIMEOUT_TICKS - 1))
        assertTrue(shouldForceWatchPartyPlaybackAfterReadyWait(WATCH_PARTY_READY_WAIT_TIMEOUT_TICKS))
    }

    @Test
    fun deferredWatchPartyPreloadDoesNotCoverPlayControlsWithLoadingOverlay() {
        assertFalse(shouldShowPlayerLoadingOverlay(isLoading = true, playWhenReady = false))
        assertTrue(shouldShowPlayerLoadingOverlay(isLoading = true, playWhenReady = true))
        assertFalse(shouldShowPlayerLoadingOverlay(isLoading = false, playWhenReady = true))
    }

    @Test
    fun watchPartyErrorMessagesNeverExposeServerDetails() {
        assertEquals("房间已销毁，请返回主页。", sanitizeWatchPartyErrorMessage("HTTP 404: room not found", roomDestroyed = true))
        assertEquals("一起看连接失败，请稍候再试", sanitizeWatchPartyErrorMessage("Failed to connect to http://example.test:8080"))
    }

    @Test
    fun sameServerJoinValidationAcceptsCanonicalEmbySuffixVariant() {
        val room = watchPartyRoom(serverUrl = "https://media.example.test/emby", serverName = "9club")

        assertNull(room.sameServerJoinFailureMessage(activeServerUrl = "https://media.example.test"))
    }

    @Test
    fun sameServerJoinValidationRejectsDifferentServerWithRequiredServerName() {
        val room = watchPartyRoom(serverUrl = "https://emos.example.test/emby", serverName = "Emos服")

        assertEquals(
            "加入房间失败，请添加Emos服后重试",
            room.sameServerJoinFailureMessage(activeServerUrl = "https://9club.example.test")
        )
        assertEquals(
            "加入房间失败，请添加Emos服后重试",
            sanitizeWatchPartyErrorMessage("加入房间失败，请添加Emos服后重试")
        )
    }

    @Test
    fun sameServerJoinValidationPromptsSwitchWhenRequiredServerAlreadySaved() {
        val room = watchPartyRoom(serverUrl = "https://emos.example.test/emby", serverName = "Emos服")

        assertEquals(
            "加入房间失败，请切换到Emos服后重试",
            room.sameServerJoinFailureMessage(
                activeServerUrl = "https://9club.example.test",
                savedServerUrls = listOf("https://emos.example.test")
            )
        )
    }

    private fun watchPartyRoom(
        playback: PlaybackStateDto = PlaybackStateDto(),
        members: List<RoomMemberDto> = listOf(
            RoomMemberDto("host", "Host", isHost = true, joinedAt = 1_000L, lastSeenAt = 1_000L),
            RoomMemberDto("guest", "Guest", isHost = false, joinedAt = 1_000L, lastSeenAt = 1_000L)
        ),
        serverUrl: String? = null,
        serverName: String? = null
    ): RoomDto = RoomDto(
        id = "1234",
        name = "Test Room",
        hostMemberId = "host",
        hostName = "Host",
        serverUrl = serverUrl,
        serverName = serverName,
        media = MediaSelectionDto(
            itemId = "episode-2",
            title = "Episode 2",
            selectedBy = "host",
            selectedAt = 1_000L
        ),
        playback = playback,
        members = members,
        createdAt = 1_000L,
        updatedAt = 2_000L
    )
}
