package com.grmemby.app.ui.components.watchparty

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.formatWatchPartyBannerText
import com.grmemby.app.watchparty.shouldShowWatchPartyTopBanner
import kotlinx.coroutines.delay

@Composable
fun WatchPartyTopBanner(modifier: Modifier = Modifier) {
    val activeSession by WatchPartySessionStore.activeSession.collectAsState()
    val repository = remember { WatchPartyRepository() }
    var memberCount by remember(activeSession?.roomId) { mutableStateOf(0) }

    LaunchedEffect(activeSession?.roomId, activeSession?.memberId, activeSession?.isHost) {
        val session = activeSession ?: run {
            memberCount = 0
            return@LaunchedEffect
        }
        if (!shouldShowWatchPartyTopBanner(session)) {
            memberCount = 0
            return@LaunchedEffect
        }
        if (memberCount != 1) memberCount = 1
        while (true) {
            runCatching { repository.getRoom(session.roomId) }
                .onSuccess { room ->
                    val nextCount = room.members.size.coerceAtLeast(1)
                    if (memberCount != nextCount) memberCount = nextCount
                }
            delay(5_000L)
        }
    }

    val label = if (shouldShowWatchPartyTopBanner(activeSession)) {
        formatWatchPartyBannerText(activeSession?.roomId, memberCount)
    } else {
        null
    }
    AnimatedVisibility(
        visible = label != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            // Align with the home top action row: left "一起看", center room status,
            // and right action buttons share the same visual center line.
            .padding(top = 10.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = label.orEmpty(),
                color = Color.White,
                fontSize = 13.sp,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}
