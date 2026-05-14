package com.grmemby.app.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import com.grmemby.app.ui.screens.player.mpv.MPVPlayer
import com.grmemby.app.ui.screens.player.mpv.MpvPlayerController
import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.MediaStream
import com.grmemby.data.model.PlaybackRequest
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.MediaRepository
import com.grmemby.detail.CodecCapabilityManager
import com.grmemby.player.audio.SpatializerHelper
import com.grmemby.player.core.PlaybackMarkerUtils
import com.grmemby.player.core.PlayerState
import com.grmemby.player.core.PlayerTrack
import com.grmemby.player.core.PlayerUtils
import com.grmemby.player.preferences.PlayerPreferences
import com.grmemby.shared.util.image.imageTagFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.grmemby.app.download.DownloadRepository
import com.grmemby.app.download.DownloadRepositoryProvider
import com.grmemby.app.watchparty.PlaybackEvent
import com.grmemby.app.watchparty.RoomChatMessageDto
import com.grmemby.app.watchparty.RoomDto
import com.grmemby.app.watchparty.WatchPartyDeviceIdentity
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.WatchPartyVoiceChatClient
import com.grmemby.app.watchparty.WatchPartyVoiceChatState
import com.grmemby.app.watchparty.areAllWatchPartyMembersReadyFor
import com.grmemby.app.watchparty.copyableWatchPartyInviteText
import com.grmemby.app.watchparty.sanitizeWatchPartyErrorMessage
import com.grmemby.app.watchparty.sameServerJoinFailureMessage
import com.grmemby.app.watchparty.shouldForceWatchPartyPlaybackAfterReadyWait
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * Player ViewModel
 */


data class PlayerSeasonEpisodeItem(
    val id: String,
    val title: String,
    val durationLabel: String,
    val thumbnailUrl: String?,
    val isCurrent: Boolean,
    val isPlayed: Boolean
)

