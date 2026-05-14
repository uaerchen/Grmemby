@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.grmemby.app.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.grmemby.app.R
import com.grmemby.app.watchparty.shouldShowPlayerLoadingOverlay
import com.grmemby.app.watchparty.WatchPartyVoiceChatState
import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.data.model.BaseItemDto
import com.grmemby.player.core.PlayerConstants.CONTROLS_AUTO_HIDE_DELAY
import com.grmemby.player.core.PlayerConstants.GESTURE_INDICATOR_HIDE_DELAY
import com.grmemby.player.core.PlayerConstants.NEXT_EPISODE_AUTOPLAY_DELAY
import com.grmemby.player.core.PlayerConstants.NEXT_EPISODE_PROGRESS_UPDATE_DELAY
import com.grmemby.player.core.PlayerState
import com.grmemby.player.core.SkippableSegmentAction
import com.grmemby.player.core.SkippableSegmentType
import kotlinx.coroutines.delay

@Composable
internal fun PlayerScreenEffects(
    context: Context,
    currentView: View,
    lifecycleOwner: LifecycleOwner,
    mediaId: String,
    initialItemDetails: BaseItemDto?,
    preferredAudioStreamIndex: Int?,
    preferredSubtitleStreamIndex: Int?,
    startPlayback: Boolean,
    viewModel: PlayerViewModel,
    onPlaybackCompleted: ((String) -> Unit)?,
    preferredStreamIndexes: PreferredStreamIndexes,
    playerState: PlayerState,
    useDeviceVolumeInPlayer: Boolean,
    useDeviceBrightnessInPlayer: Boolean,
    audioManager: AudioManager,
    originalVolume: Int,
    playerBrightness: Float,
    playerVolume: Float,
    showAudioTrackDialog: Boolean,
    showSubtitleTrackDialog: Boolean,
    showStreamingQualityDialog: Boolean,
    showAudioTranscodingDialog: Boolean,
    showMediaInfo: Boolean,
    showEpisodePicker: Boolean,
    showDanmakuSettingsDialog: Boolean,
    isOverlayInteractionInProgress: Boolean,
    autoHideKey: Int,
    isScrubbing: Boolean,
    hideSystemBars: () -> Unit,
    uiStateProvider: () -> PlayerUiState,
    onUiStateChange: (PlayerUiState) -> Unit,
    initializedMediaIdProvider: () -> String?,
    onInitializedMediaIdChange: (String?) -> Unit,
    onLifecycleChange: (Lifecycle.Event) -> Unit,
    onCurrentAudioTranscodeModeChange: (AudioTranscodeMode) -> Unit,
    onPreferredStreamIndexesChanged: (Int?, Int?) -> Unit
) {
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        val activity = context as? Activity
        val originalRequestedOrientation = activity?.requestedOrientation
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemBars()
        }

        onDispose {
            currentView.keepScreenOn = false
            activity?.let { act ->
                act.requestedOrientation =
                    originalRequestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!useDeviceVolumeInPlayer) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                }
                val layoutParams = act.window.attributes
                layoutParams.screenBrightness = -1f
                act.window.attributes = layoutParams
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            onLifecycleChange(event)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(mediaId) {
        if (initializedMediaIdProvider() == mediaId) return@LaunchedEffect

        try {
            if (initializedMediaIdProvider() != null) {
                viewModel.releasePlayer()
            }
            onUiStateChange(uiStateProvider().copy(currentPosition = 0L, isPlaying = false))
            viewModel.initializePlayer(
                context = context,
                mediaId = mediaId,
                initialItemDetails = initialItemDetails,
                preferredAudioStreamIndex = preferredAudioStreamIndex,
                preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
                startPlayback = startPlayback
            )
            onInitializedMediaIdChange(mediaId)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(viewModel, onPlaybackCompleted) {
        viewModel.playbackCompletedEvents.collect { completedMediaId ->
            onPlaybackCompleted?.invoke(completedMediaId)
        }
    }

    LaunchedEffect(viewModel.exoPlayer, viewModel.mpvPlayer) {
        while (true) {
            onUiStateChange(
                uiStateProvider().copy(
                    currentPosition = viewModel.getCurrentPosition(),
                    bufferedPosition = viewModel.getBufferedPosition(),
                    isPlaying = viewModel.isPlayingNow()
                )
            )
            delay(100)
        }
    }

    LaunchedEffect(
        initializedMediaIdProvider(),
        preferredStreamIndexes.audioStreamIndex,
        preferredStreamIndexes.subtitleStreamIndex
    ) {
        if (initializedMediaIdProvider() == mediaId) {
            onPreferredStreamIndexesChanged(
                preferredStreamIndexes.audioStreamIndex,
                preferredStreamIndexes.subtitleStreamIndex
            )
        }
    }

    LaunchedEffect(playerState.currentAudioTranscodeMode) {
        onCurrentAudioTranscodeModeChange(playerState.currentAudioTranscodeMode)
    }

    LaunchedEffect(useDeviceBrightnessInPlayer) {
        val activity = context as? Activity
        activity?.let { act ->
            val layoutParams = act.window.attributes
            // -1f keeps the fullscreen player on the system brightness curve by default.
            // Once the user swipes brightness, PlayerScreen sets an explicit 0f..1f value.
            layoutParams.screenBrightness = if (useDeviceBrightnessInPlayer) -1f else playerBrightness
            act.window.attributes = layoutParams
        }

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val initialVolume = (playerVolume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, initialVolume, 0)
    }

    LaunchedEffect(uiStateProvider().volumeLevel) {
        if (uiStateProvider().volumeLevel != null) {
            delay(CONTROLS_AUTO_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(volumeLevel = null))
        }
    }

    LaunchedEffect(uiStateProvider().brightnessLevel) {
        if (uiStateProvider().brightnessLevel != null) {
            delay(CONTROLS_AUTO_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(brightnessLevel = null))
        }
    }

    LaunchedEffect(uiStateProvider().seekPosition) {
        if (uiStateProvider().seekPosition != null) {
            delay(GESTURE_INDICATOR_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(seekPosition = null))
        }
    }

    LaunchedEffect(
        uiStateProvider().controlsVisible,
        showAudioTrackDialog,
        showSubtitleTrackDialog,
        showStreamingQualityDialog,
        showAudioTranscodingDialog,
        showMediaInfo,
        showEpisodePicker,
        showDanmakuSettingsDialog,
        isOverlayInteractionInProgress
    ) {
        if (
            uiStateProvider().controlsVisible ||
            showAudioTrackDialog ||
            showSubtitleTrackDialog ||
            showStreamingQualityDialog ||
            showAudioTranscodingDialog ||
            showMediaInfo ||
            showEpisodePicker ||
            showDanmakuSettingsDialog ||
            isOverlayInteractionInProgress
        ) {
            hideSystemBars()
        }
    }

    LaunchedEffect(
        uiStateProvider().controlsVisible,
        playerState.hasStartedPlayback,
        autoHideKey,
        isScrubbing,
        showAudioTrackDialog,
        showSubtitleTrackDialog,
        showStreamingQualityDialog,
        showAudioTranscodingDialog,
        showMediaInfo,
        showEpisodePicker,
        showDanmakuSettingsDialog,
        isOverlayInteractionInProgress
    ) {
        val shouldKeepControlsVisible = isScrubbing ||
            showAudioTrackDialog ||
            showSubtitleTrackDialog ||
            showStreamingQualityDialog ||
            showAudioTranscodingDialog ||
            showMediaInfo ||
            showEpisodePicker ||
            showDanmakuSettingsDialog ||
            isOverlayInteractionInProgress
        if (uiStateProvider().controlsVisible && playerState.hasStartedPlayback && !shouldKeepControlsVisible) {
            delay(3000L)
            onUiStateChange(uiStateProvider().copy(controlsVisible = false))
        }
    }
}

@Composable
internal fun BoxScope.PlayerOverlayHost(
    uiState: PlayerUiState,
    playerState: PlayerState,
    watchPartyState: WatchPartyUiState,
    watchPartyVoiceChatState: WatchPartyVoiceChatState,
    currentStreamingQuality: String,
    decoderModeLabel: String,
    onToggleDecoderMode: () -> Unit,
    hasPlaybackSettings: Boolean,
    chapterMarkersEnabled: Boolean,
    seekBackwardSeconds: Int,
    seekForwardSeconds: Int,
    activeSkippableSegment: SkippableSegmentAction?,
    activeCreditsSegment: SkippableSegmentAction?,
    dismissedCreditsPrompt: Boolean,
    canWatchPreviousEpisode: Boolean,
    canWatchNextEpisode: Boolean,
    viewModel: PlayerViewModel,
    onBackPressed: (() -> Unit)?,
    resetAutoHideTimer: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onWatchCredits: () -> Unit,
    onWatchPreviousEpisode: () -> Unit,
    onWatchNextEpisode: () -> Unit,
    onShowMediaInfo: () -> Unit,
    onShowDolbyAudioInfo: () -> Unit,
    onSelectPlaybackSpeed: (Float) -> Unit,
    onSpeedMenuExpandedChange: (Boolean) -> Unit,
    onShowEpisodePicker: () -> Unit,
    onShowDanmakuSettings: () -> Unit,
    onShowWatchParty: () -> Unit,
    onCopyWatchPartyInvite: () -> Unit,
    onLeaveWatchParty: () -> Unit,
    onToggleWatchPartyVoiceChat: () -> Unit,
    onShowRoomChat: () -> Unit,
    onShowStreamingQualityDialog: () -> Unit,
    onShowAudioTranscodingDialog: () -> Unit,
    onShowAudioTrackDialog: () -> Unit,
    onShowSubtitleTrackDialog: () -> Unit,
    suppressBackgroundScrim: Boolean,
    hideControlsForModal: Boolean = false,
    showPictureInPictureButton: Boolean,
    onEnterPictureInPicture: () -> Unit
) {
    val systemStatus = rememberPlayerSystemStatus()
    var nextEpisodeButtonProgress by remember(
        activeCreditsSegment?.startMs,
        activeCreditsSegment?.endMs,
        canWatchNextEpisode,
        dismissedCreditsPrompt
    ) {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(
        activeCreditsSegment?.startMs,
        activeCreditsSegment?.endMs,
        canWatchNextEpisode,
        dismissedCreditsPrompt
    ) {
        nextEpisodeButtonProgress = 0f

        if (activeCreditsSegment == null || !canWatchNextEpisode || dismissedCreditsPrompt) {
            return@LaunchedEffect
        }

        var elapsedMs = 0L
        while (elapsedMs < NEXT_EPISODE_AUTOPLAY_DELAY) {
            nextEpisodeButtonProgress =
                elapsedMs.toFloat() / NEXT_EPISODE_AUTOPLAY_DELAY.toFloat()
            delay(NEXT_EPISODE_PROGRESS_UPDATE_DELAY)
            elapsedMs += NEXT_EPISODE_PROGRESS_UPDATE_DELAY
        }

        nextEpisodeButtonProgress = 1f
        onWatchNextEpisode()
    }

    if (uiState.controlsVisible && !hideControlsForModal) {
        ControlsOverlay(
            title = playerState.mediaTitle,
            mediaLogoUrl = playerState.mediaLogoUrl,
            seasonEpisodeLabel = playerState.seasonEpisodeLabel,
            chapterMarkers = if (chapterMarkersEnabled) playerState.chapterMarkers else emptyList(),
            isPlaying = playerState.playWhenReady,
            currentPosition = uiState.currentPosition,
            duration = viewModel.getDuration(),
            bufferedPosition = uiState.bufferedPosition,
            onBackClick = {
                viewModel.exitPlaybackPage {
                    onBackPressed?.invoke()
                }
            },
            onPlayPause = {
                resetAutoHideTimer()
                viewModel.togglePlayPause()
            },
            onSeek = { progress ->
                resetAutoHideTimer()
                viewModel.seekToProgress(progress)
            },
            onScrubStateChange = { scrubbing ->
                onScrubbingChange(scrubbing)
                resetAutoHideTimer()
            },
            spatializationResult = playerState.spatializationResult,
            isSpatialAudioEnabled = playerState.isSpatialAudioEnabled,
            isHdrEnabled = playerState.isHdrEnabled,
            currentPlaybackSpeed = playerState.playbackSpeed,
            onShowMediaInfo = {
                resetAutoHideTimer()
                onShowMediaInfo()
            },
            onShowDolbyAudioInfo = {
                resetAutoHideTimer()
                onShowDolbyAudioInfo()
            },
            onSelectPlaybackSpeed = { speed ->
                resetAutoHideTimer()
                onSelectPlaybackSpeed(speed)
            },
            onSpeedMenuExpandedChange = { expanded ->
                if (expanded) resetAutoHideTimer()
                onSpeedMenuExpandedChange(expanded)
            },
            onShowEpisodePicker = {
                resetAutoHideTimer()
                onShowEpisodePicker()
            },
            watchPartyRoomCode = watchPartyState.roomId,
            watchPartyMemberCount = watchPartyState.memberCount,
            watchPartyIsHost = watchPartyState.isHost,
            onWatchPartyClick = {
                resetAutoHideTimer()
                onShowWatchParty()
            },
            onWatchPartyCopyInvite = {
                resetAutoHideTimer()
                onCopyWatchPartyInvite()
            },
            onWatchPartyLeave = {
                resetAutoHideTimer()
                onLeaveWatchParty()
            },
            isLocked = playerState.isLocked,
            onToggleLock = {
                resetAutoHideTimer()
                viewModel.toggleLock()
            },
            currentStreamingQuality = currentStreamingQuality,
            decoderModeLabel = decoderModeLabel,
            playbackInfoLabel = buildPlaybackInfoLabel(playerState),
            onToggleDecoderMode = {
                resetAutoHideTimer()
                onToggleDecoderMode()
            },
            showPictureInPictureButton = showPictureInPictureButton,
            onEnterPictureInPicture = onEnterPictureInPicture,
            showPlaybackSettingsButton = hasPlaybackSettings,
            onShowPlaybackSettings = {
                resetAutoHideTimer()
                if (playerState.isVideoTranscodingAllowed) {
                    onShowStreamingQualityDialog()
                } else if (playerState.isAudioTranscodingAllowed) {
                    onShowAudioTranscodingDialog()
                }
            },
            onShowAudioTrackSelection = {
                resetAutoHideTimer()
                onShowAudioTrackDialog()
            },
            onShowSubtitleTrackSelection = {
                resetAutoHideTimer()
                onShowSubtitleTrackDialog()
            },
            onShowDanmakuSettings = {
                resetAutoHideTimer()
                onShowDanmakuSettings()
            },
            onCycleAspectRatio = {
                resetAutoHideTimer()
                viewModel.cycleAspectRatio()
            },
            onSeekBackward = {
                resetAutoHideTimer()
                viewModel.seekBackward()
            },
            onSeekForward = {
                resetAutoHideTimer()
                viewModel.seekForward()
            },
            canWatchPreviousEpisode = canWatchPreviousEpisode,
            canWatchNextEpisode = canWatchNextEpisode,
            onWatchPreviousEpisode = {
                resetAutoHideTimer()
                onWatchPreviousEpisode()
            },
            onWatchNextEpisode = {
                resetAutoHideTimer()
                onWatchNextEpisode()
            },
            showVoiceChatButton = watchPartyState.isInRoom,
            isVoiceChatConnected = watchPartyVoiceChatState.isConnected,
            isVoiceChatSpeaking = watchPartyVoiceChatState.isSpeaking,
            isVoiceChatConnecting = watchPartyVoiceChatState.isConnecting,
            systemStatus = systemStatus,
            suppressBackgroundScrim = suppressBackgroundScrim,
            onToggleVoiceChat = {
                resetAutoHideTimer()
                onToggleWatchPartyVoiceChat()
            },
            onShowRoomChat = {
                resetAutoHideTimer()
                onShowRoomChat()
            },
            seekBackwardSeconds = seekBackwardSeconds,
            seekForwardSeconds = seekForwardSeconds,
            modifier = Modifier.fillMaxSize()
        )
    }

    AnimatedVisibility(
        visible = false,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 16.dp)
    ) {
        WatchPartyVoiceChatEdgeButton(
            isConnected = watchPartyVoiceChatState.isConnected,
            isConnecting = watchPartyVoiceChatState.isConnecting,
            isSpeaking = watchPartyVoiceChatState.isSpeaking,
            onClick = {
                resetAutoHideTimer()
                onToggleWatchPartyVoiceChat()
            }
        )
    }

    AnimatedVisibility(
        visible = activeSkippableSegment != null &&
            activeSkippableSegment.type != SkippableSegmentType.CREDITS &&
            uiState.controlsVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 92.dp, bottom = 18.dp)
    ) {
        FilledTonalButton(
            onClick = {
                resetAutoHideTimer()
                activeSkippableSegment?.seekToMs?.let(viewModel::seekTo)
            },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color(0xCC1E293B),
                contentColor = Color.White
            ),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            elevation = ButtonDefaults.filledTonalButtonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 1.dp
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(
                    when (activeSkippableSegment?.type) {
                        SkippableSegmentType.RECAP -> R.string.player_skip_recap
                        SkippableSegmentType.PREVIEW -> R.string.player_skip_preview
                        else -> R.string.player_skip_intro
                    }
                ),
                fontSize = 14.sp
            )
        }
    }

    AnimatedVisibility(
        visible = activeCreditsSegment != null &&
            !dismissedCreditsPrompt,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 92.dp, bottom = 18.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    resetAutoHideTimer()
                    onWatchCredits()
                },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xB81E293B),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_watch_credits),
                    fontSize = 14.sp
                )
            }

            if (canWatchNextEpisode) {
                NextEpisodeProgressPill(
                    label = stringResource(R.string.player_watch_next_episode),
                    progressFraction = nextEpisodeButtonProgress,
                    onClick = {
                        resetAutoHideTimer()
                        onWatchNextEpisode()
                    }
                )
            }
        }
    }

    GestureIndicators(
        volumeLevel = uiState.volumeLevel,
        brightnessLevel = uiState.brightnessLevel,
        seekPosition = uiState.seekPosition,
        seekSide = uiState.seekSide
    )

    if (shouldShowPlayerLoadingOverlay(playerState.isLoading, playerState.playWhenReady)) {
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

@Composable
private fun WatchPartyVoiceChatEdgeButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit
) {
    val icon = if (isSpeaking) Icons.Filled.Mic else Icons.Filled.MicOff
    val containerColor = if (isSpeaking) {
        Color(0xFF16A34A).copy(alpha = 0.88f)
    } else {
        Color(0xB81E293B)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when {
                isSpeaking -> "关闭麦克风，继续收听语音聊天"
                isConnecting -> "语音聊天连接中"
                isConnected -> "开启麦克风，当前正在收听语音聊天"
                else -> "开启麦克风，加入语音聊天"
            },
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun NextEpisodeProgressPill(
    label: String,
    progressFraction: Float,
    onClick: () -> Unit
) {
    val clampedProgress = progressFraction.coerceIn(0f, 1f)
    val pillShape = RoundedCornerShape(999.dp)

    Box(
        modifier = Modifier
            .clip(pillShape)
            .drawBehind {
                drawRect(color = Color(0xB81E293B))
                if (clampedProgress > 0f) {
                    drawRect(
                        color = Color.White,
                        size = Size(size.width * clampedProgress, size.height)
                    )
                }
            }
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = pillShape
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            modifier = Modifier.drawWithContent {
                val progressEdge = (size.width * clampedProgress).coerceIn(0f, size.width)
                clipRect(left = progressEdge) {
                    this@drawWithContent.drawContent()
                }
            },
            color = Color.White
        )

        Text(
            text = label,
            fontSize = 14.sp,
            modifier = Modifier.drawWithContent {
                val progressEdge = (size.width * clampedProgress).coerceIn(0f, size.width)
                val overlapPx = 1.dp.toPx()
                clipRect(right = (progressEdge + overlapPx).coerceAtMost(size.width)) {
                    this@drawWithContent.drawContent()
                }
            },
            color = Color.Black
        )
    }
}

@Composable
internal fun PlayerDialogsHost(
    playerState: PlayerState,
    showAudioTrackDialog: Boolean,
    showSubtitleTrackDialog: Boolean,
    showStreamingQualityDialog: Boolean,
    showAudioTranscodingDialog: Boolean,
    showMediaInfo: Boolean,
    availableStreamingQualityOptions: List<String>,
    currentStreamingQuality: String,
    currentAudioTranscodeMode: AudioTranscodeMode,
    mediaInfoSnapshot: MediaMetadataInfo?,
    onAudioTrackSelected: (String) -> Unit,
    onSubtitleTrackSelected: (String) -> Unit,
    onStreamingQualitySelected: (String) -> Unit,
    onAudioTranscodingSelected: (AudioTranscodeMode) -> Unit,
    onDismissAudioTrackDialog: () -> Unit,
    onDismissSubtitleTrackDialog: () -> Unit,
    onDismissStreamingQualityDialog: () -> Unit,
    onDismissAudioTranscodingDialog: () -> Unit,
    onDismissMediaInfo: () -> Unit
) {
    AudioTrackSelectionDialog(
        isVisible = showAudioTrackDialog,
        audioTracks = playerState.availableAudioTracks,
        currentAudioTrack = playerState.currentAudioTrack,
        onTrackSelected = onAudioTrackSelected,
        onDismiss = onDismissAudioTrackDialog
    )

    SubtitleTrackSelectionDialog(
        isVisible = showSubtitleTrackDialog,
        subtitleTracks = playerState.availableSubtitleTracks,
        currentSubtitleTrack = playerState.currentSubtitleTrack,
        onTrackSelected = onSubtitleTrackSelected,
        onDismiss = onDismissSubtitleTrackDialog
    )

    StreamingQualitySelectionDialog(
        isVisible = showStreamingQualityDialog && playerState.isVideoTranscodingAllowed,
        qualityOptions = availableStreamingQualityOptions,
        currentQuality = currentStreamingQuality,
        onQualitySelected = onStreamingQualitySelected,
        onDismiss = onDismissStreamingQualityDialog
    )

    AudioTranscodingModeDialog(
        isVisible = showAudioTranscodingDialog && playerState.isAudioTranscodingAllowed,
        currentMode = currentAudioTranscodeMode,
        onModeSelected = onAudioTranscodingSelected,
        onDismiss = onDismissAudioTranscodingDialog
    )

    if (showMediaInfo) {
        mediaInfoSnapshot?.let { mediaInfo ->
            MediaInfoDialog(
                mediaInfo = mediaInfo,
                onDismiss = onDismissMediaInfo
            )
        }
    }
}

private fun buildPlaybackInfoLabel(playerState: PlayerState): String {
    val videoTrack = playerState.currentVideoTrack ?: playerState.availableVideoTracks.firstOrNull()
    val codec = videoTrack?.codec
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()
        ?.replace("AVC1", "H264")
        ?.replace("AVC", "H264")
        ?.replace("HEVC", "H265")
        ?.replace("H.264", "H264")
        ?.replace("H.265", "H265")
    val resolution = videoTrack?.height
        ?.takeIf { it > 0 }
        ?.let { "${it}P" }
    return listOfNotNull(codec, resolution).joinToString("  ")
}
