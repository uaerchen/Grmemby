package com.grmemby.roomserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RoomHubTest {
    @Test
    fun createJoinPlaybackAndLeaveRoom() {
        val hub = RoomHub(
            clock = { 1_000L },
            idFactory = sequenceIds("host-1", "guest-1"),
            roomIdFactory = { "1234" }
        )

        val created = hub.createRoom(CreateRoomRequest(name = "Movie night", hostName = "Host"))
        assertEquals("1234", created.room.id)
        assertEquals("host-1", created.memberId)
        assertEquals("Host", created.room.hostName)

        val joined = hub.joinRoom("1234", JoinRoomRequest(name = "Guest"))
        assertEquals("guest-1", joined.memberId)
        assertEquals(2, joined.room.members.size)

        val updated = hub.updatePlayback(
            roomId = "1234",
            memberId = "guest-1",
            request = PlaybackUpdateRequest(event = PlaybackEvent.SEEK, positionMs = 42_000L, isPlaying = true)
        )
        assertEquals(42_000L, updated.playback.positionMs)
        assertTrue(updated.playback.isPlaying)
        assertEquals("guest-1", updated.playback.updatedBy)

        val afterGuestLeaves = hub.leaveRoom("1234", "guest-1")
        assertNotNull(afterGuestLeaves)
        assertEquals(1, afterGuestLeaves.members.size)

        val afterHostLeaves = hub.leaveRoom("1234", "host-1")
        assertNull(afterHostLeaves)
        assertNull(hub.getRoom("1234"))
    }

    @Test
    fun onlyHostCanSelectMediaAndDisband() {
        val hub = RoomHub(
            clock = { 2_000L },
            idFactory = sequenceIds("host-2", "guest-2"),
            roomIdFactory = { "2345" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = null, hostName = "Host"))
        val joined = hub.joinRoom(created.room.id, JoinRoomRequest(name = "Guest"))

        assertFalse(hub.selectMedia(created.room.id, joined.memberId, SelectMediaRequest("guest-2", "item-a", "Bad")).accepted)
        assertEquals(null, hub.getRoom(created.room.id)?.media?.itemId)

        val selected = hub.selectMedia(created.room.id, created.memberId, SelectMediaRequest(created.memberId, "item-a", "Movie"))
        assertTrue(selected.accepted)
        assertEquals("item-a", selected.room?.media?.itemId)

        assertFalse(hub.disbandRoom(created.room.id, joined.memberId))
        assertNotNull(hub.getRoom(created.room.id))
        assertTrue(hub.disbandRoom(created.room.id, created.memberId))
        assertNull(hub.getRoom(created.room.id))
    }

    @Test
    fun hostExitPlaybackKeepsRoomButPublishesExitState() {
        val hub = RoomHub(
            clock = { 3_000L },
            idFactory = sequenceIds("host-3", "guest-3"),
            roomIdFactory = { "3456" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = "Episode", hostName = "Host"))
        val joined = hub.joinRoom(created.room.id, JoinRoomRequest(name = "Guest"))
        val selected = hub.selectMedia(created.room.id, created.memberId, SelectMediaRequest(created.memberId, "episode-1", "Episode 1"))
        assertTrue(selected.accepted)

        val exited = hub.updatePlayback(
            roomId = created.room.id,
            memberId = created.memberId,
            request = PlaybackUpdateRequest(event = PlaybackEvent.EXIT, positionMs = 123_000L, isPlaying = false)
        )

        assertEquals("episode-1", exited.media?.itemId)
        assertEquals(2, exited.members.size)
        assertEquals(PlaybackEvent.EXIT, exited.playback.event)
        assertFalse(exited.playback.isPlaying)
        assertEquals(123_000L, exited.playback.positionMs)
        assertEquals(created.memberId, exited.playback.updatedBy)
        assertNotNull(hub.getRoom(created.room.id))
        assertTrue(hub.getRoom(created.room.id)?.members?.any { it.id == joined.memberId } == true)
    }

    @Test
    fun heartbeatKeepsHostMemberFreshWithoutCreatingMembers() {
        var now = 4_000L
        val hub = RoomHub(
            clock = { now },
            idFactory = sequenceIds("host-heartbeat"),
            roomIdFactory = { "4567" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = "Keep alive", hostName = "Host"))

        now = 19_000L
        val touched = hub.touchMember(created.room.id, created.memberId)

        assertNotNull(touched)
        assertEquals(1, touched.members.size)
        assertEquals(19_000L, touched.members.single().lastSeenAt)
        assertEquals(19_000L, touched.updatedAt)
        assertNull(hub.touchMember(created.room.id, "missing-member"))
    }

    @Test
    fun joinWithExistingHostMemberIdResumesHostInsteadOfCreatingGuest() {
        var now = 4_000L
        val hub = RoomHub(
            clock = { now },
            idFactory = sequenceIds("host-resume", "unused-guest"),
            roomIdFactory = { "4570" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = "Resume host", hostName = "Host"))

        now = 25_000L
        val resumed = hub.joinRoom(
            created.room.id,
            JoinRoomRequest(name = "Host", memberId = created.memberId)
        )

        assertEquals(created.memberId, resumed.memberId)
        assertEquals(created.memberId, resumed.room.hostMemberId)
        assertEquals(1, resumed.room.members.size)
        assertTrue(resumed.room.members.single().isHost)
        assertEquals(25_000L, resumed.room.members.single().lastSeenAt)
    }

    @Test
    fun createRoomCanUseStableDeviceMemberIdForHost() {
        val hub = RoomHub(
            clock = { 4_500L },
            idFactory = sequenceIds("unused-generated-host"),
            roomIdFactory = { "4580" }
        )

        val created = hub.createRoom(
            CreateRoomRequest(name = "Stable host", hostName = "Host", memberId = "device-stable-host")
        )

        assertEquals("device-stable-host", created.memberId)
        assertEquals("device-stable-host", created.room.hostMemberId)
        assertEquals(1, created.room.members.size)
        assertTrue(created.room.members.single().isHost)
    }

    @Test
    fun joinRoomCanUseStableDeviceMemberIdForGuestAndResumeWithoutDuplicate() {
        var now = 4_600L
        val hub = RoomHub(
            clock = { now },
            idFactory = sequenceIds("host-stable-guest", "unused-generated-guest"),
            roomIdFactory = { "4581" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = "Stable guest", hostName = "Host"))

        val joined = hub.joinRoom(
            created.room.id,
            JoinRoomRequest(name = "Guest", memberId = "device-stable-guest")
        )

        assertEquals("device-stable-guest", joined.memberId)
        assertEquals(2, joined.room.members.size)
        assertTrue(joined.room.members.any { it.id == "device-stable-guest" && !it.isHost })

        now = 29_000L
        val resumed = hub.joinRoom(
            created.room.id,
            JoinRoomRequest(name = "Guest 回来", memberId = "device-stable-guest")
        )

        assertEquals("device-stable-guest", resumed.memberId)
        assertEquals(2, resumed.room.members.size)
        val guest = resumed.room.members.single { it.id == "device-stable-guest" }
        assertEquals("Guest 回来", guest.name)
        assertEquals(29_000L, guest.lastSeenAt)
    }

    @Test
    fun playbackUpdatesForPreviousMediaAreIgnoredAfterEpisodeSwitch() {
        var now = 6_000L
        val hub = RoomHub(
            clock = { now },
            idFactory = sequenceIds("host-6", "guest-6"),
            roomIdFactory = { "6789" }
        )
        val created = hub.createRoom(CreateRoomRequest(name = "Episode", hostName = "Host"))
        hub.joinRoom(created.room.id, JoinRoomRequest(name = "Guest"))
        val episodeOne = hub.selectMedia(
            created.room.id,
            created.memberId,
            SelectMediaRequest(created.memberId, "episode-1", "Episode 1")
        )
        assertTrue(episodeOne.accepted)
        val playingEpisodeOne = hub.updatePlayback(
            roomId = created.room.id,
            memberId = created.memberId,
            request = PlaybackUpdateRequest(
                mediaId = "episode-1",
                event = PlaybackEvent.PROGRESS,
                positionMs = 300_000L,
                isPlaying = true
            )
        )
        assertEquals(300_000L, playingEpisodeOne.playback.positionMs)

        now = 7_000L
        val episodeTwo = hub.selectMedia(
            created.room.id,
            created.memberId,
            SelectMediaRequest(created.memberId, "episode-2", "Episode 2")
        )
        assertTrue(episodeTwo.accepted)

        now = 8_000L
        val stale = hub.updatePlayback(
            roomId = created.room.id,
            memberId = created.memberId,
            request = PlaybackUpdateRequest(
                mediaId = "episode-1",
                event = PlaybackEvent.PROGRESS,
                positionMs = 301_000L,
                isPlaying = true
            )
        )

        assertEquals("episode-2", stale.media?.itemId)
        assertEquals(PlaybackEvent.PAUSE, stale.playback.event)
        assertEquals(0L, stale.playback.positionMs)
        assertFalse(stale.playback.isPlaying)
    }

    @Test
    fun createRoomStoresHostServerIdentityAndJoinRejectsDifferentServer() {
        val hub = RoomHub(
            clock = { 1_250L },
            idFactory = sequenceIds("host-server", "guest-server"),
            roomIdFactory = { "1299" }
        )

        val created = hub.createRoom(
            CreateRoomRequest(
                name = "Server scoped",
                hostName = "Host",
                serverUrl = "https://emos.example.test/emby",
                serverName = "Emos服"
            )
        )

        assertEquals("https://emos.example.test/emby", created.room.serverUrl)
        assertEquals("Emos服", created.room.serverName)
        val mismatch = assertFailsWith<IllegalArgumentException> {
            hub.joinRoom(
                "1299",
                JoinRoomRequest(name = "Guest", serverUrl = "https://9club.example.test")
            )
        }
        assertEquals("加入房间失败，请添加Emos服后重试", mismatch.message)
        assertEquals(1, hub.getRoom("1299")?.members?.size)
    }

    @Test
    fun generatedRoomIdsAreFourDigitNumbers() {
        val hub = RoomHub(clock = { 4_000L }, idFactory = sequenceIds("host-4"))

        val created = hub.createRoom(CreateRoomRequest(name = null, hostName = "Host"))

        assertTrue(Regex("\\d{4}").matches(created.room.id))
        assertEquals("Grmemby Room ${created.room.id}", created.room.name)
    }

    @Test
    fun createRoomSkipsActiveRoomIdCollisions() {
        val hub = RoomHub(
            clock = { 5_000L },
            idFactory = sequenceIds("host-a", "host-b"),
            roomIdFactory = sequenceIds("1111", "1111", "2222")
        )

        val first = hub.createRoom(CreateRoomRequest(name = null, hostName = "Host"))
        val second = hub.createRoom(CreateRoomRequest(name = null, hostName = "Host"))

        assertEquals("1111", first.room.id)
        assertEquals("2222", second.room.id)
    }
}

private fun sequenceIds(vararg ids: String): () -> String {
    val iterator = ids.iterator()
    return { iterator.next() }
}
