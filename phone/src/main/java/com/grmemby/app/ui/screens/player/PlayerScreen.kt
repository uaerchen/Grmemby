package com.grmemby.app.ui.screens.player

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.grmemby.app.R
import com.grmemby.app.ui.components.danmaku.DanmakuComment
import com.grmemby.app.ui.components.danmaku.DanmakuEngine
import com.grmemby.app.ui.components.danmaku.DanmakuOverlay
import com.grmemby.app.ui.components.danmaku.DanmakuSearchRequest
import com.grmemby.app.watchparty.WatchPartyKeepAliveService
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.copyableWatchPartyInviteText
import com.grmemby.app.ui.screens.player.PlayerViewModel
import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.data.model.BaseItemDto
import com.grmemby.detail.SpatializationResult
import com.grmemby.player.core.SkippableSegmentType
import com.grmemby.player.core.findActiveSkippableSegment
import com.grmemby.player.preferences.PlayerPreferences

/**
 * Player state data class to group related states
 */
data class PlayerUiState(
    val controlsVisible: Boolean = true,
    val currentPosition: Long = 0L,
    val bufferedPosition: Long = 0L,
    val isPlaying: Boolean = false,
    val volumeLevel: Float? = null,
    val brightnessLevel: Float? = null,
    val seekPosition: String? = null,
    val seekSide: SeekSide = SeekSide.CENTER,
    val videoScale: Float = 1f,
    val videoOffsetX: Float = 0f,
    val videoOffsetY: Float = 0f
)

/**
 * Player Screen with proper immersive mode and gestures
 */
