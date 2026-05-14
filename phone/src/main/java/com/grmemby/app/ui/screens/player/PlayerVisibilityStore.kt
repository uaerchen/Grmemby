package com.grmemby.app.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Tracks whether any PlayerScreen is currently composed, including embedded players launched
 * from detail/download screens whose navigation route is not the top-level player route. */
internal object PlayerVisibilityStore {
    var activePlayerCount by mutableIntStateOf(0)
        private set

    val isAnyPlayerVisible: Boolean
        get() = activePlayerCount > 0

    fun markVisible() {
        activePlayerCount += 1
    }

    fun markHidden() {
        activePlayerCount = (activePlayerCount - 1).coerceAtLeast(0)
    }
}
