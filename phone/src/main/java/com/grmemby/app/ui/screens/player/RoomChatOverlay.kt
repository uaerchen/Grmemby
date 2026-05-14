package com.grmemby.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.watchparty.RoomChatMessageDto

@Composable
internal fun BoxScope.WatchPartyRoomChatOverlay(
    isInRoom: Boolean,
    messages: List<RoomChatMessageDto>,
    onSendMessage: (String) -> Unit,
    panelVisible: Boolean,
    onPanelVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isInRoom) return

    var minimized by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .align(Alignment.CenterStart)
            .padding(start = 14.dp, bottom = 72.dp)
    ) {
        if (minimized) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.30f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.12f),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { minimized = false }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "›",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light
                )
            }
        } else {
            RoomChatPeekCard(
                messages = messages.takeLast(4),
                onOpen = { onPanelVisibleChange(true) },
                onMinimize = { minimized = true }
            )
        }
    }

    if (panelVisible) {
        RoomChatSidePanel(
            messages = messages,
            onDismiss = { onPanelVisibleChange(false) },
            onSendMessage = onSendMessage,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun RoomChatPeekCard(
    messages: List<RoomChatMessageDto>,
    onOpen: () -> Unit,
    onMinimize: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(188.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
        color = Color.Black.copy(alpha = 0.30f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, top = 10.dp, end = 28.dp, bottom = 10.dp)
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "房间聊天",
                            color = Color.White.copy(alpha = 0.76f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "点击发送消息",
                            color = Color.White.copy(alpha = 0.40f),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        messages.forEachIndexed { index, message ->
                            val alpha = (0.28f + index * 0.20f).coerceIn(0.28f, 0.94f)
                            Text(
                                text = message.compactText(),
                                color = Color.White.copy(alpha = alpha),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
            ) {
                Text("‹", color = Color.White.copy(alpha = 0.68f), fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun RoomChatSidePanel(
    messages: List<RoomChatMessageDto>,
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.CenterEnd
    ) {
        val drawerWidth: Dp = if (maxWidth > maxHeight) maxWidth * 0.48f else maxWidth * 0.88f

        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 300.dp, max = 440.dp)
                .width(drawerWidth)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            color = Color(0x801A1C25),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 22.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "房间聊天",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = Color.White, fontSize = 22.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                text = "暂无聊天记录",
                                color = Color.White.copy(alpha = 0.46f),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        items(messages, key = { it.id }) { message ->
                            Text(
                                text = message.fullText(),
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val sendCurrentMessage = {
                        val message = input.trim()
                        if (message.isNotBlank()) {
                            onSendMessage(message)
                            input = ""
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (input.isBlank()) {
                            Text(
                                text = "说点什么…",
                                color = Color.White.copy(alpha = 0.42f),
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.replace('\n', ' ').replace('\r', ' ').take(240) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White.copy(alpha = 0.95f),
                                fontSize = 14.sp,
                                lineHeight = 18.sp
                            ),
                            cursorBrush = SolidColor(Color.White.copy(alpha = 0.86f)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendCurrentMessage() })
                        )
                    }
                    Button(
                        enabled = input.trim().isNotBlank(),
                        onClick = sendCurrentMessage,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7DB7FF).copy(alpha = 0.42f),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.11f),
                            disabledContentColor = Color.White.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("发送")
                    }
                }
            }
        }
    }
}

private fun RoomChatMessageDto.compactText(): String {
    val text = "${senderName.ifBlank { "成员" }}:${content.trim()}"
    return if (text.length <= 18) text else text.take(17) + "..."
}

private fun RoomChatMessageDto.fullText(): String =
    "${senderName.ifBlank { "成员" }}:${content.trim()}"