@UnstableApi
@Composable
fun PlayerScreen(
    mediaId: String,
    initialItemDetails: BaseItemDto? = null,
    preferredAudioStreamIndex: Int? = null,
    preferredSubtitleStreamIndex: Int? = null,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    onPreferredStreamIndexesChanged: (Int?, Int?) -> Unit = { _, _ -> },
    onBackPressed: (() -> Unit)? = null,
    onPlaybackCompleted: ((String) -> Unit)? = null,
    previousEpisodeId: String? = null,
    onWatchPreviousEpisode: ((String) -> Unit)? = null,
    nextEpisodeId: String? = null,
    onWatchNextEpisode: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val currentView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        PlayerVisibilityStore.markVisible()
        onDispose { PlayerVisibilityStore.markHidden() }
    }

    // Consolidated UI state
    var uiState by remember { mutableStateOf(PlayerUiState()) }
    var lifecycle by remember { mutableStateOf(Lifecycle.Event.ON_CREATE) }
    var autoHideKey by remember { mutableStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var activeMediaId by remember(mediaId) { mutableStateOf(mediaId) }
    var dismissedCreditsPrompt by remember(activeMediaId) { mutableStateOf(false) }

    val hideSystemBars: () -> Unit = {
        (context as? Activity)?.let { act ->
            val windowInsetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            windowInsetsController?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        Unit
    }

    // Helper function to reset auto-hide timer
    val resetAutoHideTimer = {
        autoHideKey++
        hideSystemBars()
    }

    var isPictureInPictureActive by remember { mutableStateOf(false) }

    val enterPictureInPicture: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (context as? Activity)?.let { activity ->
                val params = buildPictureInPictureParams(
                    activity = activity,
                    width = currentView.width,
                    height = currentView.height,
                    isPlaying = viewModel.isPlayingNow()
                )
                uiState = uiState.copy(controlsVisible = false)
                isPictureInPictureActive = true
                activity.setPictureInPictureParams(params)
                activity.enterPictureInPictureMode(params)
            }
        }
    }

    // Dialog states
    var showAudioTrackDialog by remember { mutableStateOf(false) }
    var showSubtitleTrackDialog by remember { mutableStateOf(false) }
    var showStreamingQualityDialog by remember { mutableStateOf(false) }
    var showAudioTranscodingDialog by remember { mutableStateOf(false) }
    var pendingStreamingQualitySelection by remember { mutableStateOf<String?>(null) }
    var showMediaInfo by remember { mutableStateOf(false) }
    var isOverlayInteractionInProgress by remember { mutableStateOf(false) }
    var showDolbyAudioInfo by remember { mutableStateOf(false) }
    var showEpisodePicker by remember { mutableStateOf(false) }
    var showDanmakuSettingsDialog by remember { mutableStateOf(false) }
    var showWatchPartyDialog by remember { mutableStateOf(false) }
    var showRoomChatPanel by remember { mutableStateOf(false) }
    LaunchedEffect(showRoomChatPanel) {
        if (showRoomChatPanel && uiState.controlsVisible) {
            uiState = uiState.copy(controlsVisible = false)
        }
    }
    val mediaInfoSnapshot = remember(showMediaInfo, viewModel) {
        if (showMediaInfo) viewModel.getMediaMetadataInfo() else null
    }

    // Player state from ViewModel
    val playerState by viewModel.playerState.collectAsState()
    val watchPartyState by viewModel.watchPartyState.collectAsState()
    val watchPartyVoiceChatState by viewModel.watchPartyVoiceChatState.collectAsState()
    val currentSeasonEpisodes by viewModel.currentSeasonEpisodes.collectAsState()
    val preferredStreamIndexes by viewModel.preferredStreamIndexes.collectAsState()
    val voiceChatPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleWatchPartyVoiceChat(context)
        }
    }

    LaunchedEffect(viewModel, context) {
        PlayerPictureInPictureActions.events.collect { action ->
            when (action) {
                PlayerPictureInPictureActions.ACTION_SEEK_BACKWARD -> viewModel.seekBackward()
                PlayerPictureInPictureActions.ACTION_TOGGLE_PLAY_PAUSE -> viewModel.togglePlayPause()
                PlayerPictureInPictureActions.ACTION_SEEK_FORWARD -> viewModel.seekForward()
            }
        }
    }

    LaunchedEffect(playerState.playWhenReady, isPictureInPictureActive) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (context as? Activity)
                ?.takeIf { isPictureInPictureActive || it.isInPictureInPictureMode }
                ?.let { activity ->
                    activity.setPictureInPictureParams(
                        buildPictureInPictureParams(
                            activity = activity,
                            width = currentView.width,
                            height = currentView.height,
                            isPlaying = playerState.playWhenReady
                        )
                    )
                }
        }
    }

    LaunchedEffect(mediaId) {
        activeMediaId = mediaId
    }

    LaunchedEffect(viewModel) {
        viewModel.watchPartyMediaSwitchEvents.collect { targetMediaId ->
            if (targetMediaId.isNotBlank()) {
                activeMediaId = targetMediaId
            }
        }
    }

    LaunchedEffect(viewModel, onBackPressed) {
        viewModel.watchPartyExitToRoomEvents.collect {
            viewModel.releasePlayer()
            onBackPressed?.invoke()
        }
    }
    val sourceVideoHeight = viewModel.getSourceVideoHeight()
    val availableStreamingQualityOptions = remember(
        sourceVideoHeight,
        playerState.isVideoTranscodingAllowed
    ) {
        if (playerState.isVideoTranscodingAllowed) {
            PlayerPreferences.getStreamingQualityOptions(sourceVideoHeight)
        } else {
            listOf(PlayerPreferences.STREAMING_QUALITY_ORIGINAL)
        }
    }

    // System managers
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playerPreferences = remember { PlayerPreferences(context) }
    var danmakuSettingsVersion by remember { mutableIntStateOf(0) }
    var danmakuComments by remember { mutableStateOf<List<DanmakuComment>>(emptyList()) }
    var roomChatDanmakuComments by remember { mutableStateOf<List<DanmakuComment>>(emptyList()) }
    val roomChatDanmakuSeenIds = remember { mutableStateListOf<String>() }
    var danmakuLoadMessage by remember { mutableStateOf<String?>(null) }
    var currentDanmakuName by remember { mutableStateOf<String?>(null) }
    val danmakuEnabled = remember(danmakuSettingsVersion) { playerPreferences.isDanmakuEnabled() }
    val danmakuApiUrls = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuApiUrls() }
    val danmakuFilterWords = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuFilterWords() }
    val danmakuMatchMode = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuMatchMode() }
    val danmakuLineCount = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuLineCount() }
    val danmakuSpeedPercent = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuSpeedPercent() }
    val danmakuOpacityPercent = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuOpacityPercent() }
    val danmakuFontSizeSp = remember(danmakuSettingsVersion) { playerPreferences.getDanmakuFontSizeSp() }
    val danmakuBold = remember(danmakuSettingsVersion) { playerPreferences.isDanmakuBoldEnabled() }
    val danmakuMergeDuplicates = remember(danmakuSettingsVersion) { playerPreferences.isDanmakuMergeDuplicatesEnabled() }
    val useDeviceVolumeInPlayer = remember { playerPreferences.isUseDeviceVolumeInPlayerEnabled() }
    val useDeviceBrightnessInPlayer = remember { playerPreferences.isUseDeviceBrightnessInPlayerEnabled() }

    // Store original values to restore on exit
    val originalVolume = remember { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }

    // Player-level brightness and volume (persistent)
    var playerBrightness by remember(useDeviceBrightnessInPlayer) {
        mutableStateOf(
            if (useDeviceBrightnessInPlayer) {
                readCurrentDeviceBrightness(context)
            } else {
                playerPreferences.getPlayerBrightness()
            }
        )
    }
    var playerVolume by remember(useDeviceVolumeInPlayer) {
        mutableStateOf(
            if (useDeviceVolumeInPlayer) {
                readCurrentDeviceVolume(audioManager)
            } else {
                playerPreferences.getPlayerVolume()
            }
        )
    }
    var currentStreamingQuality by remember { mutableStateOf(playerPreferences.getStreamingQuality()) }
    var currentDecoderModeLabel by remember { mutableStateOf(resolveDecoderModeLabel(playerPreferences)) }
    val skipIntroEnabled = remember { playerPreferences.isSkipIntroEnabled() }
    var currentAudioTranscodeMode by remember {
        mutableStateOf(playerPreferences.getAudioTranscodeMode())
    }
    val seekBackwardSeconds = playerPreferences.getSeekBackwardIntervalSeconds()
    val seekForwardSeconds = playerPreferences.getSeekForwardIntervalSeconds()
    val chapterMarkersEnabled = playerPreferences.areChapterMarkersEnabled()

    // Track initialized media so this screen can switch to a new episode in-place.
    var initializedMediaId by remember { mutableStateOf<String?>(null) }

    PlayerScreenEffects(
        context = context,
        currentView = currentView,
        lifecycleOwner = lifecycleOwner,
        mediaId = activeMediaId,
        initialItemDetails = initialItemDetails,
        preferredAudioStreamIndex = preferredAudioStreamIndex,
        preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
        startPlayback = !watchPartyState.isInRoom && !viewModel.hasActiveWatchPartySession(),
        viewModel = viewModel,
        onPlaybackCompleted = onPlaybackCompleted,
        preferredStreamIndexes = preferredStreamIndexes,
        playerState = playerState,
        useDeviceVolumeInPlayer = useDeviceVolumeInPlayer,
        useDeviceBrightnessInPlayer = useDeviceBrightnessInPlayer,
        audioManager = audioManager,
        originalVolume = originalVolume,
        playerBrightness = playerBrightness,
        playerVolume = playerVolume,
        showAudioTrackDialog = showAudioTrackDialog,
        showSubtitleTrackDialog = showSubtitleTrackDialog,
        showStreamingQualityDialog = showStreamingQualityDialog,
        showAudioTranscodingDialog = showAudioTranscodingDialog,
        showMediaInfo = showMediaInfo,
        showEpisodePicker = showEpisodePicker,
        showDanmakuSettingsDialog = showDanmakuSettingsDialog,
        isOverlayInteractionInProgress = isOverlayInteractionInProgress,
        autoHideKey = autoHideKey,
        isScrubbing = isScrubbing,
        hideSystemBars = hideSystemBars,
        uiStateProvider = { uiState },
        onUiStateChange = { uiState = it },
        initializedMediaIdProvider = { initializedMediaId },
        onInitializedMediaIdChange = { initializedMediaId = it },
        onLifecycleChange = { event ->
            lifecycle = event
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val activity = context as? Activity
                when (event) {
                    Lifecycle.Event.ON_RESUME -> isPictureInPictureActive = false
                    Lifecycle.Event.ON_PAUSE -> isPictureInPictureActive =
                        isPictureInPictureActive || activity?.isInPictureInPictureMode == true
                    else -> Unit
                }
            }
        },
        onCurrentAudioTranscodeModeChange = { currentAudioTranscodeMode = it },
        onPreferredStreamIndexesChanged = onPreferredStreamIndexesChanged
    )

    val hasPlaybackSettings = playerState.isVideoTranscodingAllowed ||
        playerState.isAudioTranscodingAllowed
    val playbackDuration = viewModel.getDuration()
    val activeSkippableSegment = remember(
        skipIntroEnabled,
        playerState.isLocked,
        playerState.recapStartMs,
        playerState.recapEndMs,
        playerState.introStartMs,
        playerState.introEndMs,
        playerState.creditsStartMs,
        playerState.creditsEndMs,
        playerState.previewStartMs,
        playerState.previewEndMs,
        playbackDuration,
        uiState.currentPosition
    ) {
        if (!skipIntroEnabled || playerState.isLocked) {
            null
        } else {
            playerState.findActiveSkippableSegment(
                positionMs = uiState.currentPosition,
                durationMs = playbackDuration
            )
        }
    }
    val activeCreditsSegment = activeSkippableSegment?.takeIf {
        it.type == SkippableSegmentType.CREDITS
    }
    val canWatchPreviousEpisode = !previousEpisodeId.isNullOrBlank() && onWatchPreviousEpisode != null
    val canWatchNextEpisode = !nextEpisodeId.isNullOrBlank() && onWatchNextEpisode != null
    val toggleDecoderModeAndRestart: () -> Unit = {
        val nextDecoderLabel = cycleDecoderMode(playerPreferences)
        currentDecoderModeLabel = nextDecoderLabel
        val resumePositionMs = viewModel.getCurrentPosition()
        val shouldResumePlaying = viewModel.isPlayingNow()
        uiState = uiState.copy(controlsVisible = true)
        viewModel.releasePlayer()
        initializedMediaId = null
        viewModel.initializePlayer(
            context = context,
            mediaId = activeMediaId,
            initialItemDetails = initialItemDetails,
            preferredAudioStreamIndex = preferredStreamIndexes.audioStreamIndex,
            preferredSubtitleStreamIndex = preferredStreamIndexes.subtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying
        )
        initializedMediaId = activeMediaId
        Toast.makeText(context, "解码模式：$nextDecoderLabel", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(
        activeMediaId,
        playerState.mediaTitle,
        playerState.seriesTitle,
        playerState.seasonEpisodeLabel,
        playerState.danmakuFileName,
        playerState.danmakuHash,
        danmakuEnabled,
        danmakuApiUrls,
        danmakuFilterWords,
        danmakuMatchMode,
        danmakuMergeDuplicates
    ) {
        danmakuComments = emptyList()
        currentDanmakuName = null
        danmakuLoadMessage = null
        if (!danmakuEnabled) {
            danmakuLoadMessage = "弹幕已关闭"
            return@LaunchedEffect
        }
        if (danmakuApiUrls.isEmpty()) {
            danmakuLoadMessage = "未配置弹幕 API"
            return@LaunchedEffect
        }
        val resolvedTitle = playerState.seriesTitle.ifBlank { playerState.mediaTitle }
        val metadataReady = resolvedTitle.isNotBlank() &&
            !resolvedTitle.equals("Unknown Title", ignoreCase = true)
        if (!metadataReady && playerState.danmakuFileName.isBlank() && playerState.seasonEpisodeLabel.isNullOrBlank()) {
            danmakuLoadMessage = "等待视频信息…"
            return@LaunchedEffect
        }
        val matchName = playerState.danmakuFileName.ifBlank { playerState.mediaTitle.ifBlank { activeMediaId } }
        danmakuLoadMessage = "正在搜索弹幕…"
        val result = DanmakuEngine.load(
            apiUrls = danmakuApiUrls,
            request = DanmakuSearchRequest(
                title = resolvedTitle,
                fileName = matchName,
                mediaId = activeMediaId,
                seasonEpisodeLabel = playerState.seasonEpisodeLabel.orEmpty(),
                hash = playerState.danmakuHash?.takeIf {
                    danmakuMatchMode == PlayerPreferences.DANMAKU_MATCH_MODE_FILENAME_HASH
                },
                matchMode = danmakuMatchMode
            ),
            filterWords = danmakuFilterWords,
            mergeDuplicates = danmakuMergeDuplicates
        )
        danmakuComments = result.comments
        currentDanmakuName = result.displayTitle?.takeIf { it.isNotBlank() }
        danmakuLoadMessage = when {
            result.error != null -> result.error
            currentDanmakuName != null -> "当前弹幕：$currentDanmakuName · ${result.comments.size} 条"
            result.comments.isNotEmpty() -> "已加载 ${result.comments.size} 条弹幕"
            else -> "未找到弹幕"
        }
    }

    LaunchedEffect(watchPartyState.roomId, watchPartyState.chatMessages) {
        if (!watchPartyState.isInRoom) {
            roomChatDanmakuComments = emptyList()
            roomChatDanmakuSeenIds.clear()
            return@LaunchedEffect
        }
        val unseenMessages = watchPartyState.chatMessages.filterNot { message ->
            roomChatDanmakuSeenIds.contains(message.id)
        }
        if (unseenMessages.isEmpty()) return@LaunchedEffect
        val baseTimeMs = uiState.currentPosition.coerceAtLeast(0L)
        val newComments = unseenMessages.mapIndexed { index, message ->
            roomChatDanmakuSeenIds.add(message.id)
            DanmakuComment(
                timeMs = baseTimeMs + index * 260L,
                text = "${message.senderName.ifBlank { "成员" }}:${message.content.trim()}",
                mode = 1,
                color = 0x66CCFF
            )
        }
        roomChatDanmakuComments = (roomChatDanmakuComments + newComments).takeLast(40)
        if (roomChatDanmakuSeenIds.size > 120) {
            val keep = roomChatDanmakuSeenIds.takeLast(80)
            roomChatDanmakuSeenIds.clear()
            roomChatDanmakuSeenIds.addAll(keep)
        }
    }

    val visibleDanmakuComments = remember(danmakuComments, roomChatDanmakuComments, danmakuEnabled, watchPartyState.isInRoom) {
        buildList {
            if (danmakuEnabled) addAll(danmakuComments)
            if (watchPartyState.isInRoom) addAll(roomChatDanmakuComments)
        }.sortedBy { it.timeMs }
    }

    LaunchedEffect(activeCreditsSegment?.startMs, activeCreditsSegment?.endMs) {
        if (activeCreditsSegment == null) {
            dismissedCreditsPrompt = false
        }
    }

    LaunchedEffect(
        initializedMediaId,
        nextEpisodeId,
        activeCreditsSegment != null,
        canWatchNextEpisode,
        dismissedCreditsPrompt,
        preferredStreamIndexes.audioStreamIndex,
        preferredStreamIndexes.subtitleStreamIndex
    ) {
        viewModel.updateNextEpisodeCache(
            context = context,
            nextEpisodeId = nextEpisodeId.takeIf {
                initializedMediaId == activeMediaId &&
                    activeCreditsSegment != null &&
                    canWatchNextEpisode &&
                    !dismissedCreditsPrompt
            },
            preferredAudioStreamIndex = preferredStreamIndexes.audioStreamIndex,
            preferredSubtitleStreamIndex = preferredStreamIndexes.subtitleStreamIndex
        )
    }

    val applyPlaybackSettingsSelection: (String, AudioTranscodeMode) -> Unit = applyPlaybackSettingsSelection@{ quality, audioMode ->
        val selectedQuality = quality.trim()
        val qualityChanged = selectedQuality.isNotEmpty() && selectedQuality != currentStreamingQuality
        val audioModeChanged = audioMode != currentAudioTranscodeMode

        pendingStreamingQualitySelection = null
        showStreamingQualityDialog = false
        showAudioTranscodingDialog = false

        if (selectedQuality.isEmpty()) return@applyPlaybackSettingsSelection

        playerPreferences.setStreamingQuality(selectedQuality)
        currentStreamingQuality = playerPreferences.getStreamingQuality()
        playerPreferences.setAudioTranscodeMode(audioMode)
        currentAudioTranscodeMode = playerPreferences.getAudioTranscodeMode()

        if (!qualityChanged && !audioModeChanged) {
            return@applyPlaybackSettingsSelection
        }

        val resumePositionMs = viewModel.getCurrentPosition()
        val shouldResumePlaying = viewModel.isPlayingNow()
        val preferredAudio = preferredStreamIndexes.audioStreamIndex
        val preferredSubtitle = preferredStreamIndexes.subtitleStreamIndex

        uiState = uiState.copy(controlsVisible = true)
        viewModel.releasePlayer()
        initializedMediaId = null
        viewModel.initializePlayer(
            context = context,
            mediaId = activeMediaId,
            initialItemDetails = initialItemDetails,
            preferredAudioStreamIndex = preferredAudio,
            preferredSubtitleStreamIndex = preferredSubtitle,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying
        )
        initializedMediaId = activeMediaId
    }

    val applyStreamingQualitySelection: (String) -> Unit = { selectedQuality ->
        if (!playerState.isVideoTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
            showStreamingQualityDialog = false
        } else {
            val selection = selectedQuality.trim()
            if (selection.isEmpty()) {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = false
                showStreamingQualityDialog = false
            } else {
                val needsAudioPrompt = playerState.isAudioTranscodingAllowed

                if (needsAudioPrompt) {
                    pendingStreamingQualitySelection = selection
                    showStreamingQualityDialog = false
                    showAudioTranscodingDialog = true
                } else {
                    applyPlaybackSettingsSelection(selection, currentAudioTranscodeMode)
                }
            }
        }
    }

    LaunchedEffect(
        playerState.isVideoTranscodingAllowed,
        playerState.isAudioTranscodingAllowed
    ) {
        if (!playerState.isVideoTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
            showStreamingQualityDialog = false
        }
        if (!playerState.isAudioTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
        }
    }

    // Back handler
    BackHandler {
        viewModel.exitPlaybackPage {
            onBackPressed?.invoke()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
    ) {
        VideoSurface(
            player = viewModel.exoPlayer,
            mpvPlayer = viewModel.mpvPlayer,
            lifecycle = lifecycle,
            isInPictureInPictureMode = isPictureInPictureActive,
            scale = playerState.videoScale,
            offsetX = playerState.videoOffsetX,
            offsetY = playerState.videoOffsetY,
            resizeMode = viewModel.getCurrentResizeMode(),
            onVolumeChange = { level ->
                if (!playerState.isLocked) {
                    playerVolume = level.coerceIn(0f, 1f)
                    if (!useDeviceVolumeInPlayer) {
                        playerPreferences.setPlayerVolume(playerVolume)
                    }

                    // Apply volume to system
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val newVolume = (playerVolume * maxVolume).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                    uiState = uiState.copy(volumeLevel = playerVolume)
                }
            },
            onBrightnessChange = { delta ->
                if (!playerState.isLocked) {
                    val activity = context as? Activity
                    activity?.let { act ->
                        val newPlayerBrightness = (playerBrightness + delta).coerceIn(0.0f, 1f)
                        playerBrightness = newPlayerBrightness
                        if (!useDeviceBrightnessInPlayer) {
                            playerPreferences.setPlayerBrightness(newPlayerBrightness)
                        }

                        val layoutParams = act.window.attributes
                        layoutParams.screenBrightness = newPlayerBrightness
                        act.window.attributes = layoutParams

                        uiState = uiState.copy(brightnessLevel = newPlayerBrightness)
                    }
                }
            },
            getCurrentVolumeLevel = { playerVolume },
            getCurrentBrightnessLevel = { playerBrightness },
            onSeek = { delta ->
                if (!playerState.isLocked) {
                    viewModel.seekBy(delta)

                    // Show seek indicator
                    val isForward = delta > 0
                    val seconds = kotlin.math.abs(delta) / 1000
                    val seekText = if (isForward) "+${seconds}s" else "-${seconds}s"
                    val side = if (isForward) SeekSide.RIGHT else SeekSide.LEFT

                    uiState = uiState.copy(seekPosition = seekText, seekSide = side)
                }
            },
            onToggleControls = {
                resetAutoHideTimer()
                uiState = uiState.copy(controlsVisible = !uiState.controlsVisible)
            },
            onTogglePlayPause = viewModel::togglePlayPause,
            onZoomChange = { isZooming ->
                viewModel.handlePinchZoom(isZooming)
            },
            onPlaybackSpeedBoostChange = { active ->
                if (!playerState.isLocked) {
                    viewModel.setTemporaryPlaybackSpeedBoost(active)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        DanmakuOverlay(
            comments = visibleDanmakuComments,
            currentPositionMs = uiState.currentPosition,
            isPlaying = uiState.isPlaying,
            enabled = visibleDanmakuComments.isNotEmpty(),
            lineCount = danmakuLineCount,
            speedPercent = danmakuSpeedPercent,
            opacityPercent = danmakuOpacityPercent,
            fontSizeSp = danmakuFontSizeSp,
            bold = danmakuBold,
            modifier = Modifier.fillMaxSize()
        )

        PlayerOverlayHost(
            uiState = uiState,
            playerState = playerState,
            watchPartyState = watchPartyState,
            watchPartyVoiceChatState = watchPartyVoiceChatState,
            currentStreamingQuality = currentStreamingQuality,
            decoderModeLabel = currentDecoderModeLabel,
            onToggleDecoderMode = toggleDecoderModeAndRestart,
            hasPlaybackSettings = hasPlaybackSettings,
            chapterMarkersEnabled = chapterMarkersEnabled,
            seekBackwardSeconds = seekBackwardSeconds,
            seekForwardSeconds = seekForwardSeconds,
            activeSkippableSegment = activeSkippableSegment,
            activeCreditsSegment = activeCreditsSegment,
            dismissedCreditsPrompt = dismissedCreditsPrompt,
            canWatchPreviousEpisode = canWatchPreviousEpisode,
            canWatchNextEpisode = canWatchNextEpisode,
            viewModel = viewModel,
            onBackPressed = onBackPressed,
            resetAutoHideTimer = resetAutoHideTimer,
            onScrubbingChange = { isScrubbing = it },
            onWatchCredits = {
                dismissedCreditsPrompt = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onWatchPreviousEpisode = {
                viewModel.prepareWatchPartyNextEpisodeHandoff()
                previousEpisodeId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onWatchPreviousEpisode?.invoke(it) }
            },
            onWatchNextEpisode = {
                viewModel.prepareWatchPartyNextEpisodeHandoff()
                nextEpisodeId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onWatchNextEpisode?.invoke(it) }
            },
            onShowMediaInfo = { showMediaInfo = true },
            onShowDolbyAudioInfo = { showDolbyAudioInfo = true },
            onSelectPlaybackSpeed = viewModel::setPlaybackSpeed,
            onSpeedMenuExpandedChange = { isOverlayInteractionInProgress = it },
            onShowEpisodePicker = { showEpisodePicker = true },
            onShowDanmakuSettings = { showDanmakuSettingsDialog = true },
            onShowWatchParty = { showWatchPartyDialog = true },
            onCopyWatchPartyInvite = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "Grmemby Watch Party",
                        copyableWatchPartyInviteText(
                            roomId = watchPartyState.roomId,
                            inviteText = watchPartyState.inviteText
                        )
                    )
                )
            },
            onLeaveWatchParty = viewModel::leaveOrDisbandWatchParty,
            onToggleWatchPartyVoiceChat = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.toggleWatchPartyVoiceChat(context)
                } else {
                    voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onShowRoomChat = {
                showRoomChatPanel = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onShowStreamingQualityDialog = { showStreamingQualityDialog = true },
            onShowAudioTranscodingDialog = {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = true
            },
            onShowAudioTrackDialog = { showAudioTrackDialog = true },
            onShowSubtitleTrackDialog = { showSubtitleTrackDialog = true },
            suppressBackgroundScrim = showAudioTrackDialog ||
                showSubtitleTrackDialog ||
                showStreamingQualityDialog ||
                showAudioTranscodingDialog ||
                showMediaInfo ||
                showEpisodePicker ||
                showRoomChatPanel ||
                showDanmakuSettingsDialog,
            hideControlsForModal = showAudioTrackDialog ||
                showSubtitleTrackDialog ||
                showStreamingQualityDialog ||
                showAudioTranscodingDialog ||
                showMediaInfo ||
                showEpisodePicker ||
                showRoomChatPanel ||
                showDanmakuSettingsDialog,
            showPictureInPictureButton = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            onEnterPictureInPicture = enterPictureInPicture
        )

        WatchPartyRoomChatOverlay(
            isInRoom = watchPartyState.isInRoom,
            messages = watchPartyState.chatMessages,
            onSendMessage = viewModel::sendWatchPartyChatMessage,
            panelVisible = showRoomChatPanel,
            onPanelVisibleChange = { visible ->
                showRoomChatPanel = visible
                if (visible) {
                    uiState = uiState.copy(controlsVisible = false)
                }
            }
        )

        PlayerDialogsHost(
            playerState = playerState,
            showAudioTrackDialog = showAudioTrackDialog,
            showSubtitleTrackDialog = showSubtitleTrackDialog,
            showStreamingQualityDialog = showStreamingQualityDialog,
            showAudioTranscodingDialog = showAudioTranscodingDialog,
            showMediaInfo = showMediaInfo,
            availableStreamingQualityOptions = availableStreamingQualityOptions,
            currentStreamingQuality = currentStreamingQuality,
            currentAudioTranscodeMode = currentAudioTranscodeMode,
            mediaInfoSnapshot = mediaInfoSnapshot,
            onAudioTrackSelected = { trackId ->
                viewModel.selectAudioTrack(trackId)
                showAudioTrackDialog = false
                isOverlayInteractionInProgress = false
            },
            onSubtitleTrackSelected = { trackId ->
                viewModel.selectSubtitleTrack(trackId)
                showSubtitleTrackDialog = false
                isOverlayInteractionInProgress = false
            },
            onStreamingQualitySelected = applyStreamingQualitySelection,
            onAudioTranscodingSelected = { selectedMode ->
                val targetQuality = pendingStreamingQualitySelection ?: currentStreamingQuality
                applyPlaybackSettingsSelection(targetQuality, selectedMode)
            },
            onDismissAudioTrackDialog = {
                showAudioTrackDialog = false
                isOverlayInteractionInProgress = false
            },
            onDismissSubtitleTrackDialog = {
                showSubtitleTrackDialog = false
                isOverlayInteractionInProgress = false
            },
            onDismissStreamingQualityDialog = {
                showStreamingQualityDialog = false
                isOverlayInteractionInProgress = false
            },
            onDismissAudioTranscodingDialog = {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = false
                isOverlayInteractionInProgress = false
            },
            onDismissMediaInfo = {
                showMediaInfo = false
                isOverlayInteractionInProgress = false
            }
        )



        if (showDolbyAudioInfo) {
            DolbyAudioInfoDialog(
                spatializationResult = playerState.spatializationResult,
                isSpatialAudioEnabled = playerState.isSpatialAudioEnabled,
                spatialAudioFormat = playerState.spatialAudioFormat,
                onDismiss = { showDolbyAudioInfo = false }
            )
        }

        if (showDanmakuSettingsDialog) {
            DanmakuSettingsDialog(
                playerPreferences = playerPreferences,
                currentSearchRequest = DanmakuSearchRequest(
                    title = playerState.seriesTitle.ifBlank { playerState.mediaTitle },
                    fileName = playerState.danmakuFileName.ifBlank { playerState.mediaTitle.ifBlank { activeMediaId } },
                    mediaId = activeMediaId,
                    seasonEpisodeLabel = playerState.seasonEpisodeLabel.orEmpty(),
                    hash = playerState.danmakuHash?.takeIf {
                        danmakuMatchMode == PlayerPreferences.DANMAKU_MATCH_MODE_FILENAME_HASH
                    },
                    matchMode = danmakuMatchMode
                ),
                onSettingsChanged = { danmakuSettingsVersion++ },
                currentDanmakuStatus = buildDanmakuStatusText(
                    currentDanmakuName = currentDanmakuName,
                    loadMessage = danmakuLoadMessage,
                    commentCount = danmakuComments.size
                ),
                onDanmakuSearchLoaded = { comments, message ->
                    danmakuComments = comments
                    currentDanmakuName = message
                        .takeIf { it.startsWith("当前弹幕：") }
                        ?.substringAfter("当前弹幕：")
                        ?.substringBefore(" ·")
                        ?.takeIf { it.isNotBlank() }
                    danmakuLoadMessage = message
                },
                onDismiss = {
                    showDanmakuSettingsDialog = false
                    isOverlayInteractionInProgress = false
                }
            )
        }

        EpisodeSelectionDrawer(
            isVisible = showEpisodePicker,
            episodes = currentSeasonEpisodes,
            currentMediaId = activeMediaId,
            onEpisodeSelected = { episode ->
                showEpisodePicker = false
                isOverlayInteractionInProgress = false
                if (episode.id != activeMediaId) {
                    viewModel.prepareWatchPartyNextEpisodeHandoff()
                    activeMediaId = episode.id
                }
            },
            onDismiss = {
                showEpisodePicker = false
                isOverlayInteractionInProgress = false
            }
        )

        if (showWatchPartyDialog) {
            WatchPartyDialog(
                state = watchPartyState,
                onCreateRoom = {
                    viewModel.createWatchPartyRoom(
                        context = context,
                        mediaId = activeMediaId,
                        title = playerState.mediaTitle.ifBlank { initialItemDetails?.name ?: activeMediaId }
                    )
                    showWatchPartyDialog = false
                },
                onJoinRoom = { roomId ->
                    WatchPartyKeepAliveService.restoreSession(context.applicationContext)
                        ?.takeIf { it.roomId == roomId.trim() }
                        ?.let { WatchPartySessionStore.set(it) }
                    viewModel.joinWatchPartyRoom(context, roomId)
                    showWatchPartyDialog = false
                },
                onDismiss = { showWatchPartyDialog = false }
            )
        }
    }
}

private fun buildDanmakuStatusText(
    currentDanmakuName: String?,
    loadMessage: String?,
    commentCount: Int
): String {
    return when {
        !currentDanmakuName.isNullOrBlank() -> "当前弹幕：$currentDanmakuName · $commentCount 条"
        loadMessage.isNullOrBlank() -> "未找到弹幕"
        loadMessage.contains("未", ignoreCase = false) || loadMessage.contains("关闭") || loadMessage.contains("等待") || loadMessage.contains("正在") -> loadMessage
        commentCount > 0 -> "已加载 $commentCount 条弹幕"
        else -> "未找到弹幕"
    }
}


@Composable
private fun DolbyAudioInfoDialog(
    spatializationResult: SpatializationResult?,
    isSpatialAudioEnabled: Boolean,
    spatialAudioFormat: String,
    onDismiss: () -> Unit
) {
    val format = spatialAudioFormat.takeIf { it.isNotBlank() }
        ?: spatializationResult?.spatialFormat?.takeIf { it.isNotBlank() }
        ?: "未知"
    val status = when {
        spatializationResult == null -> "正在检测杜比/空间音频信息"
        isSpatialAudioEnabled || spatializationResult.canSpatialize -> "当前输出路线支持：$format"
        else -> "当前未启用：$format"
    }
    val reason = spatializationResult?.reason ?: "播放器会根据音轨、设备和系统空间音频能力自动判断。"

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("杜比音效") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status)
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun EpisodePickerDialog(
    canWatchPreviousEpisode: Boolean,
    canWatchNextEpisode: Boolean,
    onWatchPreviousEpisode: () -> Unit,
    onWatchNextEpisode: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("选择剧集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("可从当前播放位置切换到相邻剧集。")
                if (!canWatchPreviousEpisode && !canWatchNextEpisode) {
                    Text("当前没有可切换的上一集或下一集。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onWatchPreviousEpisode,
                    enabled = canWatchPreviousEpisode
                ) { Text("上一集") }
                Button(
                    onClick = onWatchNextEpisode,
                    enabled = canWatchNextEpisode
                ) { Text("下一集") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun WatchPartyDialog(
    state: WatchPartyUiState,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var roomId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("一起看") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isInRoom) {
                    Text("当前房间：${state.roomId}")
                    Text("成员：${state.memberCount}")
                    state.mediaTitle?.takeIf { it.isNotBlank() }?.let { Text("影片：$it") }
                } else {
                    Text("创建房间后会自动选择当前影片；或输入 4 位房间号加入同一影片的房间。")
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { input -> roomId = input.filter { it.isDigit() }.take(4) },
                        label = { Text("4 位房间号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (state.isInRoom) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onJoinRoom(roomId) },
                        enabled = roomId.length == 4 && !state.isLoading
                    ) { Text("加入") }
                    Button(
                        onClick = onCreateRoom,
                        enabled = !state.isLoading
                    ) { Text("创建") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun readCurrentDeviceVolume(audioManager: AudioManager): Float {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return 0f
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
}

private fun readCurrentDeviceBrightness(context: Context): Float {
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            .toFloat()
            .div(255f)
            .coerceIn(0.0f, 1f)
    }.getOrDefault(PlayerPreferences(context).getPlayerBrightness())
}

@Composable
fun SpatialAudioInfoDialog(
    spatialInfo: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "空间音频状态",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF0F172A)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color(0xFF475569)
                    )
                }
            }
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x0F0F172A)
                )
            ) {
                Text(
                    text = spatialInfo,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF334155)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("OK", color = Color(0xFF258BFF))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun HdrFormatInfoDialog(
    hdrInfo: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HDR 格式和回退状态",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF0F172A)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color(0xFF475569)
                    )
                }
            }
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x0F0F172A)
                )
            ) {
                Text(
                    text = hdrInfo,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF334155)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("OK", color = Color(0xFF258BFF))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp)
    )
}

