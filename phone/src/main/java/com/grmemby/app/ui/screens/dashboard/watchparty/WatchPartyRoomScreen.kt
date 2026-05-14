package com.grmemby.app.ui.screens.dashboard.watchparty

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.watchparty.RoomDto
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.PlaybackEvent
import com.grmemby.app.watchparty.sanitizeWatchPartyErrorMessage
import com.grmemby.app.watchparty.shouldNavigateGuestToPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WatchPartyRoomScreen(
    onNavigateToPlayer: (String) -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WatchPartyRepository() }
    var session by remember { mutableStateOf(WatchPartySessionStore.get()) }
    var room by remember { mutableStateOf<RoomDto?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLeaving by remember { mutableStateOf(false) }
    var hasNavigatedToPlayer by remember { mutableStateOf(false) }

    LaunchedEffect(session?.isHost) {
        if (session?.isHost == true) {
            onBackToHome()
        }
    }

    LaunchedEffect(session?.roomId) {
        val activeSession = session ?: return@LaunchedEffect
        while (true) {
            runCatching { repository.getRoom(activeSession.roomId) }
                .onSuccess { latestRoom ->
                    room = latestRoom
                    errorMessage = null
                    val mediaId = latestRoom.media?.itemId
                    if (!latestRoom.playback.isPlaying && latestRoom.playback.event != PlaybackEvent.PREPARE) {
                        hasNavigatedToPlayer = false
                    }
                    if (!mediaId.isNullOrBlank() && latestRoom.shouldNavigateGuestToPlayer(hasNavigatedToPlayer, activeSession.isHost)) {
                        hasNavigatedToPlayer = true
                        onNavigateToPlayer(mediaId)
                    }
                }
                .onFailure { error ->
                    errorMessage = sanitizeWatchPartyErrorMessage(error.message)
                    WatchPartySessionStore.clear(activeSession.roomId)
                    session = null
                    return@LaunchedEffect
                }
            delay(1_000L)
        }
    }

    val activeSession = session
    val activeRoom = room
    val roomId = activeSession?.roomId ?: activeRoom?.id
    val mediaTitle = activeRoom?.media?.title?.takeIf { it.isNotBlank() }
    val selectedMediaId = activeRoom?.media?.itemId
    val isHost = activeSession?.isHost == true
    val isPlaying = activeRoom?.playback?.isPlaying == true
    val memberCount = activeRoom?.members?.size ?: 0
    val positionLabel = formatRoomPosition(activeRoom?.playback?.positionMs ?: 0L)
    val statusText = when {
        activeSession == null -> "你尚未加入房间"
        selectedMediaId.isNullOrBlank() -> "等待房主选片"
        activeRoom.playback.event == PlaybackEvent.PREPARE -> "房主已点击播放，正在同步进入播放器…"
        isPlaying -> "房主已开始播放，正在进入播放器…"
        else -> "${mediaTitle ?: "已选影片"} · 等待播放"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = roomId?.let { "一起看房间：$it" } ?: "一起看房间",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = mediaTitle ?: "等待房主选择影片",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "房间成员：$memberCount  ·  当前进度：$positionLabel",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        RoomPlayerPlaceholder(
            title = mediaTitle,
            status = statusText,
            isPlaying = isPlaying
        )

        LinearProgressIndicator(
            progress = { if (isPlaying) 0.68f else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = Color(0xFFFF8A3D),
            trackColor = Color.White.copy(alpha = 0.16f)
        )

        Text(
            text = statusText,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (activeSession == null) {
            Button(
                onClick = onBackToHome,
                modifier = Modifier.fillMaxWidth()
            ) { Text("返回主页") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val copiedRoomId = activeSession.roomId
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Grmemby 一起看房间", copiedRoomId)
                        )
                        Toast.makeText(context, "已复制房间号：$copiedRoomId", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLeaving
                ) { Text("复制房间号") }

                OutlinedButton(
                    onClick = {
                        val leavingSession = activeSession
                        isLeaving = true
                        scope.launch {
                            runCatching {
                                if (leavingSession.isHost) {
                                    repository.disbandRoom(leavingSession.roomId, leavingSession.memberId)
                                } else {
                                    repository.leaveRoom(leavingSession.roomId, leavingSession.memberId)
                                }
                            }
                            WatchPartySessionStore.clear(leavingSession.roomId)
                            session = null
                            isLeaving = false
                            onBackToHome()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLeaving
                ) { Text(if (isLeaving) "退出中…" else "退出房间") }
            }
        }
    }
}

@Composable
private fun RoomPlayerPlaceholder(
    title: String?,
    status: String,
    isPlaying: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101010))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF3A1D12),
                            Color(0xFF111111),
                            Color.Black
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        Text("▶", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    } else {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    }
                }
                Text(
                    text = title ?: "等待播放",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = status,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatRoomPosition(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
