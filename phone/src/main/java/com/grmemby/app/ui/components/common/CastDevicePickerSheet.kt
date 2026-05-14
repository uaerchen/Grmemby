package com.grmemby.app.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.grmemby.app.R
import com.grmemby.app.cast.CastController
import com.grmemby.app.cast.CastRouteEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDevicePicker(
    isVisible: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isVisible) return

    val context = LocalContext.current
    val castState by CastController.playbackState.collectAsState()
    val routes by CastController.availableRoutes.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(context) {
        CastController.startRouteDiscovery(context)
    }

    DisposableEffect(context) {
        onDispose {
            CastController.stopRouteDiscovery()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.cast_to_device),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cast_close_picker),
                        tint = Color(0xFF475569)
                    )
                }
            }

            if (castState.isConnected) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.54f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = castState.deviceName?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.cast_connected_device),
                                color = Color(0xFF111827),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.cast_connected),
                                color = Color(0xFF16A34A),
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                CastController.disconnect(context)
                                onDismissRequest()
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1).copy(alpha = 0.86f))
                        ) {
                            Text(stringResource(R.string.cast_disconnect))
                        }
                    }
                }
            }

            if (routes.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.48f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.70f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cast_searching),
                            color = Color(0xFF111827),
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(R.string.cast_same_wifi_hint),
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(routes, key = { it.id }) { route ->
                        CastRouteListItem(
                            route = route,
                            onClick = {
                                val connectResult = CastController.connectToRoute(context, route.id)
                                if (connectResult.isSuccess) {
                                    onDismissRequest()
                                }
                            }
                        )
                    }
                }
            }

            castState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    color = Color(0xFFDC2626),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CastRouteListItem(
    route: CastRouteEntry,
    onClick: () -> Unit
) {
    val containerColor = when {
        route.isSelected -> Color(0xFFBFE8FF).copy(alpha = 0.42f)
        route.isEnabled -> Color.White.copy(alpha = 0.48f)
        else -> Color(0xFFE2E8F0).copy(alpha = 0.58f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = route.isEnabled && !route.isSelected, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (route.isSelected) 0.82f else 0.64f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color.White.copy(alpha = 0.58f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cast,
                    contentDescription = null,
                    tint = if (route.isSelected) Color(0xFF258BFF) else Color(0xFF475569),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = route.name.ifBlank { stringResource(R.string.cast_unnamed_device) },
                    color = if (route.isEnabled) Color(0xFF111827) else Color(0xFF94A3B8),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitle = when {
                    route.isSelected -> stringResource(R.string.cast_connected)
                    route.isConnecting -> stringResource(R.string.cast_connecting)
                    !route.isEnabled -> stringResource(R.string.settings_unavailable)
                    else -> route.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cast_tap_to_connect)
                }

                Text(
                    text = subtitle,
                    color = if (route.isSelected) Color(0xFF258BFF) else Color(0xFF64748B),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (route.isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF258BFF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
