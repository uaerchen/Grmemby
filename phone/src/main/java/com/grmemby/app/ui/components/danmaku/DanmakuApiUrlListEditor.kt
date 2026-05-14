package com.grmemby.app.ui.components.danmaku

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.grmemby.player.preferences.PlayerPreferences

@Composable
fun DanmakuApiUrlListEditor(
    apiEndpoints: List<PlayerPreferences.DanmakuApiEndpoint>,
    onApiEndpointsChange: (List<PlayerPreferences.DanmakuApiEndpoint>) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    hintTextColor: Color = Color.White.copy(alpha = 0.56f),
    textColor: Color = Color.White,
    compact: Boolean = false
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val normalized = remember(apiEndpoints) {
        apiEndpoints
            .mapIndexedNotNull { index, endpoint ->
                val url = normalizeDanmakuUrl(endpoint.url)
                if (url.isBlank()) {
                    null
                } else {
                    PlayerPreferences.DanmakuApiEndpoint(
                        name = endpoint.name.trim().ifBlank { "API ${index + 1}" },
                        url = url
                    )
                }
            }
            .distinctBy { it.url.lowercase() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "弹幕 API URL",
                    color = textColor,
                    fontSize = if (compact) 14.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "点击添加或编辑名称与服务器地址；从上到下优先匹配，长按拖动排序。默认不内置 API。",
                    color = hintTextColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            OutlinedButton(
                onClick = { showAddDialog = true },
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("添加", fontSize = 13.sp)
            }
        }

        if (normalized.isEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "未添加 API，弹幕不会自动加载",
                    color = hintTextColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = if (compact) 280.dp else 360.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
            ) {
                itemsIndexed(
                    items = normalized,
                    key = { _, endpoint -> endpoint.url.lowercase() }
                ) { index, endpoint ->
                    DraggableApiEndpointRow(
                        index = index,
                        endpoint = endpoint,
                        apiEndpoints = normalized,
                        compact = compact,
                        accentColor = accentColor,
                        textColor = textColor,
                        hintTextColor = hintTextColor,
                        modifier = Modifier.animateItem(),
                        onEdit = {
                            editingIndex = normalized.indexOfFirst { it.url.equals(endpoint.url, ignoreCase = true) }
                                .takeIf { it >= 0 }
                                ?: index
                        },
                        onApiEndpointsChange = onApiEndpointsChange
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        DanmakuApiEndpointInputDialog(
            title = "添加弹幕 API URL",
            initialEndpoint = null,
            accentColor = accentColor,
            textColor = textColor,
            hintTextColor = hintTextColor,
            onDismiss = { showAddDialog = false },
            onSave = { endpoint ->
                val normalizedUrl = normalizeDanmakuUrl(endpoint.url)
                if (normalizedUrl.isNotBlank()) {
                    val next = normalized + endpoint.copy(url = normalizedUrl)
                    onApiEndpointsChange(next.distinctBy { it.url.lowercase() })
                }
                showAddDialog = false
            }
        )
    }

    editingIndex?.let { index ->
        normalized.getOrNull(index)?.let { current ->
            DanmakuApiEndpointInputDialog(
                title = "编辑弹幕 API URL",
                initialEndpoint = current,
                accentColor = accentColor,
                textColor = textColor,
                hintTextColor = hintTextColor,
                onDismiss = { editingIndex = null },
                onSave = { endpoint ->
                    val normalizedUrl = normalizeDanmakuUrl(endpoint.url)
                    if (normalizedUrl.isNotBlank()) {
                        val next = normalized.toMutableList().apply {
                            set(index, endpoint.copy(url = normalizedUrl))
                        }
                        onApiEndpointsChange(next.distinctBy { it.url.lowercase() })
                    }
                    editingIndex = null
                }
            )
        }
    }
}

@Composable
private fun DraggableApiEndpointRow(
    index: Int,
    endpoint: PlayerPreferences.DanmakuApiEndpoint,
    apiEndpoints: List<PlayerPreferences.DanmakuApiEndpoint>,
    compact: Boolean,
    accentColor: Color,
    textColor: Color,
    hintTextColor: Color,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onApiEndpointsChange: (List<PlayerPreferences.DanmakuApiEndpoint>) -> Unit
) {
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragVisualOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val latestEndpoints by rememberUpdatedState(apiEndpoints)
    val endpointKey = endpoint.url.lowercase()
    val currentIndex = apiEndpoints.indexOfFirst { it.url.equals(endpoint.url, ignoreCase = true) }.takeIf { it >= 0 } ?: index
    val isPrimary = currentIndex == 0
    val rowCorner = if (compact) 13.dp else 14.dp
    val verticalPadding = if (compact) 8.dp else 9.dp
    val horizontalPadding = if (compact) 9.dp else 10.dp
    val handleSize = if (compact) 32.dp else 34.dp
    val animatedDragOffset by animateFloatAsState(
        targetValue = if (isDragging) dragVisualOffset.coerceIn(-92f, 92f) else 0f,
        animationSpec = tween(durationMillis = if (isDragging) 90 else 220),
        label = "danmaku_api_drag_offset"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.018f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "danmaku_api_drag_scale"
    )
    val dragModifier = Modifier.pointerInput(endpointKey) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                isDragging = true
                dragAccumulated = 0f
                dragVisualOffset = 0f
            },
            onDragCancel = {
                isDragging = false
                dragAccumulated = 0f
                dragVisualOffset = 0f
            },
            onDragEnd = {
                isDragging = false
                dragAccumulated = 0f
                dragVisualOffset = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                dragAccumulated += dragAmount.y
                dragVisualOffset += dragAmount.y
                val threshold = if (compact) 62f else 70f
                val endpoints = latestEndpoints
                val activeIndex = endpoints.indexOfFirst { it.url.equals(endpoint.url, ignoreCase = true) }
                if (activeIndex < 0) {
                    dragAccumulated = 0f
                    dragVisualOffset = 0f
                } else {
                    when {
                        dragAccumulated <= -threshold && activeIndex > 0 -> {
                            onApiEndpointsChange(endpoints.toMutableList().apply {
                                val item = removeAt(activeIndex)
                                add(activeIndex - 1, item)
                            })
                            dragAccumulated += threshold
                            dragVisualOffset += threshold
                        }
                        dragAccumulated >= threshold && activeIndex < endpoints.lastIndex -> {
                            onApiEndpointsChange(endpoints.toMutableList().apply {
                                val item = removeAt(activeIndex)
                                add(activeIndex + 1, item)
                            })
                            dragAccumulated -= threshold
                            dragVisualOffset -= threshold
                        }
                    }
                }
            }
        )
    }

    Surface(
        color = if (isPrimary) accentColor.copy(alpha = if (isDragging) 0.16f else 0.10f) else Color.Black.copy(alpha = if (isDragging) 0.22f else 0.14f),
        shape = RoundedCornerShape(rowCorner),
        border = BorderStroke(
            1.dp,
            if (isPrimary) accentColor.copy(alpha = if (isDragging) 0.50f else 0.30f) else Color.White.copy(alpha = if (isDragging) 0.16f else 0.08f)
        ),
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = animatedDragOffset
                scaleX = animatedScale
                scaleY = animatedScale
                shadowElevation = if (isDragging) 14f else 0f
            }
            .clip(RoundedCornerShape(rowCorner))
            .then(dragModifier)
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "长按拖动排序",
                tint = hintTextColor,
                modifier = Modifier
                    .size(handleSize)
                    .padding(6.dp)
            )
            Icon(Icons.Rounded.Link, contentDescription = null, tint = if (isPrimary) accentColor else hintTextColor, modifier = Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = endpoint.name.ifBlank { if (isPrimary) "优先 API" else "备用 API ${currentIndex + 1}" },
                    color = if (isPrimary) accentColor else textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isPrimary) "权重最高 · 长按拖动排序 · 点击编辑" else "备用 ${currentIndex + 1} · 长按拖动排序 · 点击编辑",
                    color = hintTextColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = endpoint.url,
                    color = textColor.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onApiEndpointsChange(apiEndpoints.filterIndexed { i, _ -> i != currentIndex }) }) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = Color.White.copy(alpha = 0.78f))
            }
        }
    }
}