private fun buildPictureInPictureParams(
    activity: Activity,
    width: Int,
    height: Int,
    isPlaying: Boolean
): PictureInPictureParams {
    val safeWidth = width.takeIf { it > 0 } ?: 16
    val safeHeight = height.takeIf { it > 0 } ?: 9
    val aspectRatio = Rational(safeWidth, safeHeight)
    val playPauseTitle = if (isPlaying) "暂停" else "播放"
    val playPauseIcon = if (isPlaying) {
        android.R.drawable.ic_media_pause
    } else {
        android.R.drawable.ic_media_play
    }

    return PictureInPictureParams.Builder()
        .setAspectRatio(aspectRatio)
        .setActions(
            listOf(
                buildPictureInPictureAction(
                    activity = activity,
                    action = PlayerPictureInPictureActions.ACTION_SEEK_BACKWARD,
                    requestCode = 2600,
                    iconRes = android.R.drawable.ic_media_rew,
                    title = "快退"
                ),
                buildPictureInPictureAction(
                    activity = activity,
                    action = PlayerPictureInPictureActions.ACTION_TOGGLE_PLAY_PAUSE,
                    requestCode = 2601,
                    iconRes = playPauseIcon,
                    title = playPauseTitle
                ),
                buildPictureInPictureAction(
                    activity = activity,
                    action = PlayerPictureInPictureActions.ACTION_SEEK_FORWARD,
                    requestCode = 2602,
                    iconRes = android.R.drawable.ic_media_ff,
                    title = "快进"
                )
            )
        )
        .build()
}

