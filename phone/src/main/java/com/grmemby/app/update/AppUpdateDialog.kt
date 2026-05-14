package com.grmemby.app.update

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grmemby.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AppUpdateAvailableDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var downloadJob by remember(updateInfo) { mutableStateOf<Job?>(null) }
    var isDownloading by remember(updateInfo) { mutableStateOf(false) }
    var progress by remember(updateInfo) { mutableStateOf(AppUpdateDownloadProgress()) }
    var downloadError by remember(updateInfo) { mutableStateOf<String?>(null) }
    var cachedApk by remember(updateInfo, context) {
        mutableStateOf(AppUpdateDownloader.findCachedApk(context.applicationContext, updateInfo))
    }

    DisposableEffect(updateInfo) {
        onDispose { downloadJob?.cancel() }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.update_available_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        R.string.update_available_message,
                        updateInfo.version.ifBlank { "v${updateInfo.versionCode}" }
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (updateInfo.changelog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.update_changelog_prefix, updateInfo.changelog),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (cachedApk != null && !isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_cached_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.totalBytes > 0L) progress.percent / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.update_download_progress,
                            progress.percent,
                            formatBytes(progress.downloadedBytes),
                            formatBytes(progress.totalBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                downloadError?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_download_failed_message, error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isDownloading) {
                        downloadJob?.cancel()
                        downloadJob = null
                        isDownloading = false
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(stringResource(R.string.update_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isDownloading) return@TextButton
                    val readyFile = cachedApk?.takeIf { it.exists() && it.length() > 0L }
                    if (readyFile != null) {
                        AppUpdateDownloader.installApk(context, readyFile)
                        return@TextButton
                    }

                    downloadError = null
                    progress = AppUpdateDownloadProgress()
                    isDownloading = true
                    downloadJob = coroutineScope.launch {
                        when (val result = AppUpdateDownloader.downloadApk(
                            context = context.applicationContext,
                            updateInfo = updateInfo,
                            onProgress = { progress = it }
                        )) {
                            is AppUpdateDownloadResult.Success -> {
                                isDownloading = false
                                cachedApk = result.file
                            }
                            is AppUpdateDownloadResult.Failed -> {
                                isDownloading = false
                                downloadError = result.message
                            }
                        }
                    }
                },
                enabled = (updateInfo.url.isNotBlank() || cachedApk != null) && !isDownloading
            ) {
                Text(
                    stringResource(
                        when {
                            isDownloading -> R.string.update_downloading
                            cachedApk != null -> R.string.update_install
                            else -> R.string.update_download
                        }
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "--"
    if (bytes < 1024L) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1fKB".format(kb)
    val mb = kb / 1024.0
    return "%.1fMB".format(mb)
}