@Composable
private fun DanmakuApiEndpointInputDialog(
    title: String,
    initialEndpoint: PlayerPreferences.DanmakuApiEndpoint?,
    accentColor: Color,
    textColor: Color,
    hintTextColor: Color,
    onDismiss: () -> Unit,
    onSave: (PlayerPreferences.DanmakuApiEndpoint) -> Unit
) {
    var name by remember(initialEndpoint) { mutableStateOf(initialEndpoint?.name.orEmpty()) }
    var url by remember(initialEndpoint) { mutableStateOf(initialEndpoint?.url.orEmpty()) }
    val normalizedUrl = normalizeDanmakuUrl(url)

    Dialog(onDismissRequest = onDismiss) {
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
                    text = title,
                    color = textColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "名称仅用于显示；服务器地址支持域名、路径或完整 http/https 地址，不限制为 IP 和端口。",
                    color = hintTextColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    placeholder = { Text("例如：主弹幕库", color = hintTextColor.copy(alpha = 0.72f)) },
                    colors = danmakuTextFieldColors(accentColor, textColor)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    singleLine = true,
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com/api", color = hintTextColor.copy(alpha = 0.72f)) },
                    colors = danmakuTextFieldColors(accentColor, textColor)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = hintTextColor)
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                PlayerPreferences.DanmakuApiEndpoint(
                                    name = name.trim().ifBlank { "API" },
                                    url = normalizedUrl
                                )
                            )
                        },
                        enabled = normalizedUrl.isNotBlank()
                    ) {
                        Text("确定", color = if (normalizedUrl.isNotBlank()) accentColor else hintTextColor.copy(alpha = 0.38f), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun danmakuTextFieldColors(accentColor: Color, textColor: Color) = TextFieldDefaults.colors(
    focusedTextColor = textColor,
    unfocusedTextColor = textColor,
    focusedContainerColor = Color.White.copy(alpha = 0.08f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
    focusedIndicatorColor = accentColor,
    unfocusedIndicatorColor = Color.White.copy(alpha = 0.16f),
    focusedLabelColor = accentColor,
    unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
    cursorColor = accentColor
)

private fun normalizeDanmakuUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    return when {
        trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "http://$trimmed"
    }
}
