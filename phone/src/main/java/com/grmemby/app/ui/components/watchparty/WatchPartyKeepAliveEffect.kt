package com.grmemby.app.ui.components.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.grmemby.app.watchparty.WatchPartyKeepAliveService
import com.grmemby.app.watchparty.WatchPartySessionStore

@Composable
fun WatchPartyKeepAliveEffect() {
    val context = LocalContext.current.applicationContext
    val activeSession by WatchPartySessionStore.activeSession.collectAsState()
    val session = activeSession

    LaunchedEffect(Unit) {
        if (WatchPartySessionStore.get() == null) {
            WatchPartyKeepAliveService.restoreSession(context)?.let { restored ->
                WatchPartySessionStore.set(restored)
            }
        }
    }

    DisposableEffect(session?.roomId, session?.memberId, session?.isHost) {
        if (session?.isHost == true) {
            runCatching { WatchPartyKeepAliveService.start(context, session) }
        }
        onDispose {
            if (session?.isHost == true) {
                runCatching { WatchPartyKeepAliveService.stop(context) }
            }
        }
    }
}