private fun buildPictureInPictureAction(
    activity: Activity,
    action: String,
    requestCode: Int,
    iconRes: Int,
    title: String
): RemoteAction {
    val actionIntent = Intent(activity, PlayerPictureInPictureActionReceiver::class.java)
        .setAction(action)
        .setPackage(activity.packageName)
    val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val pendingIntent = PendingIntent.getBroadcast(
        activity,
        requestCode,
        actionIntent,
        pendingIntentFlags
    )

    return RemoteAction(
        Icon.createWithResource(activity, iconRes),
        title,
        title,
        pendingIntent
    )
}

private fun resolveDecoderModeLabel(playerPreferences: PlayerPreferences): String {
    val decoderPriority = playerPreferences.getDecoderPriority()
    val mpvHardwareDecoding = playerPreferences.getMpvHardwareDecoding()
    return when {
        decoderPriority == PlayerPreferences.DECODER_PRIORITY_SOFTWARE ||
            !playerPreferences.isHardwareAccelerationEnabled() ||
            mpvHardwareDecoding == PlayerPreferences.MPV_HARDWARE_DECODING_NONE -> "SW"
        decoderPriority == PlayerPreferences.DECODER_PRIORITY_HARDWARE ||
            mpvHardwareDecoding != PlayerPreferences.MPV_HARDWARE_DECODING_NONE -> "HW"
        else -> "AUTO"
    }
}

