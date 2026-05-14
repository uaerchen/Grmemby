package com.grmemby.app.ui.screens.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PlayerPictureInPictureActions {
    const val ACTION_SEEK_BACKWARD = "com.grmemby.app.player.PIP_SEEK_BACKWARD"
    const val ACTION_TOGGLE_PLAY_PAUSE = "com.grmemby.app.player.PIP_TOGGLE_PLAY_PAUSE"
    const val ACTION_SEEK_FORWARD = "com.grmemby.app.player.PIP_SEEK_FORWARD"

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun dispatch(action: String?): Boolean {
        return when (action) {
            ACTION_SEEK_BACKWARD,
            ACTION_TOGGLE_PLAY_PAUSE,
            ACTION_SEEK_FORWARD -> {
                _events.tryEmit(action)
                true
            }
            else -> false
        }
    }
}
