package com.grmemby.app.ui.screens.dashboard.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.R
import androidx.compose.ui.res.stringResource
import com.grmemby.app.ui.components.glass.GlassDropdownMenu
import com.grmemby.app.ui.components.glass.GlassDropdownMenuItem
import com.grmemby.app.ui.components.glass.GlassTokens
import com.grmemby.app.ui.components.glass.blendGlassColor
import com.grmemby.app.ui.components.glass.dynamicGlassBorder
import com.grmemby.app.ui.components.glass.dynamicGlassBrush
import com.grmemby.app.ui.screens.dashboard.DashboardPalette
import com.grmemby.data.repository.AuthRepository

@Composable
internal fun GlassServerSwitchChip(
    serverName: String?,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    surfaceColor: Color,
    onClick: () -> Unit
) {
    val displayName = serverName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.dashboard_server_fallback)
    val shape = RoundedCornerShape(100.dp)
    val chipTextColor = Color(0xFFF2F2F7)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "server_switch_arrow"
    )

    Row(
        modifier = modifier
            .height(38.dp)
            .widthIn(min = 104.dp, max = 178.dp)
            .capyTopActionSurface(shape)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .clip(CircleShape)
                .background(Color(0x33EBEBF5))
                .border(0.6.dp, Color(0x99EBEBF5), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.grmemby_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(23.dp),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = displayName,
            color = chipTextColor,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = Color(0x99EBEBF5),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer(rotationZ = arrowRotation)
        )
    }
}

@Composable
internal fun GlassServerSwitchDropdown(
    serverName: String?,
    savedServers: List<AuthRepository.SavedServer>,
    activeServerId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onServerSelected: (AuthRepository.SavedServer) -> Unit,
    onEmptyClick: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val surfaceColor by DashboardPalette.surfaceColor.collectAsState()
    val serverOptions = remember(savedServers, activeServerId) {
        savedServers
            .groupBy { server ->
                server.serverUrl.trim().trimEnd('/').lowercase() to server.serverName.trim().lowercase()
            }
            .values
            .mapNotNull { usersForServer ->
                usersForServer.firstOrNull { it.id == activeServerId }
                    ?: usersForServer.maxByOrNull { it.lastUsedAt }
                    ?: usersForServer.firstOrNull()
            }
            .sortedWith(
                compareByDescending<AuthRepository.SavedServer> { it.id == activeServerId }
                    .thenByDescending { it.lastUsedAt }
            )
    }

    Box(modifier = modifier) {
        GlassServerSwitchChip(
            serverName = serverName,
            expanded = expanded,
            surfaceColor = surfaceColor,
            onClick = {
                if (!enabled) return@GlassServerSwitchChip
                if (serverOptions.isEmpty()) {
                    onEmptyClick()
                } else {
                    expanded = true
                }
            }
        )
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 176.dp,
            dark = true,
            surfaceColor = surfaceColor
        ) {
            serverOptions.forEach { server ->
                val selected = server.id == activeServerId ||
                    (activeServerId == null && server.serverName == serverName)
                GlassDropdownMenuItem(
                    text = server.serverName.ifBlank { stringResource(R.string.dashboard_server_fallback) },
                    selected = selected,
                    enabled = enabled,
                    leadingIcon = Icons.Rounded.Storage,
                    dark = true,
                    surfaceColor = surfaceColor,
                    onClick = {
                        expanded = false
                        onServerSelected(server)
                    }
                )
            }
        }
    }
}