private fun cycleDecoderMode(playerPreferences: PlayerPreferences): String {
    val nextHardwareEnabled = resolveDecoderModeLabel(playerPreferences) == "SW"
    if (nextHardwareEnabled) {
        playerPreferences.setHardwareAccelerationEnabled(true)
        playerPreferences.setDecoderPriority(PlayerPreferences.DECODER_PRIORITY_HARDWARE)
        playerPreferences.setMpvHardwareDecoding(PlayerPreferences.MPV_HARDWARE_DECODING_MEDIACODEC)
    } else {
        playerPreferences.setHardwareAccelerationEnabled(false)
        playerPreferences.setDecoderPriority(PlayerPreferences.DECODER_PRIORITY_SOFTWARE)
        playerPreferences.setMpvHardwareDecoding(PlayerPreferences.MPV_HARDWARE_DECODING_NONE)
    }
    return resolveDecoderModeLabel(playerPreferences)
}

@Preview(
    name = "Player Screen - Controls Visible",
    showBackground = true,
    widthDp = 800,
    heightDp = 450
)
@Composable
fun PlayerScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Mock video surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = "Video Content",
                color = Color.White.copy(alpha = 0.3f),
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
            )
        }

        // Show controls overlay
        ControlsOverlay(
            title = "Sample Movie Title",
            chapterMarkers = emptyList(),
            isPlaying = true,
            currentPosition = 45000L, // 45 seconds
            duration = 7200000L, // 2 hours
            onBackClick = { },
            onPlayPause = { },
            onSeek = { },
            isLocked = false,
            onToggleLock = { },
            onShowAudioTrackSelection = { },
            onShowSubtitleTrackSelection = { },
            onCycleAspectRatio = { },
            onSeekBackward = { },
            onSeekForward = { },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(
    name = "Player Screen - Gesture Indicators",
    showBackground = true,
    widthDp = 800,
    heightDp = 450
)
@Composable
fun PlayerScreenGesturePreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Mock video surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Video Surface",
                color = Color.White,
                fontSize = 24.sp
            )
        }

        // Gesture indicators preview
        GestureIndicators(
            volumeLevel = 0.7f, // 70% volume
            brightnessLevel = 0.5f, // 50% brightness
            seekPosition = "+10s"
        )

        // Loading indicator preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Preview(
    name = "Player Screen - Controls Hidden",
    showBackground = true,
    widthDp = 800,
    heightDp = 450
)
@Composable
fun PlayerScreenPreviewHidden() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = "Video Content",
                color = Color.White.copy(alpha = 0.3f),
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
            )
        }
    }
}
