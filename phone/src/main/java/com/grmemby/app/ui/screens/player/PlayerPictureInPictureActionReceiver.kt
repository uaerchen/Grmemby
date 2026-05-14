package com.grmemby.app.ui.screens.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlayerPictureInPictureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PlayerPictureInPictureActions.dispatch(intent?.action)
    }
}
