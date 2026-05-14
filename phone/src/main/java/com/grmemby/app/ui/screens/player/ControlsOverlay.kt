package com.grmemby.app.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.drawToBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.grmemby.app.R
import com.grmemby.app.ui.components.danmaku.DanmakuApiUrlListEditor
import com.grmemby.app.ui.components.danmaku.DanmakuComment
import com.grmemby.app.ui.components.danmaku.DanmakuEngine
import com.grmemby.app.ui.components.danmaku.DanmakuSearchCandidate
import com.grmemby.app.ui.components.danmaku.DanmakuSearchRequest
import com.grmemby.app.ui.components.common.ScreenCastButton
import com.grmemby.app.ui.components.glass.GlassDropdownMenu
import com.grmemby.app.ui.components.glass.GlassDropdownMenuItem
import com.grmemby.detail.SpatializationResult
import com.grmemby.player.core.ChapterMarker
import com.grmemby.player.core.PlayerConstants.PROGRESS_BAR_HEIGHT_DP
import com.grmemby.player.preferences.PlayerPreferences
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ControlsOverlay(
    title: String,
    mediaLogoUrl: String? = null,
    seasonEpisodeLabel: String? = null,
    chapterMarkers: List<ChapterMarker> = emptyList(),
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    bufferedPosition: Long = 0L,
    modifier: Modifier = Modifier,
    spatializationResult: SpatializationResult? = null,
    isSpatialAudioEnabled: Boolean = false,
    isHdrEnabled: Boolean = false,
    currentPlaybackSpeed: Float = 1f,
    onShowMediaInfo: () -> Unit = {},
    onSelectPlaybackSpeed: (Float) -> Unit = {},
    onSpeedMenuExpandedChange: (Boolean) -> Unit = {},
    onShowDolbyAudioInfo: () -> Unit = {},
    onShowEpisodePicker: () -> Unit = {},
    watchPartyRoomCode: String? = null,
    watchPartyMemberCount: Int = 0,
    watchPartyIsHost: Boolean = false,
    onWatchPartyClick: () -> Unit = {},
    onWatchPartyCopyInvite: () -> Unit = {},
    onWatchPartyLeave: () -> Unit = {},
    isLocked: Boolean = false,
    onToggleLock: () -> Unit = {},
    currentStreamingQuality: String = "",
    decoderModeLabel: String = "AUTO",
    playbackInfoLabel: String = "",
    onToggleDecoderMode: () -> Unit = {},
    showPictureInPictureButton: Boolean = false,
    showPlaybackSettingsButton: Boolean = true,
    onEnterPictureInPicture: () -> Unit = {},
    onShowPlaybackSettings: () -> Unit = {},
    onShowAudioTrackSelection: () -> Unit = {},
    onShowSubtitleTrackSelection: () -> Unit = {},
    onShowDanmakuSettings: () -> Unit = {},
    onCycleAspectRatio: () -> Unit = {},
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    seekBackwardSeconds: Int = 30,
    seekForwardSeconds: Int = 30,
    canWatchPreviousEpisode: Boolean = false,
    canWatchNextEpisode: Boolean = false,
    onWatchPreviousEpisode: () -> Unit = {},
    onWatchNextEpisode: () -> Unit = {},
    showVoiceChatButton: Boolean = false,
    isVoiceChatConnected: Boolean = false,
    isVoiceChatSpeaking: Boolean = false,
    isVoiceChatConnecting: Boolean = false,
    onToggleVoiceChat: () -> Unit = {},
    onShowRoomChat: () -> Unit = {},
    systemStatus: PlayerSystemStatus = rememberPlayerSystemStatus(),
    onScrubStateChange: (Boolean) -> Unit = {},
    suppressBackgroundScrim: Boolean = false
) {
    var scrubPreviewProgress by remember { mutableStateOf<Float?>(null) }
    val displayedPosition = scrubPreviewProgress
        ?.takeIf { duration > 0L }
        ?.let { (duration * it).toLong() }
        ?: currentPosition
    val overlayTitle = listOf(title, seasonEpisodeLabel)
        .filter { it?.isNotBlank() == true }
        .joinToString(" · ") { it.orEmpty() }
        .ifBlank { title }
    val watchPartyStatus = watchPartyRoomCode
        ?.takeIf { it.isNotBlank() }
        ?.let { roomCode ->
            buildString {
                if (watchPartyIsHost) append("房主 ")
                append(watchPartyMemberCount.coerceAtLeast(1))
                append("人 ")
                append(roomCode)
            }
        }

    val context = LocalContext.current
    val rootView = LocalView.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (suppressBackgroundScrim) {
                        listOf(Color.Transparent, Color.Transparent)
                    } else {
                        listOf(
                            Color.Black.copy(alpha = 0.60f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.82f)
                        )
                    }
                )
            )
    ) {
        // Top: reference-style first status row + second action row. No room/member chip.
        Box(
            modifier = Modifier
                .fillMaxWidth(PlayerOverlayHorizontalContentFraction)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                .padding(top = 5.dp)
                .align(Alignment.TopCenter)
        ) {
            if (!isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(142.dp)
                    ) {
                        Text(
                            text = systemStatus.time,
                            color = Color.White.copy(alpha = 0.94f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = "网络",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = systemStatus.networkSpeed,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    if (overlayTitle.isNotBlank()) {
                        Text(
                            text = overlayTitle,
                            color = Color.White.copy(alpha = 0.94f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(142.dp)
                    ) {
                        Text(
                            text = systemStatus.batteryLabel,
                            color = Color.White.copy(alpha = 0.94f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = Icons.Filled.BatteryFull,
                            contentDescription = "电量",
                            tint = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 27.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerLineIconButton(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出",
                        onClick = onBackClick
                    )
                    ScreenCastButton(
                        onConnectedClick = onShowMediaInfo,
                        size = 36.dp
                    )
                    PlayerLineIconButton(
                        imageVector = Icons.Outlined.AspectRatio,
                        contentDescription = "拓展全屏",
                        onClick = onCycleAspectRatio
                    )
                    PlayerLineIconButton(
                        imageVector = Icons.Outlined.ScreenRotation,
                        contentDescription = "旋转屏幕",
                        onClick = { rotatePlayerScreen(context) }
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 27.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showPictureInPictureButton) {
                        PlayerLineIconButton(
                            imageVector = Icons.Outlined.PictureInPictureAlt,
                            contentDescription = "小窗",
                            onClick = onEnterPictureInPicture
                        )
                    }
                    if (showPlaybackSettingsButton) {
                        PlayerLineIconButton(
                            imageVector = Icons.Outlined.OndemandVideo,
                            contentDescription = "当前视频 ($currentStreamingQuality)",
                            onClick = onShowPlaybackSettings
                        )
                    }
                    PlayerLineIconButton(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "截屏",
                        onClick = { capturePlayerScreenshot(context, rootView) }
                    )
                    PlayerLineIconButton(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "信息",
                        onClick = onShowMediaInfo
                    )
                }
            } else {
                PlayerLineIconButton(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "解锁",
                    onClick = onToggleLock,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        if (!isLocked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(PlayerOverlayHorizontalContentFraction)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 14.dp)
                    .align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        DecoderModeInlineButton(
                            label = decoderModeLabel.ifBlank { "AUTO" },
                            onClick = onToggleDecoderMode
                        )
                        if (playbackInfoLabel.isNotBlank()) {
                            Text(
                                text = playbackInfoLabel,
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!watchPartyStatus.isNullOrBlank()) {
                            Text(
                                text = watchPartyStatus,
                                color = Color(0xFF8BC7FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isHdrEnabled) {
                            Text(
                                text = "HDR",
                                color = Color(0xFFFFD54F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerCircleTextButton(
                            label = "弹",
                            contentDescription = "弹幕设置",
                            onClick = onShowDanmakuSettings,
                            buttonSize = 38.dp,
                            outlined = true
                        )
                        PlayerCircleIconButton(
                            imageVector = Icons.Outlined.GraphicEq,
                            contentDescription = "音轨",
                            onClick = onShowAudioTrackSelection,
                            buttonSize = 38.dp,
                            iconSize = 21.dp,
                            outlined = true
                        )
                        PlayerCircleIconButton(
                            imageVector = Icons.Outlined.Subtitles,
                            contentDescription = "字幕",
                            onClick = onShowSubtitleTrackSelection,
                            buttonSize = 38.dp,
                            iconSize = 21.dp,
                            outlined = true
                        )
                        PlaybackSpeedMenuButton(
                            currentSpeed = currentPlaybackSpeed,
                            onSelectSpeed = onSelectPlaybackSpeed,
                            onExpandedChange = onSpeedMenuExpandedChange,
                            buttonSize = 38.dp,
                            iconSize = 21.dp
                        )
                        if (showVoiceChatButton) {
                            PlayerCircleIconButton(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "打开房间聊天",
                                onClick = onShowRoomChat,
                                buttonSize = 38.dp,
                                iconSize = 21.dp,
                                outlined = true
                            )
                            PlayerCircleIconButton(
                                imageVector = if (isVoiceChatSpeaking) Icons.Filled.Mic else Icons.Outlined.MicOff,
                                contentDescription = when {
                                    isVoiceChatConnecting -> "语音连接中"
                                    isVoiceChatSpeaking -> "关闭麦克风"
                                    isVoiceChatConnected -> "开启麦克风"
                                    else -> "开启语音"
                                },
                                onClick = onToggleVoiceChat,
                                buttonSize = 38.dp,
                                iconSize = 21.dp,
                                outlined = true,
                                alpha = if (isVoiceChatConnecting) 0.58f else 1f,
                                active = isVoiceChatSpeaking,
                                activeColor = Color(0xFF62D7FF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(11.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(displayedPosition),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.widthIn(min = 55.dp)
                    )
                    SeekBar(
                        progress = if (duration > 0 && currentPosition >= 0) {
                            (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                        } else 0f,
                        bufferedProgress = if (duration > 0) {
                            (bufferedPosition.coerceAtLeast(currentPosition).toFloat() / duration.toFloat())
                                .coerceIn(0f, 1f)
                        } else 0f,
                        duration = duration,
                        chapterMarkers = chapterMarkers,
                        onSeek = onSeek,
                        onScrubProgressChange = {
                            scrubPreviewProgress = it
                            onScrubStateChange(it != null)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatTime(if (duration > 0) duration else 0L),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.widthIn(min = 62.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    PlayerLineIconButton(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = "锁定控制",
                        onClick = onToggleLock,
                        modifier = Modifier.align(Alignment.CenterStart),
                        iconSize = 26.dp,
                        buttonSize = 42.dp
                    )
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportIconButton(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = if (canWatchPreviousEpisode) "上一集" else "后退 $seekBackwardSeconds 秒",
                            onClick = {
                                if (canWatchPreviousEpisode) onWatchPreviousEpisode() else onSeekBackward()
                            }
                        )
                        PlayPauseTransportButton(
                            isPlaying = isPlaying,
                            onClick = onPlayPause
                        )
                        TransportIconButton(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = if (canWatchNextEpisode) "下一集" else "前进 $seekForwardSeconds 秒",
                            onClick = {
                                if (canWatchNextEpisode) onWatchNextEpisode() else onSeekForward()
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerLineIconButton(
                            imageVector = Icons.Outlined.QueueMusic,
                            contentDescription = "选择剧集",
                            onClick = onShowEpisodePicker,
                            iconSize = 27.dp,
                            buttonSize = 42.dp
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun PlayerLineIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 23.dp,
    backgroundAlpha: Float = 0f
) {
    val buttonShape = RoundedCornerShape(999.dp)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .then(
                if (backgroundAlpha > 0f) {
                    Modifier.background(Color.Black.copy(alpha = backgroundAlpha), buttonShape)
                } else {
                    Modifier
                }
            )
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.94f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun PlayerCircleIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 44.dp,
    iconSize: Dp = 23.dp,
    outlined: Boolean = false,
    alpha: Float = 1f,
    active: Boolean = false,
    activeColor: Color = Color(0xFF62D7FF)
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .background(
                color = when {
                    outlined && active -> Color.Transparent
                    outlined -> Color.Transparent
                    else -> Color.Black.copy(alpha = 0.38f)
                },
                shape = RoundedCornerShape(999.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (outlined) Modifier else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (outlined) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = (if (active) activeColor else Color.White).copy(alpha = 0.82f * alpha),
                        radius = size.minDimension / 2f - 1.2.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
                    )
                }
            }
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = (if (active) activeColor else Color.White).copy(alpha = 0.94f * alpha),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun PlaybackSpeedMenuButton(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 38.dp,
    iconSize: Dp = 21.dp
) {
    var expanded by remember { mutableStateOf(false) }
    val speedOptions = listOf(0.5f, 1f, 1.3f, 1.5f, 1.8f, 2f, 2.5f, 3f, 5f)

    DisposableEffect(Unit) {
        onDispose { onExpandedChange(false) }
    }

    Box(modifier = modifier) {
        PlayerCircleIconButton(
            imageVector = Icons.Outlined.Speed,
            contentDescription = "倍速",
            onClick = {
                val nextExpanded = !expanded
                expanded = nextExpanded
                onExpandedChange(nextExpanded)
            },
            buttonSize = buttonSize,
            iconSize = iconSize,
            outlined = true,
            active = abs(currentSpeed - 1f) > 0.01f,
            activeColor = Color(0xFF62D7FF)
        )
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                onExpandedChange(false)
            },
            offset = DpOffset(x = 0.dp, y = (-214).dp),
            minWidth = 128.dp
        ) {
            speedOptions.forEach { speed ->
                val selected = abs(currentSpeed - speed) < 0.01f
                GlassDropdownMenuItem(
                    text = formatSpeedLabel(speed),
                    selected = selected,
                    leadingIcon = if (selected) Icons.Filled.Check else null,
                    onClick = {
                        onSelectSpeed(speed)
                        expanded = false
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

private fun formatSpeedLabel(speed: Float): String = when {
    abs(speed - speed.toInt()) < 0.01f -> "${speed.toInt()}×"
    else -> "${speed}×"
}

@Composable
internal fun DanmakuSettingsDialog(
    playerPreferences: PlayerPreferences,
    currentSearchRequest: DanmakuSearchRequest? = null,
    currentDanmakuStatus: String = "未找到弹幕",
    onSettingsChanged: () -> Unit = {},
    onDanmakuSearchLoaded: (List<DanmakuComment>, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val danmakuAccent = Color(0xFF3B82F6)
    var danmakuEnabled by remember { mutableStateOf(playerPreferences.isDanmakuEnabled()) }
    var filterWords by remember { mutableStateOf(playerPreferences.getDanmakuFilterWords()) }
    var showFilterWordsDialog by remember { mutableStateOf(false) }
    var showApiEditorDialog by remember { mutableStateOf(false) }
    var showSearchResultsDialog by remember { mutableStateOf(false) }
    var matchMode by remember { mutableStateOf(playerPreferences.getDanmakuMatchMode()) }
    var apiEndpoints by remember { mutableStateOf(playerPreferences.getDanmakuApiEndpoints()) }
    var lineCount by remember { mutableIntStateOf(playerPreferences.getDanmakuLineCount()) }
    var speedPercent by remember { mutableIntStateOf(playerPreferences.getDanmakuSpeedPercent()) }
    var opacityPercent by remember { mutableIntStateOf(playerPreferences.getDanmakuOpacityPercent()) }
    var fontSizeSp by remember { mutableIntStateOf(playerPreferences.getDanmakuFontSizeSp()) }
    var boldEnabled by remember { mutableStateOf(playerPreferences.isDanmakuBoldEnabled()) }
    var mergeDuplicates by remember { mutableStateOf(playerPreferences.isDanmakuMergeDuplicatesEnabled()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HideSystemBarsForDanmakuDialogWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.CenterEnd
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val drawerWidth = if (maxWidth > maxHeight) maxWidth * 0.42f else maxWidth * 0.84f

                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    color = Color(0x801A1C25),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 280.dp, max = 388.dp)
                        .width(drawerWidth)
                        .align(Alignment.CenterEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DanmakuDrawerHeader(
                            enabled = danmakuEnabled,
                            apiCount = apiEndpoints.size,
                            accentColor = danmakuAccent,
                            onClose = onDismiss
                        )
                        DanmakuClickableSettingRow(
                            title = "当前弹幕",
                            value = currentDanmakuStatus.ifBlank { "未找到弹幕" },
                            hint = "没有自动匹配时点搜索手动选择正确季/来源",
                            accentColor = danmakuAccent,
                            actionLabel = if (apiEndpoints.isEmpty()) "添加" else "重搜",
                            onClick = {
                                if (apiEndpoints.isEmpty()) {
                                    showApiEditorDialog = true
                                } else {
                                    showSearchResultsDialog = true
                                }
                            }
                        )
                        DanmakuClickableSettingRow(
                            title = "搜索弹幕",
                            value = if (apiEndpoints.isEmpty()) "未添加 API，无法搜索" else "按当前视频搜索并选择弹幕源",
                            hint = if (apiEndpoints.isEmpty()) "点击先添加弹幕 API URL" else "点击打开搜索结果，选择正确季/来源后加载",
                            accentColor = danmakuAccent,
                            actionLabel = if (apiEndpoints.isEmpty()) "添加" else "搜索",
                            onClick = {
                                if (apiEndpoints.isEmpty()) {
                                    showApiEditorDialog = true
                                } else {
                                    showSearchResultsDialog = true
                                }
                            }
                        )
                        DanmakuSwitchSettingRow(
                            title = "弹幕总开关",
                            checked = danmakuEnabled,
                            accentColor = danmakuAccent,
                            onCheckedChange = { checked ->
                                danmakuEnabled = checked
                                playerPreferences.setDanmakuEnabled(checked)
                                onSettingsChanged()
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                        DanmakuSliderSettingRow(
                            title = "弹幕行数",
                            valueText = "${lineCount} 行",
                            value = lineCount.toFloat(),
                            valueRange = PlayerPreferences.MIN_DANMAKU_LINE_COUNT.toFloat()..PlayerPreferences.MAX_DANMAKU_LINE_COUNT.toFloat(),
                            steps = PlayerPreferences.MAX_DANMAKU_LINE_COUNT - PlayerPreferences.MIN_DANMAKU_LINE_COUNT - 1,
                            accentColor = danmakuAccent,
                            onValueChange = { value ->
                                val next = value.roundToInt().coerceIn(
                                    PlayerPreferences.MIN_DANMAKU_LINE_COUNT,
                                    PlayerPreferences.MAX_DANMAKU_LINE_COUNT
                                )
                                lineCount = next
                                playerPreferences.setDanmakuLineCount(next)
                                onSettingsChanged()
                            }
                        )
                        DanmakuSliderSettingRow(
                            title = "弹幕速度",
                            valueText = "${speedPercent}%",
                            value = speedPercent.toFloat(),
                            valueRange = PlayerPreferences.MIN_DANMAKU_SPEED_PERCENT.toFloat()..PlayerPreferences.MAX_DANMAKU_SPEED_PERCENT.toFloat(),
                            steps = 14,
                            accentColor = danmakuAccent,
                            onValueChange = { value ->
                                val next = (value / 10f).roundToInt() * 10
                                speedPercent = next.coerceIn(
                                    PlayerPreferences.MIN_DANMAKU_SPEED_PERCENT,
                                    PlayerPreferences.MAX_DANMAKU_SPEED_PERCENT
                                )
                                playerPreferences.setDanmakuSpeedPercent(speedPercent)
                                onSettingsChanged()
                            }
                        )
                        DanmakuSliderSettingRow(
                            title = "透明度",
                            valueText = "${opacityPercent}%",
                            value = opacityPercent.toFloat(),
                            valueRange = PlayerPreferences.MIN_DANMAKU_OPACITY_PERCENT.toFloat()..PlayerPreferences.MAX_DANMAKU_OPACITY_PERCENT.toFloat(),
                            steps = 7,
                            accentColor = danmakuAccent,
                            onValueChange = { value ->
                                val next = (value / 10f).roundToInt() * 10
                                opacityPercent = next.coerceIn(
                                    PlayerPreferences.MIN_DANMAKU_OPACITY_PERCENT,
                                    PlayerPreferences.MAX_DANMAKU_OPACITY_PERCENT
                                )
                                playerPreferences.setDanmakuOpacityPercent(opacityPercent)
                                onSettingsChanged()
                            }
                        )
                        DanmakuSliderSettingRow(
                            title = "字体大小",
                            valueText = "${fontSizeSp}sp",
                            value = fontSizeSp.toFloat(),
                            valueRange = PlayerPreferences.MIN_DANMAKU_FONT_SIZE_SP.toFloat()..PlayerPreferences.MAX_DANMAKU_FONT_SIZE_SP.toFloat(),
                            steps = PlayerPreferences.MAX_DANMAKU_FONT_SIZE_SP - PlayerPreferences.MIN_DANMAKU_FONT_SIZE_SP - 1,
                            accentColor = danmakuAccent,
                            onValueChange = { value ->
                                val next = value.roundToInt().coerceIn(
                                    PlayerPreferences.MIN_DANMAKU_FONT_SIZE_SP,
                                    PlayerPreferences.MAX_DANMAKU_FONT_SIZE_SP
                                )
                                fontSizeSp = next
                                playerPreferences.setDanmakuFontSizeSp(next)
                                onSettingsChanged()
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                        DanmakuSwitchSettingRow(
                            title = "粗体弹幕",
                            checked = boldEnabled,
                            accentColor = danmakuAccent,
                            onCheckedChange = { checked ->
                                boldEnabled = checked
                                playerPreferences.setDanmakuBoldEnabled(checked)
                                onSettingsChanged()
                            }
                        )
                        DanmakuSwitchSettingRow(
                            title = "合并重复弹幕",
                            checked = mergeDuplicates,
                            accentColor = danmakuAccent,
                            onCheckedChange = { checked ->
                                mergeDuplicates = checked
                                playerPreferences.setDanmakuMergeDuplicatesEnabled(checked)
                                onSettingsChanged()
                            }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                        DanmakuClickableSettingRow(
                            title = "屏蔽词设置",
                            value = filterWords.ifBlank { "未设置" },
                            hint = "点击输入关键字，多个词用英文逗号分隔",
                            accentColor = danmakuAccent,
                            onClick = { showFilterWordsDialog = true }
                        )
                        DanmakuMatchModeSettingRow(
                            selected = matchMode,
                            accentColor = danmakuAccent,
                            onSelected = { selected ->
                                matchMode = selected
                                playerPreferences.setDanmakuMatchMode(selected)
                                onSettingsChanged()
                            }
                        )
                        DanmakuClickableSettingRow(
                            title = "弹幕 API URL",
                            value = if (apiEndpoints.isEmpty()) "未添加 API" else "已添加 ${apiEndpoints.size} 个 API，最上方优先匹配",
                            hint = "点击管理 API 名称和服务器地址；默认不内置 API",
                            accentColor = danmakuAccent,
                            onClick = { showApiEditorDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showFilterWordsDialog) {
        DanmakuFilterWordsInputDialog(
            initialValue = filterWords,
            accentColor = danmakuAccent,
            onDismiss = { showFilterWordsDialog = false },
            onSave = { value ->
                filterWords = value
                playerPreferences.setDanmakuFilterWords(value)
                onSettingsChanged()
                showFilterWordsDialog = false
            }
        )
    }

    if (showApiEditorDialog) {
        DanmakuApiManagementDialog(
            apiEndpoints = apiEndpoints,
            accentColor = danmakuAccent,
            onDismiss = { showApiEditorDialog = false },
            onApiEndpointsChange = { endpoints ->
                apiEndpoints = endpoints
                playerPreferences.setDanmakuApiEndpoints(endpoints)
                onSettingsChanged()
            }
        )
    }

    if (showSearchResultsDialog) {
        val request = currentSearchRequest?.copy(matchMode = matchMode)
        if (request == null) {
            Toast.makeText(context, "当前视频信息不足，无法搜索弹幕", Toast.LENGTH_SHORT).show()
            showSearchResultsDialog = false
        } else {
            DanmakuSearchResultsDialog(
                queryTitle = request.title.ifBlank { request.fileName.ifBlank { request.mediaId } },
                apiUrls = apiEndpoints.map { it.url },
                request = request,
                filterWords = filterWords,
                mergeDuplicates = mergeDuplicates,
                accentColor = danmakuAccent,
                onDismiss = { showSearchResultsDialog = false },
                onLoaded = { comments, message ->
                    onDanmakuSearchLoaded(comments, message)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    showSearchResultsDialog = false
                }
            )
        }
    }
}

@Composable
private fun DanmakuSearchResultsDialog(
    queryTitle: String,
    apiUrls: List<String>,
    request: DanmakuSearchRequest,
    filterWords: String,
    mergeDuplicates: Boolean,
    accentColor: Color,
    onDismiss: () -> Unit,
    onLoaded: (List<DanmakuComment>, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var candidates by remember(request, apiUrls) { mutableStateOf<List<DanmakuSearchCandidate>>(emptyList()) }
    var isSearching by remember(request, apiUrls) { mutableStateOf(true) }
    var selectedSource by remember { mutableStateOf("全部") }
    var loadingKey by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request, apiUrls) {
        isSearching = true
        errorText = null
        candidates = runCatching { DanmakuEngine.searchCandidates(apiUrls, request) }
            .onFailure { error -> errorText = friendlyDanmakuError(error, "搜索弹幕失败") }
            .getOrDefault(emptyList())
        isSearching = false
    }

    val sourceTabs = remember(candidates) {
        listOf("全部") + candidates.map { it.sourceLabel }.distinct()
    }
    val visibleCandidates = remember(candidates, selectedSource) {
        if (selectedSource == "全部") candidates else candidates.filter { it.sourceLabel == selectedSource }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HideSystemBarsForDanmakuDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight
                val panelWidth = if (isLandscape) maxWidth * 0.68f else maxWidth * 0.92f
                val panelHeight = if (isLandscape) maxHeight * 0.88f else maxHeight * 0.78f
                Surface(
                    modifier = Modifier
                        .width(panelWidth.coerceAtMost(760.dp))
                        .height(panelHeight)
                        .align(Alignment.Center)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xF02D2E38),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 22.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = Color.White.copy(alpha = 0.88f)
                                )
                            }
                            Text(
                                text = queryTitle.ifBlank { "搜索弹幕" },
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White.copy(alpha = 0.88f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            sourceTabs.forEach { tab ->
                                DanmakuSourceChip(
                                    label = tab,
                                    selected = selectedSource == tab,
                                    accentColor = accentColor,
                                    onClick = { selectedSource = tab }
                                )
                            }
                        }

                        when {
                            isSearching -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = accentColor, strokeWidth = 2.dp)
                                        Spacer(Modifier.height(14.dp))
                                        Text("正在搜索弹幕…", color = Color.White.copy(alpha = 0.72f))
                                    }
                                }
                            }
                            errorText != null -> {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(errorText.orEmpty(), color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
                                }
                            }
                            visibleCandidates.isEmpty() -> {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text("没有找到可用弹幕结果", color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
                                }
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(30.dp)
                                ) {
                                    items(
                                        items = visibleCandidates,
                                        key = { candidate -> "${candidate.apiUrl}:${candidate.animeId}:${candidate.title}" }
                                    ) { candidate ->
                                        val key = "${candidate.apiUrl}:${candidate.animeId}:${candidate.commentId}:${candidate.title}"
                                        DanmakuSearchResultRow(
                                            candidate = candidate,
                                            isLoading = loadingKey == key,
                                            accentColor = accentColor,
                                            onClick = {
                                                if (loadingKey == null) {
                                                    loadingKey = key
                                                    scope.launch {
                                                        val result = runCatching {
                                                            DanmakuEngine.loadCandidate(
                                                                candidate = candidate,
                                                                request = request,
                                                                filterWords = filterWords,
                                                                mergeDuplicates = mergeDuplicates
                                                            )
                                                        }.getOrElse { error ->
                                                            loadingKey = null
                                                            errorText = friendlyDanmakuError(error, "加载弹幕失败")
                                                            return@launch
                                                        }
                                                        val message = result.error ?: result.displayTitle
                                                            ?.takeIf { it.isNotBlank() }
                                                            ?.let { "当前弹幕：$it · ${result.comments.size} 条" }
                                                            ?: "已加载 ${result.comments.size} 条弹幕"
                                                        loadingKey = null
                                                        if (result.comments.isNotEmpty()) {
                                                            onLoaded(result.comments, message)
                                                        } else {
                                                            errorText = message
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun friendlyDanmakuError(error: Throwable, fallback: String): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("timeout", ignoreCase = true) || error::class.java.simpleName.contains("Timeout", ignoreCase = true) ->
            "弹幕 API 请求超时，请检查接口地址或稍后重试"
        message.isNotBlank() -> message
        else -> fallback
    }
}

@Composable
private fun DanmakuSourceChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0xFF4D607A).copy(alpha = 0.72f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.16f)),
        tonalElevation = 0.dp
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (selected) 0.96f else 0.72f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DanmakuSearchResultRow(
    candidate: DanmakuSearchCandidate,
    isLoading: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Surface(
            modifier = Modifier.size(width = 90.dp, height = 135.dp),
            shape = RoundedCornerShape(9.dp),
            color = Color.White.copy(alpha = 0.08f),
            tonalElevation = 0.dp
        ) {
            if (!candidate.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = candidate.imageUrl,
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("弹", color = accentColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 135.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.title,
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            candidate.episodeCount?.takeIf { it > 0 }?.let { count ->
                Text("共${count}集", color = Color.White.copy(alpha = 0.74f), fontSize = 14.sp)
            }
            Text(candidate.type.ifBlank { "电视剧" }, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
            Text(candidate.sourceLabel, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
            if (!candidate.selectedEpisodeTitle.isNullOrBlank()) {
                Text(
                    text = "将加载：${candidate.selectedEpisodeTitle}",
                    color = accentColor.copy(alpha = 0.86f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DanmakuDrawerHeader(
    enabled: Boolean,
    apiCount: Int,
    accentColor: Color,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "弹幕",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accentColor.copy(alpha = 0.18f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "弹",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = buildString {
                        append(if (enabled) "已开启" else "已关闭")
                        append(" · ")
                        append(apiCount)
                        append(" 个 API")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭弹幕设置",
                tint = Color.White,
                modifier = Modifier.size(27.dp)
            )
        }
    }
}

@Composable
private fun DanmakuApiManagementDialog(
    apiEndpoints: List<PlayerPreferences.DanmakuApiEndpoint>,
    accentColor: Color,
    onDismiss: () -> Unit,
    onApiEndpointsChange: (List<PlayerPreferences.DanmakuApiEndpoint>) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        HideSystemBarsForDanmakuDialogWindow()
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xEE1A1C25),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "弹幕 API URL",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "添加或编辑 API 名称和服务器地址，长按拖动调整优先级。",
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭 API 管理",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                DanmakuApiUrlListEditor(
                    apiEndpoints = apiEndpoints,
                    onApiEndpointsChange = onApiEndpointsChange,
                    accentColor = accentColor,
                    hintTextColor = Color.White.copy(alpha = 0.58f),
                    textColor = Color.White,
                    compact = true
                )
            }
        }
    }
}

@Composable
private fun DanmakuSliderSettingRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    accentColor: Color,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
        }
        Slider(
            modifier = Modifier.graphicsLayer(scaleY = 0.90f),
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = accentColor.copy(alpha = 0.92f),
                activeTrackColor = accentColor.copy(alpha = 0.68f),
                inactiveTrackColor = Color.White.copy(alpha = 0.20f),
                activeTickColor = Color.White.copy(alpha = 0.32f),
                inactiveTickColor = Color.White.copy(alpha = 0.16f)
            )
        )
    }
}

@Composable
private fun DanmakuSwitchSettingRow(
    title: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                checkedBorderColor = accentColor,
                uncheckedThumbColor = Color.White.copy(alpha = 0.80f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.22f)
            )
        )
    }
}


@Composable
private fun DanmakuClickableSettingRow(
    title: String,
    value: String,
    hint: String,
    accentColor: Color,
    actionLabel: String = "编辑",
    onClick: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    value,
                    color = if (value == "未设置") Color.White.copy(alpha = 0.46f) else Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.44f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(actionLabel, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DanmakuFilterWordsInputDialog(
    initialValue: String,
    accentColor: Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    Dialog(onDismissRequest = onDismiss) {
        HideSystemBarsForDanmakuDialogWindow()
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xEE1A1C25),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "屏蔽词设置",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "输入需要屏蔽的关键字，多个词用英文逗号分隔。保存后立即生效。",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("广告,剧透,关键词", color = Color.White.copy(alpha = 0.38f)) },
                    minLines = 3,
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        focusedIndicatorColor = accentColor,
                        unfocusedIndicatorColor = Color.White.copy(alpha = 0.16f),
                        cursorColor = accentColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White.copy(alpha = 0.58f))
                    }
                    TextButton(onClick = { onSave(value.trim()) }) {
                        Text("保存", color = accentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HideSystemBarsForDanmakuDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let { dialogWindow ->
            dialogWindow.setDimAmount(0f)
            val controller = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { }
    }
}

@Composable
private fun DanmakuMatchModeSettingRow(
    selected: String,
    accentColor: Color,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("弹幕匹配模式", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Box {
            TextButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selected, color = Color.White)
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Color.White.copy(alpha = 0.72f))
                }
            }
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                minWidth = 180.dp
            ) {
                PlayerPreferences.DANMAKU_MATCH_MODE_OPTIONS.forEach { option ->
                    GlassDropdownMenuItem(
                        text = option,
                        selected = option == selected,
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCircleTextButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 44.dp,
    outlined: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .background(
                color = if (outlined) Color.Transparent else Color.Black.copy(alpha = 0.30f),
                shape = RoundedCornerShape(999.dp)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (outlined) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.82f),
                        radius = size.minDimension / 2f - 1.2.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
                    )
                }
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
private fun DecoderModeInlineButton(
    label: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        modifier = Modifier.heightIn(min = 24.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DecoderModeChip(
    label: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        modifier = Modifier
            .heightIn(min = 28.dp)
            .background(Color.Black.copy(alpha = 0.40f), RoundedCornerShape(5.dp))
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TransportIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun PlayPauseTransportButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(58.dp)
    ) {
        if (isPlaying) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(9.dp)
                        .height(34.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                )
                Box(
                    modifier = Modifier
                        .width(9.dp)
                        .height(34.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                )
            }
        } else {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
private fun EpisodeNavigationButtons(
    canWatchPreviousEpisode: Boolean,
    canWatchNextEpisode: Boolean,
    onWatchPreviousEpisode: () -> Unit,
    onWatchNextEpisode: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 84.dp)
    ) {
        EpisodeNavigationButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "上一集",
            enabled = canWatchPreviousEpisode,
            onClick = onWatchPreviousEpisode
        )
        EpisodeNavigationButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "下一集",
            enabled = canWatchNextEpisode,
            onClick = onWatchNextEpisode
        )
    }
}

@Composable
private fun EpisodeNavigationButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(38.dp)
            .background(
                color = Color.Black.copy(alpha = if (enabled) 0.42f else 0.18f),
                shape = RoundedCornerShape(999.dp)
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.36f),
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun replayIcon(seconds: Int): ImageVector {
    return when (seconds) {
        5 -> Icons.Filled.Replay5
        10 -> Icons.Filled.Replay10
        30 -> Icons.Filled.Replay30
        else -> Icons.Filled.Replay
    }
}

private fun replayforwardIcon(seconds: Int): ImageVector {
    return when (seconds) {
        5 -> Icons.Filled.Forward5
        10 -> Icons.Filled.Forward10
        30 -> Icons.Filled.Forward30
        else -> Icons.Filled.Replay
    }
}

@Composable
private fun SeekBar(
    progress: Float,
    duration: Long,
    chapterMarkers: List<ChapterMarker>,
    onSeek: (Float) -> Unit,
    onScrubProgressChange: (Float?) -> Unit,
    bufferedProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    var scrubProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var dragActive by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val trackHeightFraction by animateFloatAsState(
        targetValue = if (dragActive) 0.95f else 0.55f,
        label = "seekTrackHeight"
    )
    val thumbRadiusFraction by animateFloatAsState(
        targetValue = if (dragActive) 0.52f else 0.36f,
        label = "seekThumbRadius"
    )
    val bubbleYOffsetPx = with(density) { (-42).dp.roundToPx() }

    LaunchedEffect(progress) {
        if (!dragActive) {
            scrubProgress = progress.coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = modifier
            .height(PROGRESS_BAR_HEIGHT_DP.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInteropFilter { event ->
                if (widthPx <= 0) return@pointerInteropFilter false

                val newProgress = (event.x / widthPx.toFloat()).coerceIn(0f, 1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragActive = true
                        scrubProgress = newProgress
                        onScrubProgressChange(scrubProgress)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        scrubProgress = newProgress
                        onScrubProgressChange(scrubProgress)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        scrubProgress = newProgress
                        dragActive = false
                        onSeek(scrubProgress)
                        onScrubProgressChange(null)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragActive = false
                        scrubProgress = progress.coerceIn(0f, 1f)
                        onScrubProgressChange(null)
                        true
                    }
                    else -> false
                }
            }
    ) {
        val renderedProgress = scrubProgress.coerceIn(0f, 1f)
        val renderedBufferedProgress = if (dragActive) {
            bufferedProgress.coerceIn(0f, 1f)
        } else {
            bufferedProgress.coerceAtLeast(renderedProgress).coerceIn(0f, 1f)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_BAR_HEIGHT_DP.dp)
                .align(Alignment.BottomCenter)
        ) {
            val yOffset = size.height / 2
            val trackInset = 2.dp.toPx()
            val trackStart = Offset(trackInset, yOffset)
            val trackEnd = Offset(size.width - trackInset, yOffset)
            val trackHeight = size.height * trackHeightFraction
            val markerSpacingPx = 6.dp.toPx()
            val markerStrokeWidth = 1.5.dp.toPx()
            val markerVerticalInset = 1.dp.toPx()
            var progressX: Float? = null

            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = trackStart,
                end = trackEnd,
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            if (renderedBufferedProgress > 0f) {
                val bufferedX = trackStart.x + (trackEnd.x - trackStart.x) * renderedBufferedProgress
                drawLine(
                    color = Color.White.copy(alpha = 0.58f),
                    start = trackStart,
                    end = Offset(bufferedX, yOffset),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Round
                )
            }

            if (renderedProgress > 0f) {
                progressX = trackStart.x + (trackEnd.x - trackStart.x) * renderedProgress
                drawLine(
                    color = Color.White.copy(alpha = 0.95f),
                    start = trackStart,
                    end = Offset(progressX, yOffset),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Round
                )
            }

            if (duration > 0L && chapterMarkers.isNotEmpty()) {
                var lastMarkerX = Float.NEGATIVE_INFINITY
                chapterMarkers.forEach { marker ->
                    val markerProgress = (marker.positionMs.toFloat() / duration.toFloat())
                        .coerceIn(0f, 1f)
                    if (markerProgress <= 0f || markerProgress >= 1f) return@forEach

                    val markerX = trackStart.x + (trackEnd.x - trackStart.x) * markerProgress
                    if (abs(markerX - lastMarkerX) < markerSpacingPx) return@forEach

                    drawLine(
                        color = Color.White.copy(alpha = 0.95f),
                        start = Offset(markerX, yOffset - trackHeight / 2f + markerVerticalInset),
                        end = Offset(markerX, yOffset + trackHeight / 2f - markerVerticalInset),
                        strokeWidth = markerStrokeWidth,
                        cap = StrokeCap.Round
                    )
                    lastMarkerX = markerX
                }
            }

            progressX?.let { thumbX ->
                drawCircle(
                    color = Color.White,
                    radius = size.height * thumbRadiusFraction,
                    center = Offset(thumbX, yOffset)
                )
            }
        }

        AnimatedVisibility(
            visible = dragActive && duration > 0L && widthPx > 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .wrapContentSize(unbounded = true)
                .zIndex(1f)
                .offset {
                    val thumbCenterX = (widthPx * renderedProgress).roundToInt()
                    IntOffset(thumbCenterX - 32.dp.roundToPx(), bubbleYOffsetPx)
                },
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.92f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 4.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
            ) {
                Text(
                    text = formatTime((duration * renderedProgress).toLong()),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

data class PlayerSystemStatus(
    val time: String,
    val networkSpeed: String,
    val batteryLabel: String
)

@Composable
fun rememberPlayerSystemStatus(): PlayerSystemStatus {
    var status by remember {
        mutableStateOf(
            PlayerSystemStatus(
                time = currentStatusTime(),
                networkSpeed = "0 KB/s",
                batteryLabel = "--%"
            )
        )
    }
    val context = LocalContext.current

    LaunchedEffect(context) {
        var lastBytes = safeTotalNetworkBytes()
        var lastAt = System.currentTimeMillis()
        while (true) {
            delay(1000L)
            val now = System.currentTimeMillis()
            val nextBytes = safeTotalNetworkBytes()
            val deltaMs = (now - lastAt).coerceAtLeast(1L)
            val speed = if (lastBytes >= 0L && nextBytes >= lastBytes) {
                formatNetworkSpeed(((nextBytes - lastBytes) * 1000L) / deltaMs)
            } else {
                "-- KB/s"
            }
            status = PlayerSystemStatus(
                time = currentStatusTime(),
                networkSpeed = speed,
                batteryLabel = readBatteryLabel(context)
            )
            lastBytes = nextBytes
            lastAt = now
        }
    }
    return status
}

private fun currentStatusTime(): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date())

private fun safeTotalNetworkBytes(): Long {
    val rx = TrafficStats.getTotalRxBytes()
    val tx = TrafficStats.getTotalTxBytes()
    return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) -1L else rx + tx
}

private fun formatNetworkSpeed(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB/s", value / 1024.0 / 1024.0)
        value >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", value / 1024.0)
        else -> String.format(Locale.US, "%.0f B/s", value)
    }
}

private fun readBatteryLabel(context: Context): String {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) {
        "${(level * 100 / scale.toFloat()).roundToInt()}%"
    } else {
        "--%"
    }
}

private fun rotatePlayerScreen(context: Context) {
    val activity = context.findActivity() ?: return
    activity.requestedOrientation = when (activity.requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun capturePlayerScreenshot(context: Context, view: View) {
    runCatching {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
            .apply { mkdirs() }
        val file = File(dir, "grmemby_${System.currentTimeMillis()}.jpg")
        val bitmap = view.drawToBitmap()
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        Toast.makeText(context, "截图已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
    }.onFailure { throwable ->
        Toast.makeText(context, "截图失败：${throwable.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private const val PlayerOverlayHorizontalContentFraction = 0.90f

@Preview(
    name = "Controls - Playing",
    showBackground = true,
    widthDp = 800,
    heightDp = 450,
    backgroundColor = 0xFF000000
)
@Composable
fun ControlsOverlayPreviewPlaying() {
    ControlsOverlay(
        title = "The Matrix Reloaded",
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 540000L, label = "Chapter 1"),
            ChapterMarker(positionMs = 1740000L, label = "Chapter 2"),
            ChapterMarker(positionMs = 3120000L, label = "Chapter 3")
        ),
        isPlaying = true,
        currentPosition = 1800000L,
        duration = 8280000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        spatializationResult = SpatializationResult(
            canSpatialize = true,
            reason = "Content and device support spatial audio",
            spatialFormat = "Dolby Atmos"
        ),
        isSpatialAudioEnabled = true,
        onShowMediaInfo = { },
        isLocked = false,
        onToggleLock = { },
        onShowAudioTrackSelection = { },
        onShowSubtitleTrackSelection = { },
        onCycleAspectRatio = { },
        onSeekBackward = { },
        onSeekForward = { }
    )
}

@Preview(
    name = "Controls - Paused",
    showBackground = true,
    widthDp = 800,
    heightDp = 450,
    backgroundColor = 0xFF000000
)
@Composable
fun ControlsOverlayPreviewPaused() {
    ControlsOverlay(
        title = "Inception",
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 600000L, label = "Dream 1"),
            ChapterMarker(positionMs = 2520000L, label = "Dream 2"),
            ChapterMarker(positionMs = 5100000L, label = "Dream 3")
        ),
        isPlaying = false,
        currentPosition = 3600000L,
        duration = 8880000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        spatializationResult = SpatializationResult(
            canSpatialize = true,
            reason = "Content and device support spatial audio",
            spatialFormat = "DTS:X"
        ),
        isSpatialAudioEnabled = false,
        onShowMediaInfo = { },
        isLocked = false,
        onToggleLock = { },
        onShowAudioTrackSelection = { },
        onShowSubtitleTrackSelection = { },
        onCycleAspectRatio = { },
        onSeekBackward = { },
        onSeekForward = { }
    )
}

@Preview(
    name = "Seekbar",
    showBackground = true,
    widthDp = 400,
    heightDp = 50,
    backgroundColor = 0xFF000000
)
@Composable
fun SeekBarPreview() {
    SeekBar(
        progress = 0.35f,
        duration = 7200000L,
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 900000L, label = "Intro"),
            ChapterMarker(positionMs = 2400000L, label = "Chapter 2"),
            ChapterMarker(positionMs = 4800000L, label = "Chapter 3"),
            ChapterMarker(positionMs = 6300000L, label = "Credits")
        ),
        onSeek = { },
        onScrubProgressChange = { },
        modifier = Modifier.padding(16.dp)
    )
}
