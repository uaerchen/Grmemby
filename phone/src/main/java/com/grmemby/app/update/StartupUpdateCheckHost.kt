package com.grmemby.app.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun StartupUpdateCheckHost() {
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        when (val result = AppUpdateChecker.checkForUpdate()) {
            is AppUpdateCheckResult.UpdateAvailable -> updateInfo = result.info
            is AppUpdateCheckResult.NoUpdate,
            is AppUpdateCheckResult.Failed -> Unit
        }
    }

    updateInfo?.let { info ->
        AppUpdateAvailableDialog(
            updateInfo = info,
            onDismiss = { updateInfo = null }
        )
    }
}
