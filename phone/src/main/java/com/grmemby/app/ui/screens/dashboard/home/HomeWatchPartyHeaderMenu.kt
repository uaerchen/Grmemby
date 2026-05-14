package com.grmemby.app.ui.screens.dashboard.home

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.watchparty.ActiveWatchPartySession
import com.grmemby.app.watchparty.WatchPartyDeviceIdentity
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.copyableWatchPartyInviteText
import com.grmemby.app.watchparty.sameServerJoinFailureMessage
import com.grmemby.app.watchparty.sanitizeWatchPartyErrorMessage
import kotlinx.coroutines.launch

@Composable
internal fun HomeWatchPartyHeaderMenu(
    displayUsername: String?,
    serverUrl: String?,
    serverName: String?,
    savedServerUrls: List<String>,
    onWatchPartyRoomReady: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watchPartyRepository = remember { WatchPartyRepository() }
    val activeWatchPartySession by WatchPartySessionStore.activeSession.collectAsState()
    var isCreatingWatchPartyRoom by remember { mutableStateOf(false) }
    var isJoiningWatchPartyRoom by remember { mutableStateOf(false) }
    var isLeavingWatchPartyRoom by remember { mutableStateOf(false) }
    var showJoinRoomDialog by remember { mutableStateOf(false) }
    var joinRoomId by remember { mutableStateOf("") }
    var lastJoinRoomClickAt by rememberSaveable { mutableStateOf(0L) }

    WatchPartyActionMenu(
        activeSession = activeWatchPartySession,
        isLoading = isCreatingWatchPartyRoom || isJoiningWatchPartyRoom || isLeavingWatchPartyRoom,
        onCreateRoom = {
            if (isCreatingWatchPartyRoom) return@WatchPartyActionMenu
            val existingSession = WatchPartySessionStore.get()
            if (existingSession != null) {
                val inviteText = copyableWatchPartyInviteText(
                    roomId = existingSession.roomId,
                    inviteText = existingSession.inviteText
                )
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Grmemby 一起看", inviteText)
                )
                Toast.makeText(
                    context,
                    "已有房间：${existingSession.roomId}，已复制房间号",
                    Toast.LENGTH_LONG
                ).show()
                return@WatchPartyActionMenu
            }
            val hostName = displayUsername?.takeIf { it.isNotBlank() } ?: "房主"
            isCreatingWatchPartyRoom = true
            scope.launch {
                runCatching {
                    watchPartyRepository.createRoom(
                        name = "Grmemby 一起看",
                        hostName = hostName,
                        serverUrl = serverUrl,
                        serverName = serverName,
                        memberId = WatchPartyDeviceIdentity.memberId(context)
                    )
                }.onSuccess { created ->
                    val inviteText = copyableWatchPartyInviteText(created.room.id)
                    val session = ActiveWatchPartySession(
                        roomId = created.room.id,
                        memberId = created.memberId,
                        isHost = true,
                        roomName = created.room.name,
                        inviteText = inviteText
                    )
                    WatchPartySessionStore.set(session)
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("Grmemby 一起看", inviteText)
                    )
                    Toast.makeText(
                        context,
                        "房间已创建并复制：${created.room.id}，请选择影片开始同播",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        sanitizeWatchPartyErrorMessage(error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
                isCreatingWatchPartyRoom = false
            }
        },
        onJoinRoom = { showJoinRoomDialog = true },
        onLeaveRoom = {
            val leavingSession = WatchPartySessionStore.get() ?: return@WatchPartyActionMenu
            isLeavingWatchPartyRoom = true
            scope.launch {
                runCatching {
                    if (leavingSession.isHost) {
                        watchPartyRepository.disbandRoom(leavingSession.roomId, leavingSession.memberId)
                    } else {
                        watchPartyRepository.leaveRoom(leavingSession.roomId, leavingSession.memberId)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        sanitizeWatchPartyErrorMessage(error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
                WatchPartySessionStore.clear(leavingSession.roomId)
                isLeavingWatchPartyRoom = false
                Toast.makeText(context, "已退出房间", Toast.LENGTH_SHORT).show()
            }
        }
    )

    if (showJoinRoomDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isJoiningWatchPartyRoom) showJoinRoomDialog = false
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 0.dp,
            title = { Text("加入房间", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "输入或粘贴房间 ID，加入后进入等待页；房主开始播放后会自动跟随。",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = joinRoomId,
                        onValueChange = { joinRoomId = it.trim() },
                        singleLine = true,
                        label = { Text("房间 ID") },
                        enabled = !isJoiningWatchPartyRoom,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF111827),
                            unfocusedTextColor = Color(0xFF111827),
                            disabledTextColor = Color(0xFF94A3B8),
                            focusedLabelColor = Color(0xFF258BFF),
                            unfocusedLabelColor = Color(0xFF64748B),
                            disabledLabelColor = Color(0xFF94A3B8),
                            focusedBorderColor = Color(0xFF258BFF),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            disabledBorderColor = Color(0xFFE2E8F0),
                            cursorColor = Color(0xFF258BFF)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = joinRoomId.isNotBlank() && !isJoiningWatchPartyRoom,
                    onClick = {
                        val cleanRoomId = joinRoomId.trim()
                        if (isJoiningWatchPartyRoom) return@TextButton
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastJoinRoomClickAt < 1_500L) return@TextButton
                        lastJoinRoomClickAt = now
                        val memberName = displayUsername?.takeIf { it.isNotBlank() } ?: "用户"
                        isJoiningWatchPartyRoom = true
                        scope.launch {
                            runCatching {
                                val roomPreview = watchPartyRepository.getRoom(cleanRoomId)
                                roomPreview.sameServerJoinFailureMessage(
                                    activeServerUrl = serverUrl,
                                    savedServerUrls = savedServerUrls
                                )?.let { message ->
                                    throw IllegalStateException(message)
                                }
                                watchPartyRepository.joinRoom(
                                    roomId = cleanRoomId,
                                    name = memberName,
                                    serverUrl = serverUrl,
                                    serverName = serverName,
                                    memberId = WatchPartyDeviceIdentity.memberId(context)
                                )
                            }.onSuccess { joined ->
                                val inviteText = copyableWatchPartyInviteText(joined.room.id)
                                val session = ActiveWatchPartySession(
                                    roomId = joined.room.id,
                                    memberId = joined.memberId,
                                    isHost = joined.room.hostMemberId == joined.memberId,
                                    roomName = joined.room.name,
                                    inviteText = inviteText
                                )
                                WatchPartySessionStore.set(session)
                                showJoinRoomDialog = false
                                joinRoomId = ""
                                Toast.makeText(context, "已加入房间", Toast.LENGTH_LONG).show()
                                onWatchPartyRoomReady()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    sanitizeWatchPartyErrorMessage(error.message),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isJoiningWatchPartyRoom = false
                        }
                    }
                ) {
                    Text(
                        if (isJoiningWatchPartyRoom) "加入中..." else "加入",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isJoiningWatchPartyRoom,
                    onClick = { showJoinRoomDialog = false }
                ) {
                    Text("取消", color = Color(0xFF64748B))
                }
            }
        )
    }
}