data class WatchPartyUiState(
    val roomId: String? = null,
    val memberId: String? = null,
    val isHost: Boolean = false,
    val roomName: String? = null,
    val mediaTitle: String? = null,
    val memberCount: Int = 0,
    val chatMessages: List<RoomChatMessageDto> = emptyList(),
    val inviteText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val isInRoom: Boolean get() = !roomId.isNullOrBlank() && !memberId.isNullOrBlank()
}

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val watchPartyRepository: WatchPartyRepository
) : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private val _preferredStreamIndexes = MutableStateFlow(PreferredStreamIndexes())
    val preferredStreamIndexes: StateFlow<PreferredStreamIndexes> = _preferredStreamIndexes.asStateFlow()
    private val _playbackCompletedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackCompletedEvents: SharedFlow<String> = _playbackCompletedEvents.asSharedFlow()
    private val _watchPartyMediaSwitchEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val watchPartyMediaSwitchEvents: SharedFlow<String> = _watchPartyMediaSwitchEvents.asSharedFlow()
    private val _watchPartyExitToRoomEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val watchPartyExitToRoomEvents: SharedFlow<Unit> = _watchPartyExitToRoomEvents.asSharedFlow()
    private val _watchPartyState = MutableStateFlow(WatchPartyUiState())
    val watchPartyState: StateFlow<WatchPartyUiState> = _watchPartyState.asStateFlow()
    private val watchPartyVoiceChatClient = WatchPartyVoiceChatClient()
    val watchPartyVoiceChatState: StateFlow<WatchPartyVoiceChatState> = watchPartyVoiceChatClient.state
    private val _currentSeasonEpisodes = MutableStateFlow<List<PlayerSeasonEpisodeItem>>(emptyList())
    val currentSeasonEpisodes: StateFlow<List<PlayerSeasonEpisodeItem>> = _currentSeasonEpisodes.asStateFlow()

    var exoPlayer: ExoPlayer? by mutableStateOf(null)
        private set
    var mpvPlayer: MpvPlayerController? by mutableStateOf(null)
        private set
    private var activePlayerEngine: String = PlayerPreferences.DEFAULT_PLAYER_ENGINE
    private var playbackSpeedBeforeBoost: Float? = null

    private val trackSelectionCoordinator = PlayerTrackSelection()
    private var playbackSession = PlaybackSessionContext()
    private val playbackReporter = PlayerPlaybackReporter(
        mediaRepository = mediaRepository,
        scope = viewModelScope,
        positionProvider = { getCurrentPosition() },
        isPausedProvider = { !isPlayingNow() }
    )
    private var spatializerHelper: SpatializerHelper? = null
    private var playerContext: Context? = null
    private var apiMediaStreams: List<MediaStream>? = null
    private var defaultAudioStreamIndex: Int? = null
    private var defaultSubtitleStreamIndex: Int? = null
    private var hasHandledPlaybackCompletion = false
    private var videoTranscodingAllowed: Boolean? = null
    private var audioTranscodingAllowed: Boolean? = null
    private var audioDiagnosticsSignature: String? = null
    private var downloadRepository: DownloadRepository? = null
    private var communityPlaybackSegmentsJob: Job? = null
    private var spatialAudioAnalysisJob: Job? = null
    private var currentItemDetails: BaseItemDto? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var seasonEpisodesJob: Job? = null
    private var nextEpisodePrefetchSignature: String? = null
    private var watchPartyPollJob: Job? = null
    private var watchPartyProgressJob: Job? = null
    private var watchPartyReadyWaitJob: Job? = null
    private var latestWatchPartyRoom: RoomDto? = null
    private var forcingWatchPartyPlay = false
    private var applyingRemotePlayback = false
    private var lastAppliedRemotePlaybackAt = 0L
    private var pendingWatchPartyMediaSwitchId: String? = null
    private var hasRenderedFirstFrame = false
    private var mpvExternalSubtitleUrls: Map<Int, String> = emptyMap()

    fun hasActiveWatchPartySession(): Boolean {
        return _watchPartyState.value.isInRoom || WatchPartySessionStore.get() != null
    }

    fun toggleWatchPartyVoiceChat(context: Context) {
        watchPartyVoiceChatClient.toggleMicrophone(context.applicationContext, WatchPartySessionStore.get())
    }

    private fun startWatchPartyVoiceListening(context: Context) {
        watchPartyVoiceChatClient.startListening(context.applicationContext, WatchPartySessionStore.get())
    }

    fun sendWatchPartyChatMessage(content: String) {
        val cleanContent = content.trim().take(240)
        if (cleanContent.isBlank()) return
        val state = _watchPartyState.value
        val roomId = state.roomId ?: return
        val memberId = state.memberId ?: return
        viewModelScope.launch {
            runCatching {
                watchPartyRepository.sendChatMessage(
                    roomId = roomId,
                    memberId = memberId,
                    content = cleanContent
                )
            }.onSuccess { room ->
                latestWatchPartyRoom = room
                updateWatchPartyState(room, memberId)
            }.onFailure { error ->
                Log.w(TAG, "Failed to send watch party chat message", error)
                _watchPartyState.value = _watchPartyState.value.copy(
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                )
            }
        }
    }

    fun prepareWatchPartyNextEpisodeHandoff() {
        val state = _watchPartyState.value
        val activeSession = WatchPartySessionStore.get()
        val session = when {
            activeSession?.roomId == state.roomId && activeSession?.isHost == true -> activeSession
            state.isHost && !state.roomId.isNullOrBlank() && !state.memberId.isNullOrBlank() -> {
                com.grmemby.app.watchparty.ActiveWatchPartySession(
                    roomId = state.roomId,
                    memberId = state.memberId,
                    isHost = true,
                    roomName = state.roomName,
                    inviteText = state.inviteText
                )
            }
            else -> null
        } ?: return
        WatchPartySessionStore.set(session.copy(startPlaybackOnNextPlayer = true))
    }

    private fun isMpvPlayback(): Boolean {
        return activePlayerEngine == PlayerPreferences.PLAYER_ENGINE_MPV
    }

    fun initializePlayer(
        context: Context,
        mediaId: String,
        initialItemDetails: BaseItemDto? = null,
        preferredAudioStreamIndex: Int? = null,
        preferredSubtitleStreamIndex: Int? = null,
        initialSeekPositionMs: Long? = null,
        startPlayback: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val effectiveStartPlayback = if (shouldDeferWatchPartyAutoStart(mediaId)) false else startPlayback
                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = effectiveStartPlayback,
                    hasStartedPlayback = false,
                    error = null
                )
                _playerState.value = _playerState.value.copy(
                    recapStartMs = null,
                    recapEndMs = null,
                    introStartMs = null,
                    introEndMs = null,
                    creditsStartMs = null,
                    creditsEndMs = null,
                    previewStartMs = null,
                    previewEndMs = null,
                    chapterMarkers = emptyList()
                )

                playerContext = context
                hasHandledPlaybackCompletion = false
                val playerPreferences = PlayerPreferences(context)
                activePlayerEngine = playerPreferences.getPlayerEngine()
                val resolvedPreferredAudioStreamIndex = preferredAudioStreamIndex
                    ?: playerPreferences.getPreferredAudioStreamIndex(mediaId)
                val activePreferredSubtitleStreamIndex = preferredSubtitleStreamIndex
                    ?: playerPreferences.getPreferredSubtitleStreamIndex(mediaId)
                val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
                val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
                val audioTranscodeMode = if (isAudioTranscodingAllowed) {
                    playerPreferences.getAudioTranscodeMode()
                } else {
                    AudioTranscodeMode.AUTO
                }
                val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
                    playerPreferences.getMaxStreamingBitrate()
                } else {
                    null
                }
                val maxStreamingHeight = if (isVideoTranscodingAllowed) {
                    playerPreferences.getStreamingQualityMaxHeight()
                } else {
                    null
                }
                trackSelectionCoordinator.resetPendingSelections(
                    preferredAudioStreamIndex = resolvedPreferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = activePreferredSubtitleStreamIndex
                )
                _preferredStreamIndexes.value = PreferredStreamIndexes(
                    audioStreamIndex = resolvedPreferredAudioStreamIndex,
                    subtitleStreamIndex = activePreferredSubtitleStreamIndex
                )

                audioDiagnosticsSignature = null
                currentItemDetails = null
                cancelNextEpisodePrefetch()
                playbackReporter.reset()
                communityPlaybackSegmentsJob?.cancel()
                communityPlaybackSegmentsJob = null
                spatialAudioAnalysisJob?.cancel()
                spatialAudioAnalysisJob = null
                hasRenderedFirstFrame = false
                spatializerHelper = SpatializerHelper(context)
                downloadRepository = DownloadRepositoryProvider.getInstance(context)
                val offlinePath = downloadRepository?.getOfflineFilePath(mediaId)
                val hasOfflineFile = !offlinePath.isNullOrBlank() && File(offlinePath).exists()
                val offlineItemDetails = if (hasOfflineFile) {
                    downloadRepository?.offlineItemMetadata(mediaId)
                } else {
                    null
                }

                // Get item details to check for resume position
                val itemDetails = if (initialItemDetails?.id == mediaId) {
                    initialItemDetails
                } else if (hasOfflineFile) {
                    offlineItemDetails ?: mediaRepository.getItemById(mediaId).getOrNull()
                } else {
                    mediaRepository.getItemById(mediaId).getOrNull()
                }
                currentItemDetails = itemDetails
                refreshCurrentSeasonEpisodes(itemDetails, mediaId)
                val resumePositionTicks = itemDetails?.userData?.playbackPositionTicks
                val storedResumePositionMs = if (resumePositionTicks != null && resumePositionTicks > 0) {
                    resumePositionTicks / 10000L
                } else {
                    null
                }
                val mediaTitle = itemDetails?.name ?: "Unknown Title"
                val isEpisodeItem = itemDetails?.type.equals("Episode", ignoreCase = true)
                val resolvedSeriesItem = if (isEpisodeItem && itemDetails?.seriesName.isNullOrBlank()) {
                    itemDetails?.seriesId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { seriesId -> mediaRepository.getItemById(seriesId).getOrNull() }
                } else {
                    null
                }
                val seriesTitle = if (isEpisodeItem) {
                    itemDetails?.seriesName?.takeIf { it.isNotBlank() }
                        ?: resolvedSeriesItem?.name?.takeIf { it.isNotBlank() }
                        ?: resolvedSeriesItem?.originalTitle?.takeIf { it.isNotBlank() }
                        ?: mediaTitle
                } else {
                    mediaTitle
                }
                val logoSourceId = when {
                    itemDetails?.imageTags?.containsKey("Logo") == true && !itemDetails.id.isNullOrBlank() -> itemDetails.id
                    !itemDetails?.parentLogoItemId.isNullOrBlank() && !itemDetails?.parentLogoImageTag.isNullOrBlank() -> itemDetails?.parentLogoItemId
                    else -> null
                }
                val mediaLogoUrl = logoSourceId?.let { sourceId ->
                    mediaRepository.getImageUrlString(
                        itemId = sourceId,
                        imageType = "Logo",
                        width = 320,
                        quality = 90,
                        enableImageEnhancers = false
                    )
                }
                val seasonEpisodeLabel = itemDetails?.let { item ->
                    val isEpisodeItem = item.type.equals("Episode", ignoreCase = true)
                    val season = item.parentIndexNumber
                    val episode = item.indexNumber
                    if (isEpisodeItem && season != null && episode != null) {
                        val episodeName = item.episodeTitle
                            ?.takeIf { it.isNotBlank() }
                            ?: item.name?.takeIf { it.isNotBlank() }
                        buildString {
                            append("S")
                            append(season)
                            append(":E")
                            append(episode)
                            episodeName?.let {
                                append(" - ")
                                append(it)
                            }
                        }
                    } else {
                        null
                    }
                }
                val chapterMarkers = PlaybackMarkerUtils.buildChapterMarkers(itemDetails?.chapters)
                val playerStartPositionMs = initialSeekPositionMs ?: storedResumePositionMs
                val introSegment = PlaybackMarkerUtils.extractIntroWindow(itemDetails?.chapters)

                var primaryMediaSource: MediaSource? = null
                var sessionPlaySessionId: String? = null
                var sessionMediaSourceId: String? = null
                var sessionMediaSourceContainer: String? = null
                var sessionMediaSourceBitrateKbps: Int? = null
                var sessionPlayMethod = PlayMethod.DIRECT_PLAY
                var sessionIsOfflinePlayback = false
                var streamingMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
                var playbackRequest: PlaybackRequest? = null
                defaultAudioStreamIndex = null
                defaultSubtitleStreamIndex = null

                val mediaItem = if (hasOfflineFile) {
                    val localFilePath = requireNotNull(offlinePath)
                    sessionIsOfflinePlayback = true
                    sessionPlayMethod = PlayMethod.OFFLINE
                    MediaItem.fromUri(Uri.fromFile(File(localFilePath)))
                } else {
                    sessionIsOfflinePlayback = false

                    // Get playback info first to obtain session details
                    val playbackInfoResult = mediaRepository.getPlaybackInfo(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        subtitleStreamIndex = activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode
                    )
                    if (playbackInfoResult.isFailure) {
                        val error = playbackInfoResult.exceptionOrNull()?.message ?: "Failed to get playback info"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    val playbackInfo = playbackInfoResult.getOrNull()
                    if (playbackInfo == null) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Playback info is null")
                        return@launch
                    }

                    primaryMediaSource = playbackInfo.mediaSources?.firstOrNull()
                    defaultAudioStreamIndex = primaryMediaSource?.defaultAudioStreamIndex
                    defaultSubtitleStreamIndex = primaryMediaSource?.defaultSubtitleStreamIndex
                    sessionPlaySessionId = playbackInfo.playSessionId
                    sessionMediaSourceId = primaryMediaSource?.id
                    sessionMediaSourceContainer = primaryMediaSource?.container
                    sessionMediaSourceBitrateKbps = primaryMediaSource?.bitrate?.div(1000)
                    sessionPlayMethod = when {
                        primaryMediaSource?.supportsDirectPlay == true -> PlayMethod.DIRECT_PLAY
                        primaryMediaSource?.supportsDirectStream == true -> PlayMethod.DIRECT_STREAM
                        else -> PlayMethod.TRANSCODE
                    }
                    val playbackRequestResult = mediaRepository.getPlaybackRequest(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        maxStreamingHeight = maxStreamingHeight,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        subtitleStreamIndex = activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode,
                        playbackInfo = playbackInfo,
                        includeAccessToken = true
                    )
                    if (playbackRequestResult.isFailure) {
                        val error = playbackRequestResult.exceptionOrNull()?.message ?: "Failed to get playback request"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    playbackRequest = playbackRequestResult.getOrNull()
                    val streamingUrl = playbackRequest?.url
                    if (streamingUrl.isNullOrEmpty()) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Failed to get playback URL")
                        return@launch
                    }
                    val streamUri = Uri.parse(streamingUrl)
                    val streamPlaySessionId = streamUri.getQueryParameter("PlaySessionId")
                        ?: streamUri.getQueryParameter("playSessionId")
                    if (!streamPlaySessionId.isNullOrBlank()) {
                        sessionPlaySessionId = streamPlaySessionId
                    }
                    sessionPlayMethod = getPlayMethod(
                        streamingUrl = streamingUrl,
                        fallback = sessionPlayMethod
                    )

                    val activeSubtitleStreamIndex = (
                        activePreferredSubtitleStreamIndex
                            ?: primaryMediaSource?.defaultSubtitleStreamIndex
                        )?.takeIf { it >= 0 }
                    val activeSubtitleStream = primaryMediaSource
                        ?.mediaStreams
                        ?.firstOrNull { stream ->
                            stream.type == "Subtitle" &&
                                stream.index == activeSubtitleStreamIndex
                        }

                    val streamingMediaItem = streamingMediaItem(
                        streamingUrl = streamingUrl,
                        selectedSubtitleStream = activeSubtitleStream
                    )
                    if (!isMpvPlayback()) {
                        streamingMediaSource = PlayerUtils.createStreamingMediaSource(
                            context = context,
                            mediaItem = streamingMediaItem,
                            requestHeaders = playbackRequest?.requestHeaders.orEmpty()
                        )
                    }
                    streamingMediaItem
                }

                playbackSession = PlaybackSessionContext(
                    mediaId = mediaId,
                    playSessionId = sessionPlaySessionId,
                    mediaSourceId = sessionMediaSourceId,
                    mediaSourceContainer = sessionMediaSourceContainer,
                    mediaSourceBitrateKbps = sessionMediaSourceBitrateKbps,
                    playMethod = sessionPlayMethod,
                    isOfflinePlayback = sessionIsOfflinePlayback
                )
                playbackReporter.updateSession(playbackSession)

                // Get media info for spatial audio analysis
                apiMediaStreams = PlayerTrack.resolveApiMediaStreams(
                    itemDetails = itemDetails,
                    playbackMediaSource = primaryMediaSource
                )
                mpvExternalSubtitleUrls = MPVPlayer.externalSubtitleUrls(
                    playbackRequest = playbackRequest,
                    mediaStreams = apiMediaStreams.orEmpty()
                )

                if (isMpvPlayback()) {
                    val selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex
                        ?: defaultAudioStreamIndex
                    val selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex
                        ?: defaultSubtitleStreamIndex
                    mpvPlayer = createMpvPlayer(context).also { player ->
                        player.load(
                            url = mediaItem.localConfiguration?.uri?.toString().orEmpty(),
                            requestHeaders = playbackRequest?.requestHeaders.orEmpty(),
                            subtitleUrls = mpvExternalSubtitleUrls.values.toList(),
                            audioTrackId = MPVPlayer.audioTrackId(
                                apiMediaStreams,
                                selectedAudioStreamIndex
                            ),
                            subtitleTrackId = MPVPlayer.subtitleTrackId(
                                apiMediaStreams,
                                selectedSubtitleStreamIndex
                            ),
                            selectedSubtitleUrl = selectedSubtitleStreamIndex?.let(
                                mpvExternalSubtitleUrls::get
                            ),
                            startPositionMs = playerStartPositionMs,
                            startPlayback = effectiveStartPlayback
                        )
                        player.setPlaybackSpeed(_playerState.value.playbackSpeed)
                    }
                } else {
                    exoPlayer = PlayerUtils.createPlayer(
                        context = context
                    )
                    exoPlayer?.apply {
                        addListener(playerListener)
                        if (streamingMediaSource != null) {
                            setMediaSource(streamingMediaSource!!)
                        } else {
                            setMediaItem(mediaItem)
                        }
                        setPlaybackSpeed(_playerState.value.playbackSpeed)
                        prepare()

                        if (playerStartPositionMs != null && playerStartPositionMs > 0) {
                            seekTo(playerStartPositionMs)
                        }

                        playWhenReady = effectiveStartPlayback
                    }
                }

                // Spatial audio analysis and device capabilities
                val usesMpv = isMpvPlayback()
                if (!usesMpv) {
                    updateTrackInformation()
                }
                val isHdrPlayback = if (usesMpv) {
                    MPVPlayer.isHdr(apiMediaStreams)
                } else {
                    PlayerMetadata.isCurrentPlaybackHdr(exoPlayer)
                }

                // Apply start maximized setting if enabled
                applyStartMaximizedSetting(context)

                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = effectiveStartPlayback,
                    hasStartedPlayback = false,
                    mediaTitle = mediaTitle,
                    seriesTitle = seriesTitle,
                    mediaLogoUrl = mediaLogoUrl,
                    seasonEpisodeLabel = seasonEpisodeLabel,
                    danmakuFileName = resolveDanmakuFileName(
                        itemDetails = itemDetails,
                        primaryMediaSource = primaryMediaSource,
                        offlinePath = offlinePath,
                        fallback = mediaTitle
                    ),
                    danmakuHash = resolveDanmakuHash(
                        itemDetails = itemDetails,
                        primaryMediaSource = primaryMediaSource
                    ),
                    chapterMarkers = chapterMarkers,
                    introStartMs = introSegment?.startMs,
                    introEndMs = introSegment?.endMs,
                    isVideoTranscodingAllowed = isVideoTranscodingAllowed,
                    isAudioTranscodingAllowed = isAudioTranscodingAllowed,
                    currentAudioTranscodeMode = audioTranscodeMode,
                    spatializationResult = null,
                    isSpatialAudioEnabled = false,
                    spatialAudioFormat = "",
                    isHdrEnabled = isHdrPlayback
                )
                if (usesMpv) {
                    updateApiTrackInformation()
                }
                if (itemDetails != null) {
                    applyCommunityPlaybackSegments(mediaId = mediaId, itemDetails = itemDetails)
                }
                analyzeSpatialAudioAsync(
                    context = context,
                    mediaId = mediaId,
                    mediaStreams = apiMediaStreams,
                    helper = spatializerHelper
                )

                adoptActiveWatchPartySession(
                    context = context,
                    mediaId = mediaId,
                    title = buildWatchPartyDisplayTitle(itemDetails, mediaTitle, seasonEpisodeLabel)
                )

            } catch (e: Exception) {
                Log.e(TAG, "Player initialization failed", e)
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    private fun refreshCurrentSeasonEpisodes(itemDetails: BaseItemDto?, currentMediaId: String) {
        seasonEpisodesJob?.cancel()
        _currentSeasonEpisodes.value = emptyList()

        val seriesId = itemDetails?.seriesId?.takeIf { it.isNotBlank() } ?: return
        val seasonId = itemDetails.seasonId?.takeIf { it.isNotBlank() }
        if (!itemDetails.type.equals("Episode", ignoreCase = true)) return

        seasonEpisodesJob = viewModelScope.launch(Dispatchers.IO) {
            val episodes = mediaRepository.getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                limit = null,
                startIndex = null
            ).getOrNull().orEmpty()
                .filter { !it.id.isNullOrBlank() }
                .sortedWith(
                    compareBy<BaseItemDto> { it.parentIndexNumber ?: Int.MAX_VALUE }
                        .thenBy { it.indexNumber ?: Int.MAX_VALUE }
                        .thenBy { it.name.orEmpty() }
                )

            _currentSeasonEpisodes.value = episodes.mapNotNull { episode ->
                val episodeId = episode.id ?: return@mapNotNull null
                val seasonNumber = episode.parentIndexNumber ?: itemDetails.parentIndexNumber
                val episodeNumber = episode.indexNumber
                val displayName = episode.episodeTitle
                    ?.takeIf { it.isNotBlank() }
                    ?: episode.name?.takeIf { it.isNotBlank() }
                    ?: "第${episodeNumber ?: 0}集"
                val titlePrefix = when {
                    seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
                    episodeNumber != null -> "E${episodeNumber}"
                    else -> null
                }
                val title = titlePrefix?.let { "$it - $displayName" } ?: displayName
                val imageUrl = mediaRepository.getImageUrl(
                    itemId = episodeId,
                    imageType = "Primary",
                    width = 360,
                    height = 202,
                    quality = 90,
                    enableImageEnhancers = false,
                    imageTag = episode.imageTagFor("Primary", targetItemId = episodeId)
                ).first()

                PlayerSeasonEpisodeItem(
                    id = episodeId,
                    title = title,
                    durationLabel = formatEpisodeRuntime(episode.runTimeTicks),
                    thumbnailUrl = imageUrl,
                    isCurrent = episodeId == currentMediaId,
                    isPlayed = episode.userData?.played == true
                )
            }
        }
    }

    private fun shouldDeferWatchPartyAutoStart(mediaId: String): Boolean {
        val activeSession = WatchPartySessionStore.get() ?: return false
        if (activeSession.roomId.isBlank() || activeSession.memberId.isBlank()) return false
        if (activeSession.startPlaybackOnNextPlayer) return true
        val currentWatchPartyState = _watchPartyState.value
        if (currentWatchPartyState.roomId != activeSession.roomId) return true
        val latestRoom = latestWatchPartyRoom
        if (!activeSession.isHost && latestRoom?.media?.itemId == mediaId) {
            val playback = latestRoom.playback
            return !playback.isPlaying && playback.event != PlaybackEvent.PLAY
        }
        return false
    }

    private fun analyzeSpatialAudioAsync(
        context: Context,
        mediaId: String,
        mediaStreams: List<MediaStream>?,
        helper: SpatializerHelper?
    ) {
        spatialAudioAnalysisJob?.cancel()
        val primaryAudioStream = mediaStreams
            ?.firstOrNull { it.type == "Audio" }
            ?: return

        spatialAudioAnalysisJob = viewModelScope.launch {
            val spatializationResult = withContext(Dispatchers.Default) {
                CodecCapabilityManager.canSpatializeAudioStream(
                    context = context,
                    audioStream = primaryAudioStream,
                    spatializerHelper = helper
                )
            }
            if (playbackSession.mediaId != mediaId) return@launch

            _playerState.value = _playerState.value.copy(
                spatializationResult = spatializationResult,
                isSpatialAudioEnabled = spatializationResult.canSpatialize,
                spatialAudioFormat = spatializationResult.spatialFormat
            )
        }
    }

    private fun applyCommunityPlaybackSegments(mediaId: String, itemDetails: BaseItemDto) {
        if (!itemDetails.type.equals("Episode", ignoreCase = true)) return

        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = viewModelScope.launch {
            val playbackSegments = mediaRepository.getCommunityPlaybackSegments(itemDetails).getOrNull()
                ?: return@launch
            if (playbackSession.mediaId != mediaId) return@launch

            val currentState = _playerState.value
            _playerState.value = currentState.copy(
                recapStartMs = currentState.recapStartMs ?: playbackSegments.recap?.startMs,
                recapEndMs = currentState.recapEndMs ?: playbackSegments.recap?.endMs,
                introStartMs = currentState.introStartMs ?: playbackSegments.intro?.startMs,
                introEndMs = currentState.introEndMs ?: playbackSegments.intro?.endMs,
                creditsStartMs = currentState.creditsStartMs ?: playbackSegments.credits?.startMs,
                creditsEndMs = currentState.creditsEndMs ?: playbackSegments.credits?.endMs,
                previewStartMs = currentState.previewStartMs ?: playbackSegments.preview?.startMs,
                previewEndMs = currentState.previewEndMs ?: playbackSegments.preview?.endMs
            )
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        mpvPlayer?.seekTo(position)
        _playerState.value = _playerState.value.copy(currentPosition = position)
        sendWatchPartyPlayback(PlaybackEvent.SEEK, explicitMemberControl = true)
    }

    fun play() {
        if (!forcingWatchPartyPlay && beginHostReadyBarrierIfNeeded()) return
        forceLocalPlayAndPublish()
    }

    private fun forceLocalPlayAndPublish() {
        forcingWatchPartyPlay = true
        try {
            exoPlayer?.play()
            mpvPlayer?.play()
            _playerState.value = _playerState.value.copy(
                isPlaying = isPlayingNow(),
                playWhenReady = true,
                isLoading = false
            )
            sendWatchPartyPlayback(PlaybackEvent.PLAY, explicitMemberControl = true)
        } finally {
            forcingWatchPartyPlay = false
        }
    }

    private fun beginHostReadyBarrierIfNeeded(): Boolean {
        val state = _watchPartyState.value
        if (!state.isHost || state.memberCount <= 1) return false
        val room = latestWatchPartyRoom ?: return false
        val mediaId = room.media?.itemId?.takeIf { it.isNotBlank() } ?: playbackSession.mediaId ?: return false
        val guestMembers = room.members.filterNot { it.id == state.memberId }
        if (guestMembers.isEmpty()) return false

        if (watchPartyMembersReady(room, mediaId)) return false

        exoPlayer?.pause()
        mpvPlayer?.pause()
        _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
        _watchPartyState.value = _watchPartyState.value.copy(
            errorMessage = "正在等待成员预加载，准备好后会自动开始播放"
        )
        sendWatchPartyPlayback(PlaybackEvent.PREPARE)
        markWatchPartyReadyIfPossible()
        watchPartyReadyWaitJob?.cancel()
        watchPartyReadyWaitJob = viewModelScope.launch {
            var latest = room
            var waitTicks = 0
            while (true) {
                if (watchPartyMembersReady(latest, mediaId)) {
                    _watchPartyState.value = _watchPartyState.value.copy(errorMessage = null)
                    forceLocalPlayAndPublish()
                    return@launch
                }
                if (shouldForceWatchPartyPlaybackAfterReadyWait(waitTicks)) {
                    _watchPartyState.value = _watchPartyState.value.copy(
                        errorMessage = "部分成员预加载较慢，已先开始同步播放"
                    )
                    forceLocalPlayAndPublish()
                    return@launch
                }
                delay(1_000L)
                latest = runCatching { watchPartyRepository.getRoom(state.roomId ?: return@launch) }
                    .onFailure { error ->
                        _watchPartyState.value = _watchPartyState.value.copy(
                            errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                        )
                    }
                    .getOrElse { latest }
                latestWatchPartyRoom = latest
                waitTicks += 1
            }
        }
        return true
    }

    private fun watchPartyMembersReady(room: RoomDto, mediaId: String): Boolean {
        return room.areAllWatchPartyMembersReadyFor(mediaId)
    }

    private fun markWatchPartyReadyIfPossible() {
        val state = _watchPartyState.value
        if (!state.isInRoom) return
        val mediaId = playbackSession.mediaId?.takeIf { it.isNotBlank() } ?: return
        val roomMediaId = latestWatchPartyRoom?.media?.itemId?.takeIf { it.isNotBlank() }
        if (roomMediaId != null && roomMediaId != mediaId) return
        if (!isWatchPartyMediaPreloaded()) return
        sendWatchPartyPlayback(PlaybackEvent.READY)
    }

    private fun isWatchPartyMediaPreloaded(): Boolean {
        exoPlayer?.let { player -> return player.playbackState == Player.STATE_READY }
        mpvPlayer ?: return false
        return !_playerState.value.isLoading
    }

    fun pause() {
        exoPlayer?.pause()
        mpvPlayer?.pause()
        _playerState.value = _playerState.value.copy(
            isPlaying = false,
            playWhenReady = false,
            isLoading = false
        )
        persistPosition()
        sendWatchPartyPlayback(PlaybackEvent.PAUSE, explicitMemberControl = true)
    }

    fun seekToProgress(progress: Float) {
        val duration = getDuration()
        if (duration > 0L) {
            seekTo((duration * progress).toLong())
        }
    }

    fun seekBy(deltaMs: Long) {
        val currentPosition = getCurrentPosition()
        val duration = getDuration()
        val targetPosition = if (duration > 0L) {
            (currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (currentPosition + deltaMs).coerceAtLeast(0L)
        }
        seekTo(targetPosition)
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: mpvPlayer?.currentPosition ?: 0L

    fun isPlayingNow(): Boolean = exoPlayer?.isPlaying == true || mpvPlayer?.isPlaying == true

    fun getDuration(): Long = exoPlayer?.duration?.coerceAtLeast(0L) ?: mpvPlayer?.duration ?: 0L

    fun getBufferedPosition(): Long {
        val duration = getDuration()
        val currentPosition = getCurrentPosition().coerceAtLeast(0L)
        val rawBufferedPosition = exoPlayer?.bufferedPosition
            ?: mpvPlayer?.bufferedPosition
            ?: currentPosition
        val bufferedPosition = rawBufferedPosition.coerceAtLeast(currentPosition)
        return if (duration > 0L) {
            bufferedPosition.coerceIn(0L, duration)
        } else {
            bufferedPosition.coerceAtLeast(0L)
        }
    }

    private fun resolveDanmakuFileName(
        itemDetails: BaseItemDto?,
        primaryMediaSource: MediaSource?,
        offlinePath: String?,
        fallback: String
    ): String {
        val raw = listOfNotNull(
            offlinePath,
            primaryMediaSource?.path,
            itemDetails?.path,
            primaryMediaSource?.name,
            itemDetails?.originalTitle,
            itemDetails?.name,
            fallback
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { fallback }
    }

    private fun resolveDanmakuHash(
        itemDetails: BaseItemDto?,
        primaryMediaSource: MediaSource?
    ): String? {
        return listOfNotNull(
            primaryMediaSource?.eTag,
            primaryMediaSource?.id,
            itemDetails?.etag,
            itemDetails?.id
        ).firstOrNull { it.isNotBlank() }
    }

    fun createWatchPartyRoom(context: Context, mediaId: String, title: String) {
        if (_watchPartyState.value.isLoading) return
        WatchPartySessionStore.get()?.let { existingSession ->
            _watchPartyState.value = _watchPartyState.value.copy(
                roomId = existingSession.roomId,
                memberId = existingSession.memberId,
                isHost = existingSession.isHost,
                roomName = existingSession.roomName,
                inviteText = copyableWatchPartyInviteText(
                    roomId = existingSession.roomId,
                    inviteText = existingSession.inviteText
                ),
                isLoading = false,
                errorMessage = "已有房间：${existingSession.roomId}"
            )
            startWatchPartyVoiceListening(context)
            return
        }
        val safeTitle = title.takeIf { it.isNotBlank() }
            ?: buildWatchPartyDisplayTitle(
                currentItemDetails,
                currentItemDetails?.name ?: "Grmemby",
                _playerState.value.seasonEpisodeLabel
            )
        viewModelScope.launch {
            _watchPartyState.value = _watchPartyState.value.copy(isLoading = true, errorMessage = null)
            runCatching {
                val activeSessionSnapshot = AuthRepositoryProvider.getInstance(context).getActiveSessionSnapshot()
                val created = watchPartyRepository.createRoom(
                    name = safeTitle,
                    hostName = "Host",
                    memberId = WatchPartyDeviceIdentity.memberId(context),
                    serverUrl = activeSessionSnapshot.serverUrl,
                    serverName = activeSessionSnapshot.serverName
                )
                val selected = watchPartyRepository.selectMedia(
                    roomId = created.room.id,
                    memberId = created.memberId,
                    itemId = mediaId,
                    title = safeTitle
                )
                val room = selected.room ?: created.room
                latestWatchPartyRoom = room
                updateWatchPartyState(room, created.memberId)
                WatchPartySessionStore.set(
                    com.grmemby.app.watchparty.ActiveWatchPartySession(
                        roomId = room.id,
                        memberId = created.memberId,
                        isHost = true,
                        roomName = room.name,
                        inviteText = _watchPartyState.value.inviteText
                    )
                )
                startWatchPartySync(room.id, created.memberId)
                startWatchPartyVoiceListening(context)
                sendWatchPartyPlayback(PlaybackEvent.PROGRESS)
            }.onFailure { error ->
                Log.w(TAG, "Failed to create watch party room", error)
                _watchPartyState.value = _watchPartyState.value.copy(
                    isLoading = false,
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                )
            }
        }
    }

    fun joinWatchPartyRoom(context: Context, roomId: String) {
        val cleanRoomId = roomId.trim()
        val currentState = _watchPartyState.value
        if (cleanRoomId.isBlank() || currentState.isLoading) return
        _watchPartyState.value = currentState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                val existingSession = WatchPartySessionStore.get()?.takeIf { it.roomId == cleanRoomId }
                val activeSessionSnapshot = AuthRepositoryProvider.getInstance(context).getActiveSessionSnapshot()
                val roomPreview = watchPartyRepository.getRoom(cleanRoomId)
                roomPreview.sameServerJoinFailureMessage(
                    activeServerUrl = activeSessionSnapshot.serverUrl,
                    savedServerUrls = activeSessionSnapshot.savedServers.map { it.serverUrl }
                )?.let { message ->
                    throw IllegalStateException(message)
                }
                val joined = watchPartyRepository.joinRoom(
                    roomId = cleanRoomId,
                    name = if (existingSession?.isHost == true) "Host" else "Guest",
                    memberId = existingSession?.memberId ?: WatchPartyDeviceIdentity.memberId(context),
                    serverUrl = activeSessionSnapshot.serverUrl,
                    serverName = activeSessionSnapshot.serverName
                )
                val roomMediaId = joined.room.media?.itemId
                val currentMediaId = playbackSession.mediaId
                latestWatchPartyRoom = joined.room
                updateWatchPartyState(joined.room, joined.memberId)
                WatchPartySessionStore.set(
                    com.grmemby.app.watchparty.ActiveWatchPartySession(
                        roomId = joined.room.id,
                        memberId = joined.memberId,
                        isHost = joined.room.hostMemberId == joined.memberId,
                        roomName = joined.room.name,
                        inviteText = _watchPartyState.value.inviteText
                    )
                )
                startWatchPartySync(joined.room.id, joined.memberId)
                startWatchPartyVoiceListening(context)
                applyRemotePlayback(joined.room)
                if (!roomMediaId.isNullOrBlank() && !currentMediaId.isNullOrBlank() && roomMediaId != currentMediaId) {
                    _watchPartyState.value = _watchPartyState.value.copy(
                        errorMessage = "房间正在播放其他媒体，请先打开同一影片后再同步"
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to join watch party room", error)
                _watchPartyState.value = _watchPartyState.value.copy(
                    isLoading = false,
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                )
            }
        }
    }

    fun leaveOrDisbandWatchParty() {
        val state = _watchPartyState.value
        val roomId = state.roomId ?: return
        val memberId = state.memberId ?: return
        viewModelScope.launch {
            _watchPartyState.value = state.copy(isLoading = true, errorMessage = null)
            runCatching {
                if (state.isHost) {
                    watchPartyRepository.disbandRoom(roomId, memberId)
                } else {
                    watchPartyRepository.leaveRoom(roomId, memberId)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to leave watch party room", error)
            }
            clearWatchPartySession()
        }
    }

    private fun startWatchPartySync(roomId: String, memberId: String) {
        watchPartyPollJob?.cancel()
        watchPartyProgressJob?.cancel()
        lastAppliedRemotePlaybackAt = 0L
        watchPartyPollJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                runCatching { watchPartyRepository.getRoom(roomId) }
                    .onSuccess { room ->
                        latestWatchPartyRoom = room
                        updateWatchPartyState(room, memberId)
                        applyRemotePlayback(room)
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Watch party polling failed; keeping local watch party session for retry", error)
                        _watchPartyState.value = _watchPartyState.value.copy(
                            isLoading = false,
                            errorMessage = "一起看连接波动，正在重连…"
                        )
                    }
            }
        }
        watchPartyProgressJob = viewModelScope.launch {
            while (true) {
                delay(3_000L)
                sendWatchPartyPlayback(PlaybackEvent.PROGRESS)
            }
        }
    }

    private fun updateWatchPartyState(room: RoomDto, memberId: String) {
        val isHost = room.hostMemberId == memberId
        _watchPartyState.value = WatchPartyUiState(
            roomId = room.id,
            memberId = memberId,
            isHost = isHost,
            roomName = room.name,
            mediaTitle = room.media?.title,
            memberCount = room.members.size,
            chatMessages = room.chatMessages,
            inviteText = copyableWatchPartyInviteText(room.id),
            isLoading = false,
            errorMessage = _watchPartyState.value.errorMessage
        )
    }

    private fun clearWatchPartySession(message: String? = null) {
        watchPartyPollJob?.cancel()
        watchPartyPollJob = null
        watchPartyProgressJob?.cancel()
        watchPartyProgressJob = null
        watchPartyReadyWaitJob?.cancel()
        watchPartyReadyWaitJob = null
        latestWatchPartyRoom = null
        lastAppliedRemotePlaybackAt = 0L
        pendingWatchPartyMediaSwitchId = null
        WatchPartySessionStore.clear(_watchPartyState.value.roomId)
        watchPartyVoiceChatClient.stop()
        _watchPartyState.value = WatchPartyUiState(errorMessage = message)
    }

    private fun sendWatchPartyPlayback(event: PlaybackEvent, explicitMemberControl: Boolean = false) {
        if (applyingRemotePlayback) return
        val state = _watchPartyState.value
        if (!canPublishWatchPartyPlayback(state, event, explicitMemberControl)) return
        val roomId = state.roomId ?: return
        val memberId = state.memberId ?: return
        if (event == PlaybackEvent.PROGRESS && !isWatchPartyPlaybackActive()) return
        val mediaId = playbackSession.mediaId?.takeIf { it.isNotBlank() }
        val syncIsPlaying = when (event) {
            PlaybackEvent.PLAY -> true
            PlaybackEvent.PAUSE, PlaybackEvent.PREPARE, PlaybackEvent.READY, PlaybackEvent.EXIT -> false
            else -> isWatchPartyPlaybackActive()
        }
        val positionMs = getCurrentPosition()
        viewModelScope.launch {
            runCatching {
                publishWatchPartyPlayback(
                    roomId = roomId,
                    memberId = memberId,
                    mediaId = mediaId,
                    event = event,
                    positionMs = positionMs,
                    isPlaying = syncIsPlaying
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to send watch party playback", error)
                _watchPartyState.value = _watchPartyState.value.copy(
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                )
            }
        }
    }

    private fun canPublishWatchPartyPlayback(
        state: WatchPartyUiState,
        event: PlaybackEvent,
        explicitMemberControl: Boolean
    ): Boolean {
        if (state.isHost || event == PlaybackEvent.READY) return true
        if (!explicitMemberControl) return false
        return event == PlaybackEvent.PLAY || event == PlaybackEvent.PAUSE || event == PlaybackEvent.SEEK
    }

    private suspend fun publishWatchPartyPlayback(
        roomId: String,
        memberId: String,
        mediaId: String?,
        event: PlaybackEvent,
        positionMs: Long,
        isPlaying: Boolean
    ): RoomDto {
        return watchPartyRepository.updatePlayback(
            roomId = roomId,
            memberId = memberId,
            mediaId = mediaId,
            event = event,
            positionMs = positionMs,
            isPlaying = isPlaying
        )
    }

    private fun applyRemotePlayback(room: RoomDto) {
        val state = _watchPartyState.value
        val memberId = state.memberId ?: return
        val roomMediaId = room.media?.itemId?.takeIf { it.isNotBlank() }
        val currentMediaId = playbackSession.mediaId?.takeIf { it.isNotBlank() }
        val playback = room.playback
        if (
            !state.isHost &&
            playback.event == PlaybackEvent.EXIT &&
            playback.updatedBy != null &&
            playback.updatedBy != memberId &&
            playback.updatedAt > lastAppliedRemotePlaybackAt
        ) {
            lastAppliedRemotePlaybackAt = playback.updatedAt
            applyingRemotePlayback = true
            try {
                exoPlayer?.pause()
                mpvPlayer?.pause()
                _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
            } finally {
                applyingRemotePlayback = false
            }
            _watchPartyExitToRoomEvents.tryEmit(Unit)
            return
        }
        if (!state.isHost && roomMediaId != null && currentMediaId != roomMediaId) {
            if (currentMediaId != null && pendingWatchPartyMediaSwitchId != roomMediaId) {
                pendingWatchPartyMediaSwitchId = roomMediaId
                applyingRemotePlayback = true
                try {
                    exoPlayer?.pause()
                    mpvPlayer?.pause()
                    _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
                } finally {
                    applyingRemotePlayback = false
                }
                _watchPartyMediaSwitchEvents.tryEmit(roomMediaId)
            }
            return
        }
        if (!state.isHost && roomMediaId != null && playbackSession.mediaId == roomMediaId && room.playback.event == PlaybackEvent.PREPARE) {
            applyingRemotePlayback = true
            try {
                exoPlayer?.pause()
                mpvPlayer?.pause()
                _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
            } finally {
                applyingRemotePlayback = false
            }
            markWatchPartyReadyIfPossible()
            return
        }
        if (roomMediaId == currentMediaId) {
            pendingWatchPartyMediaSwitchId = null
        }
        if (playback.updatedBy == null || playback.updatedBy == memberId) return
        if (playback.updatedAt <= lastAppliedRemotePlaybackAt) return
        if (roomMediaId != null && currentMediaId != roomMediaId) return
        if (exoPlayer == null && mpvPlayer == null) return
        lastAppliedRemotePlaybackAt = playback.updatedAt

        applyingRemotePlayback = true
        try {
            val targetPosition = playback.positionMs.coerceAtLeast(0L)
            val localPosition = getCurrentPosition()
            if (playback.event == PlaybackEvent.SEEK || abs(localPosition - targetPosition) > 2_500L) {
                seekTo(targetPosition)
            }
            if (playback.isPlaying && !isWatchPartyPlaybackActive()) {
                play()
            } else if (!playback.isPlaying && isWatchPartyPlaybackActive()) {
                pause()
            }
        } finally {
            applyingRemotePlayback = false
        }
    }

    private fun isWatchPartyPlaybackActive(): Boolean {
        exoPlayer?.let { player ->
            return player.playWhenReady
        }
        return _playerState.value.playWhenReady || isPlayingNow()
    }


    private fun buildWatchPartyDisplayTitle(
        itemDetails: BaseItemDto?,
        fallbackTitle: String,
        seasonEpisodeLabel: String?
    ): String {
        val cleanFallback = fallbackTitle.takeIf { it.isNotBlank() } ?: "Grmemby"
        if (itemDetails?.type.equals("Episode", ignoreCase = true)) {
            val seriesName = itemDetails?.seriesName?.takeIf { it.isNotBlank() }
            val season = itemDetails?.parentIndexNumber
            val episode = itemDetails?.indexNumber
            val episodeName = itemDetails?.episodeTitle?.takeIf { it.isNotBlank() }
                ?: itemDetails?.name?.takeIf { it.isNotBlank() }
            val seasonEpisodeText = when {
                season != null && episode != null -> "第${season}季 第${episode}集"
                season != null -> "第${season}季"
                episode != null -> "第${episode}集"
                !seasonEpisodeLabel.isNullOrBlank() -> seasonEpisodeLabel
                else -> null
            }
            return listOfNotNull(seriesName, seasonEpisodeText, episodeName)
                .distinct()
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }
                ?: cleanFallback
        }
        return cleanFallback
    }

    private fun adoptActiveWatchPartySession(context: Context, mediaId: String, title: String) {
        val activeSession = WatchPartySessionStore.get() ?: return
        val cleanMediaId = mediaId.takeIf { it.isNotBlank() } ?: return
        val alreadyAdopted = _watchPartyState.value.roomId == activeSession.roomId
        val cachedRoom = latestWatchPartyRoom?.takeIf { it.id == activeSession.roomId }
        if (alreadyAdopted && (!activeSession.isHost || cachedRoom?.media?.itemId == cleanMediaId)) {
            startWatchPartyVoiceListening(context)
            return
        }
        viewModelScope.launch {
            runCatching {
                var room = cachedRoom ?: watchPartyRepository.getRoom(activeSession.roomId)
                if (activeSession.isHost && room.media?.itemId != cleanMediaId) {
                    room = watchPartyRepository.selectMedia(
                        roomId = activeSession.roomId,
                        memberId = activeSession.memberId,
                        itemId = cleanMediaId,
                        title = title
                    ).room ?: room
                }
                latestWatchPartyRoom = room
                updateWatchPartyState(room, activeSession.memberId)
                if (activeSession.startPlaybackOnNextPlayer) {
                    WatchPartySessionStore.set(activeSession.copy(startPlaybackOnNextPlayer = false))
                }
                if (!alreadyAdopted) {
                    startWatchPartySync(activeSession.roomId, activeSession.memberId)
                }
                startWatchPartyVoiceListening(context)
                if (activeSession.isHost && activeSession.startPlaybackOnNextPlayer) {
                    if (!beginHostReadyBarrierIfNeeded()) {
                        forceLocalPlayAndPublish()
                    }
                } else if (!activeSession.isHost) {
                    applyRemotePlayback(room)
                }
                sendWatchPartyPlayback(PlaybackEvent.PROGRESS)
            }.onFailure { error ->
                Log.w(TAG, "Failed to adopt active watch party session", error)
                _watchPartyState.value = _watchPartyState.value.copy(
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                )
            }
        }
    }

    fun updateNextEpisodeCache(
        context: Context,
        nextEpisodeId: String?,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?
    ) {
        val playerPreferences = PlayerPreferences(context)
        val targetEpisodeId = nextEpisodeId?.takeIf { it.isNotBlank() }
        if (
            targetEpisodeId == null ||
            targetEpisodeId == playbackSession.mediaId ||
            !playerPreferences.isCacheNextEpisodeEnabled()
        ) {
            cancelNextEpisodePrefetch()
            return
        }
        val prefetchSignature = buildString {
            append(targetEpisodeId)
            append('|')
            append(preferredAudioStreamIndex ?: "auto")
            append('|')
            append(preferredSubtitleStreamIndex ?: "auto")
            append('|')
            append(playerPreferences.getStreamingQuality())
            append('|')
            append(playerPreferences.getAudioTranscodeMode().name)
        }

        if (
            nextEpisodePrefetchSignature == prefetchSignature &&
            nextEpisodePrefetchJob?.isActive == true
        ) {
            return
        }

        cancelNextEpisodePrefetch()
        nextEpisodePrefetchSignature = prefetchSignature
        nextEpisodePrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                prefetchNextEpisode(
                    context = context.applicationContext,
                    nextEpisodeId = targetEpisodeId,
                    preferredAudioStreamIndex = preferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
                    playerPreferences = playerPreferences
                )
            }.onFailure { error ->
                Log.d(TAG, "Skipping next-episode cache prefetch for $targetEpisodeId", error)
            }
        }
    }

    private suspend fun prefetchNextEpisode(
        context: Context,
        nextEpisodeId: String,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?,
        playerPreferences: PlayerPreferences
    ) {
        val nextDownloadRepository = downloadRepository ?: DownloadRepositoryProvider.getInstance(context)
        val offlinePath = nextDownloadRepository.getOfflineFilePath(nextEpisodeId)
        if (!offlinePath.isNullOrBlank() && File(offlinePath).exists()) {
            return
        }

        val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
        val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
        val audioTranscodeMode = if (isAudioTranscodingAllowed) {
            playerPreferences.getAudioTranscodeMode()
        } else {
            AudioTranscodeMode.AUTO
        }
        val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
            playerPreferences.getMaxStreamingBitrate()
        } else {
            null
        }
        val maxStreamingHeight = if (isVideoTranscodingAllowed) {
            playerPreferences.getStreamingQualityMaxHeight()
        } else {
            null
        }

        val playbackInfo = mediaRepository.getPlaybackInfo(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode
        ).getOrNull() ?: return

        val playbackRequest = mediaRepository.getPlaybackRequest(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            maxStreamingHeight = maxStreamingHeight,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode,
            playbackInfo = playbackInfo
        ).getOrNull() ?: return

        val streamingUrl = playbackRequest.url?.takeIf { it.isNotBlank() } ?: return
        val nextMediaItem = streamingMediaItem(streamingUrl = streamingUrl)
        val localConfiguration = nextMediaItem.localConfiguration ?: return
        val prefetchBytes = nextEpisodePrefetchBytes(
            playerPreferences = playerPreferences,
            sourceBitrate = playbackInfo.mediaSources?.firstOrNull()?.bitrate,
            maxStreamingBitrate = maxStreamingBitrate
        )

        PlayerUtils.prefetchStreamingMedia(
            context = context,
            streamUri = localConfiguration.uri,
            cacheKey = localConfiguration.customCacheKey,
            maxBytes = prefetchBytes,
            requestHeaders = playbackRequest.requestHeaders
        )
    }

    private fun nextEpisodePrefetchBytes(
        playerPreferences: PlayerPreferences,
        sourceBitrate: Int?,
        maxStreamingBitrate: Int?
    ): Long {
        val bitrateBitsPerSecond = when {
            maxStreamingBitrate != null && maxStreamingBitrate > 0 -> maxStreamingBitrate.toLong()
            sourceBitrate != null && sourceBitrate > 0 -> sourceBitrate.toLong()
            else -> 8_000_000L
        }.coerceAtLeast(2_000_000L)
        val prefetchWindowSeconds = playerPreferences.getPlayerCacheTimeSeconds()
            .coerceIn(
                PlayerPreferences.MIN_PLAYER_CACHE_TIME_SECONDS,
                PlayerPreferences.MAX_PLAYER_CACHE_TIME_SECONDS
            )
        val desiredBytes = bitrateBitsPerSecond
            .times(prefetchWindowSeconds.toLong())
            .div(8L)
        val cacheBudgetBytes = playerPreferences.getPlayerCacheSizeMb()
            .toLong()
            .times(1024L * 1024L)
            .div(3L)

        return minOf(desiredBytes, cacheBudgetBytes).coerceAtLeast(8L * 1024L * 1024L)
    }

    private fun cancelNextEpisodePrefetch() {
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = null
        nextEpisodePrefetchSignature = null
    }

    private fun getPlayMethod(
        streamingUrl: String,
        fallback: PlayMethod
    ): PlayMethod {
        val streamUri = Uri.parse(streamingUrl)
        val path = streamUri.encodedPath.orEmpty().lowercase()
        val isTranscodingUrl =
            path.contains("master.m3u8") ||
                path.contains("transcode") ||
                path.contains("transcoding")

        if (isTranscodingUrl) {
            return PlayMethod.TRANSCODE
        }

        return when (streamUri.getQueryParameter("static")?.lowercase()) {
            "true" -> PlayMethod.DIRECT_PLAY
            "false" -> PlayMethod.DIRECT_STREAM
            else -> fallback
        }
    }

    private suspend fun isVideoTranscodingAllowedForUser(): Boolean {
        videoTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isVideoTranscodingAllowed?.let {
            videoTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableVideoPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        videoTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isVideoTranscodingAllowed = allowed)
        return allowed
    }

    private suspend fun isAudioTranscodingAllowedForUser(): Boolean {
        audioTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isAudioTranscodingAllowed?.let {
            audioTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableAudioPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        audioTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isAudioTranscodingAllowed = allowed)
        return allowed
    }

    fun togglePlayPause() {
        if (exoPlayer != null || mpvPlayer != null) {
            if (isWatchPartyPlaybackActive()) pause() else play()
            playbackReporter.onPlaybackPauseStateChanged()
        }
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
        mpvPlayer?.setVolume(volume)
        _playerState.value = _playerState.value.copy(volume = volume)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeedBeforeBoost = null
        setPlaybackSpeedInternal(speed)
    }

    fun setTemporaryPlaybackSpeedBoost(active: Boolean) {
        if (active) {
            if (playbackSpeedBeforeBoost == null) {
                playbackSpeedBeforeBoost = exoPlayer?.playbackParameters?.speed
                    ?: mpvPlayer?.currentPlaybackSpeed
                    ?: 1f
            }
            val boostSpeed = playerContext
                ?.let { PlayerPreferences(it).getLongPressSpeedBoostRate().toFloat() }
                ?: PlayerPreferences.DEFAULT_LONG_PRESS_SPEED_BOOST_RATE.toFloat()
            setPlaybackSpeedInternal(boostSpeed)
        } else {
            setPlaybackSpeedInternal(playbackSpeedBeforeBoost ?: 1f)
            playbackSpeedBeforeBoost = null
        }
    }

    private fun setPlaybackSpeedInternal(speed: Float) {
        val normalizedSpeed = speed.coerceIn(0.25f, 5f)
        exoPlayer?.setPlaybackSpeed(normalizedSpeed)
        mpvPlayer?.setPlaybackSpeed(normalizedSpeed)
        _playerState.value = _playerState.value.copy(playbackSpeed = normalizedSpeed)
    }

    fun setBrightness(brightness: Float) {
        _playerState.value = _playerState.value.copy(brightness = brightness)
    }

    fun toggleControls() {
        _playerState.value = _playerState.value.copy(showControls = !_playerState.value.showControls)
    }

    fun exitPlaybackPage(onExited: () -> Unit = {}) {
        val state = _watchPartyState.value
        val roomId = state.roomId
        val memberId = state.memberId
        val mediaId = playbackSession.mediaId?.takeIf { it.isNotBlank() }
        val positionMs = getCurrentPosition()
        val shouldPublishExit = state.isHost && !roomId.isNullOrBlank() && !memberId.isNullOrBlank()

        viewModelScope.launch {
            if (shouldPublishExit) {
                runCatching {
                    publishWatchPartyPlayback(
                        roomId = roomId!!,
                        memberId = memberId!!,
                        mediaId = mediaId,
                        event = PlaybackEvent.EXIT,
                        positionMs = positionMs,
                        isPlaying = false
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Failed to send watch party exit", error)
                    _watchPartyState.value = _watchPartyState.value.copy(
                        errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                    )
                }
            }
            releasePlayer()
            onExited()
        }
    }

    fun releasePlayer() {
        persistPosition()
        playbackReporter.reportPlaybackStopped()
        cancelNextEpisodePrefetch()
        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = null
        spatialAudioAnalysisJob?.cancel()
        spatialAudioAnalysisJob = null
        exoPlayer?.apply {
            removeListener(playerListener)
            release()
        }
        exoPlayer = null
        mpvPlayer?.release()
        mpvPlayer = null
        spatializerHelper?.cleanup()
        spatializerHelper = null
        playbackSession = PlaybackSessionContext()
        playbackReporter.reset()
        trackSelectionCoordinator.clear()
        apiMediaStreams = null
        defaultAudioStreamIndex = null
        defaultSubtitleStreamIndex = null
        mpvExternalSubtitleUrls = emptyMap()
        playerContext = null
        downloadRepository = null
        hasHandledPlaybackCompletion = false
        hasRenderedFirstFrame = false
        audioDiagnosticsSignature = null
        _preferredStreamIndexes.value = PreferredStreamIndexes()
        _playerState.value = PlayerState()
    }

    private fun handlePlaybackCompleted() {
        if (hasHandledPlaybackCompletion) return
        hasHandledPlaybackCompletion = true
        persistPosition(markCompleted = true)
        playbackReporter.reportPlaybackStopped()
        playbackSession.mediaId?.let { completedMediaId ->
            _playbackCompletedEvents.tryEmit(completedMediaId)
        }
    }

    private fun persistPosition(markCompleted: Boolean = false) {
        val session = playbackSession
        if (!session.isOfflinePlayback) return
        val mediaId = session.mediaId ?: return
        downloadRepository?.updatePlaybackPosition(
            itemId = mediaId,
            positionMs = getCurrentPosition(),
            markCompleted = markCompleted
        )
    }

    fun clearError() {
        _playerState.value = _playerState.value.copy(error = null)
    }

    /**
     * Toggle lock state - when locked, disable all gestures and hide controls
     */
    fun toggleLock() {
        val currentState = _playerState.value
        _playerState.value = currentState.copy(
            isLocked = !currentState.isLocked,
            showControls = if (!currentState.isLocked) false else currentState.showControls
        )
    }

    /**
     * Update track information from ExoPlayer
     */
    private fun updateTrackInformation() {
        exoPlayer?.let { player ->
            try {
                val isHdrPlayback = PlayerMetadata.isCurrentPlaybackHdr(exoPlayer)
                val selectedAudioSignature = buildSelectedAudioSignature(player)
                if (selectedAudioSignature != null && selectedAudioSignature != audioDiagnosticsSignature) {
                    PlayerUtils.logAudioPlaybackDiagnostics(player, reason = "track_changed")
                    audioDiagnosticsSignature = selectedAudioSignature
                }
                val resolvedTracks = PlayerTrack.currentTrackState(
                    exoPlayer = player,
                    mediaStreams = apiMediaStreams,
                    isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE,
                    selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                    selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
                    defaultAudioStreamIndex = defaultAudioStreamIndex,
                    defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
                )
                val syncedPreferredIndexes = trackSelectionCoordinator.syncPreferredIndexesFromCurrentTracks(
                    context = playerContext,
                    mediaId = playbackSession.mediaId,
                    currentAudioTrack = resolvedTracks.currentAudioTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentPublished = _preferredStreamIndexes.value
                )
                if (syncedPreferredIndexes != _preferredStreamIndexes.value) {
                    _preferredStreamIndexes.value = syncedPreferredIndexes
                }

                _playerState.value = _playerState.value.copy(
                    availableAudioTracks = resolvedTracks.availableAudioTracks,
                    currentAudioTrack = resolvedTracks.currentAudioTrack,
                    availableSubtitleTracks = resolvedTracks.availableSubtitleTracks,
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack,
                    availableVideoTracks = resolvedTracks.availableVideoTracks,
                    isHdrEnabled = isHdrPlayback
                )
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to update track information", e)
            }
        }
    }

    @UnstableApi
    private fun buildSelectedAudioSignature(player: ExoPlayer): String? {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            val trackIndex = (0 until group.mediaTrackGroup.length)
                .firstOrNull(group::isTrackSelected)
                ?: return@forEachIndexed
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            return "$groupIndex:$trackIndex|${format.channelCount}|${format.bitrate}|${format.sampleRate}|${format.codecs ?: format.sampleMimeType.orEmpty()}"
        }
        return null
    }

    private fun applyPendingTrackSelectionsIfNeeded() {
        val player = exoPlayer ?: return
        val appliedAnySelection = trackSelectionCoordinator.applyInitialSelections(
            player = player,
            mediaStreams = apiMediaStreams,
            isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE
        )
        if (appliedAnySelection) {
            viewModelScope.launch {
                delay(250)
                updateTrackInformation()
            }
        }
    }

    private fun updateApiTrackInformation() {
        val trackState = MPVPlayer.trackState(
            mediaStreams = apiMediaStreams,
            selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
            selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
        )

        _playerState.value = _playerState.value.copy(
            availableAudioTracks = trackState.availableAudioTracks,
            currentAudioTrack = trackState.currentAudioTrack,
            availableSubtitleTracks = trackState.availableSubtitleTracks,
            currentSubtitleTrack = trackState.currentSubtitleTrack,
            availableVideoTracks = trackState.availableVideoTracks,
            isHdrEnabled = MPVPlayer.isHdr(apiMediaStreams)
        )
    }

    private fun createMpvPlayer(context: Context): MpvPlayerController {
        val preferences = PlayerPreferences(context)
        return MpvPlayerController(
            context = context,
            hardwareDecoding = preferences.getMpvHardwareDecoding(),
            videoOutput = preferences.getMpvVideoOutput(),
            audioOutput = preferences.getMpvAudioOutput(),
            listener = object : MpvPlayerController.Listener {
                override fun onBuffering() {
                    _playerState.value = _playerState.value.copy(isLoading = true)
                }

                override fun onReady() {
                    val wasPlaying = _playerState.value.isPlaying
                    _playerState.value = _playerState.value.copy(
                        isLoading = false,
                        isPlaying = isPlayingNow(),
                        playWhenReady = isPlayingNow(),
                        hasStartedPlayback = true,
                        duration = getDuration()
                    )
                    if (!playbackReporter.hasReportedStart() && isPlayingNow()) {
                        playbackReporter.reportPlaybackStatus()
                    }
                    markWatchPartyReadyIfPossible()
                    if (wasPlaying != isPlayingNow()) {
                        playbackReporter.onPlaybackPauseStateChanged()
                        sendWatchPartyPlayback(if (isPlayingNow()) PlaybackEvent.PLAY else PlaybackEvent.PAUSE)
                    }
                }

                override fun onEnded() {
                    _playerState.value = _playerState.value.copy(
                        isPlaying = false,
                        playWhenReady = false,
                        isLoading = false
                    )
                    handlePlaybackCompleted()
                }

            }
        )
    }

    /**
     * Select audio track by ID
     */
    fun selectAudioTrack(trackId: String) {
        if (trackId == _playerState.value.currentAudioTrack?.id) return
        val selectedTrack = _playerState.value.availableAudioTracks.firstOrNull { it.id == trackId } ?: return
        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectAudioTrack(mpvPlayer, selectedTrack) ?: return
            val (preferences, mediaId) = currentMediaPreferences() ?: return
            preferences.setPreferredAudioStreamIndex(mediaId, streamIndex)
            _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(audioStreamIndex = streamIndex)
            _playerState.value = _playerState.value.copy(currentAudioTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            playbackTrackSelection(
                audioStreamIndex = selectedTrack.streamIndex,
                subtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectAudioTrack(player, playerTrackId)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    /**
     * Select subtitle track by ID
     */
    fun selectSubtitleTrack(trackId: String) {
        if (trackId == _playerState.value.currentSubtitleTrack?.id) return
        val selectedTrack = _playerState.value.availableSubtitleTracks.firstOrNull { it.id == trackId } ?: return
        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectSubtitleTrack(
                controller = mpvPlayer,
                track = selectedTrack,
                externalSubtitleUrls = mpvExternalSubtitleUrls
            ) ?: return
            val (preferences, mediaId) = currentMediaPreferences() ?: return
            preferences.setPreferredSubtitleStreamIndex(
                mediaId,
                streamIndex.takeUnless { it < 0 }
            )
            _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(
                subtitleStreamIndex = streamIndex.takeUnless { it < 0 }
            )
            _playerState.value = _playerState.value.copy(currentSubtitleTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            playbackTrackSelection(
                audioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                subtitleStreamIndex = selectedTrack.streamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectSubtitleTrack(player, playerTrackId)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    private fun currentMediaPreferences(): Pair<PlayerPreferences, String>? {
        val context = playerContext ?: return null
        val mediaId = playbackSession.mediaId ?: return null
        return PlayerPreferences(context) to mediaId
    }

    private fun playbackTrackSelection(
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ) {
        val context = playerContext ?: return
        val mediaId = playbackSession.mediaId ?: return
        val resumePositionMs = getCurrentPosition()
        val shouldResumePlaying = isPlayingNow()

        PlayerPreferences(context).apply {
            setPreferredAudioStreamIndex(mediaId, audioStreamIndex)
            setPreferredSubtitleStreamIndex(mediaId, subtitleStreamIndex)
        }

        releasePlayer()
        initializePlayer(
            context = context,
            mediaId = mediaId,
            initialItemDetails = currentItemDetails,
            preferredAudioStreamIndex = audioStreamIndex,
            preferredSubtitleStreamIndex = subtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying
        )
    }

    private var currentAspectRatio by mutableIntStateOf(0)
    private val aspectRatioModes = listOf("Fit", "Zoom")

    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    /**
     * Toggle between fit and zoom modes
     * Uses ExoPlayer's native AspectRatioFrameLayout resize modes for proper aspect ratio handling
     */
    fun cycleAspectRatio() {
        setAspectRatioMode((currentAspectRatio + 1) % aspectRatioModes.size)
    }

    /**
     * Get current resize mode for VideoSurface
     */
    fun getCurrentResizeMode(): Int = currentResizeMode

    /**
     * Handle pinch-to-zoom gesture to set appropriate resize mode
     */
    fun handlePinchZoom(isZooming: Boolean) {
        if (isZooming && currentAspectRatio == 0) {
            setAspectRatioMode(1)
        } else if (!isZooming && currentAspectRatio == 1) {
            setAspectRatioMode(0)
        }
    }

    /**
     * Apply start maximized setting based on user preference
     * Uses ExoPlayer's native resize modes for proper aspect ratio handling
     */
    private fun applyStartMaximizedSetting(context: Context) {
        val playerPreferences = PlayerPreferences(context)
        val startMaximized = playerPreferences.isStartMaximizedEnabled()

        setAspectRatioMode(if (startMaximized) 1 else 0)
    }

    private fun setAspectRatioMode(modeIndex: Int) {
        currentAspectRatio = modeIndex.coerceIn(0, aspectRatioModes.lastIndex)
        currentResizeMode = when (currentAspectRatio) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        _playerState.value = _playerState.value.copy(
            aspectRatioMode = aspectRatioModes[currentAspectRatio],
            videoScale = 1f,
            videoOffsetX = 0f,
            videoOffsetY = 0f
        )
    }

    /**
     * Seek backward by the configured interval
     */
    fun seekBackward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekBackwardIntervalSeconds()
        seekBy(deltaMs = -(seconds * 1000L))
    }

    /**
     * Seek forward by the configured interval
     */
    fun seekForward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekForwardIntervalSeconds()
        seekBy(deltaMs = seconds * 1000L)
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            applyPendingTrackSelectionsIfNeeded()
            updateTrackInformation()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentState = _playerState.value
            val wasPlaying = currentState.isPlaying
            val playWhenReady = exoPlayer?.playWhenReady == true
            val isNowPlaying = playbackState == Player.STATE_READY && playWhenReady
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }

            _playerState.value = currentState.copy(
                isLoading = shouldShowLoading,
                isPlaying = isNowPlaying,
                playWhenReady = playWhenReady,
                hasStartedPlayback = currentState.hasStartedPlayback || hasRenderedFirstFrame
            )

            if (playbackState == Player.STATE_READY && isNowPlaying && !hasReportedStart) {
                playbackReporter.reportPlaybackStatus()
            }

            if (wasPlaying != isNowPlaying) {
                playbackReporter.onPlaybackPauseStateChanged()
                if (playbackState == Player.STATE_READY) {
                    sendWatchPartyPlayback(if (isNowPlaying) PlaybackEvent.PLAY else PlaybackEvent.PAUSE)
                }
            }

            if (playbackState == Player.STATE_READY) {
                hasHandledPlaybackCompletion = false
                applyPendingTrackSelectionsIfNeeded()
                updateTrackInformation()
                markWatchPartyReadyIfPossible()
            }

            if (playbackState == Player.STATE_ENDED) {
                handlePlaybackCompleted()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val currentState = _playerState.value
            val playbackState = exoPlayer?.playbackState ?: Player.STATE_IDLE
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }
            _playerState.value = currentState.copy(
                playWhenReady = playWhenReady,
                isPlaying = playWhenReady && playbackState == Player.STATE_READY,
                isLoading = shouldShowLoading
            )
        }

        override fun onRenderedFirstFrame() {
            hasRenderedFirstFrame = true
            _playerState.value = _playerState.value.copy(
                isLoading = false,
                hasStartedPlayback = true
            )
            markWatchPartyReadyIfPossible()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            hasRenderedFirstFrame = false
            _playerState.value = _playerState.value.copy(
                error = error.message ?: "Playback error occurred",
                isLoading = false,
                playWhenReady = false,
                isPlaying = false
            )

            if (playbackReporter.hasReportedStart()) {
                playbackReporter.reportPlaybackStopped(failed = true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _playerState.value = _playerState.value.copy(
                currentPosition = newPosition.positionMs,
                duration = getDuration()
            )

            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                playbackReporter.onPlaybackPositionDiscontinuity()
            }
        }
    }

    private fun formatEpisodeRuntime(runTimeTicks: Long?): String {
        val totalMinutes = runTimeTicks
            ?.takeIf { it > 0L }
            ?.let { ((it / 10_000L) + 30_000L) / 60_000L }
            ?: return "--min"
        return if (totalMinutes >= 60L) {
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}min"
        } else {
            "${totalMinutes}min"
        }
    }

    override fun onCleared() {
        super.onCleared()
        seasonEpisodesJob?.cancel()
        watchPartyVoiceChatClient.release()
        releasePlayer()
    }

    fun getHdrFormatInfo(): String {
        return PlayerMetadata.buildHdrFormatInfo(
            context = playerContext,
            exoPlayer = exoPlayer
        )
    }

    /**
     * Get unified media metadata information for the modern bubble dialog
     */
    fun getMediaMetadataInfo(): MediaMetadataInfo {
        return PlayerMetadata.buildMediaMetadataInfo(
            context = playerContext,
            exoPlayer = exoPlayer,
            mediaStreams = apiMediaStreams,
            mediaSourceContainer = playbackSession.mediaSourceContainer,
            mediaSourceBitrateKbps = playbackSession.mediaSourceBitrateKbps,
            playMethodDisplayName = playbackSession.playMethod.displayName
        )
    }

    fun getSourceVideoHeight(): Int? {
        return PlayerMetadata.getSourceVideoHeight(apiMediaStreams)
    }
}
