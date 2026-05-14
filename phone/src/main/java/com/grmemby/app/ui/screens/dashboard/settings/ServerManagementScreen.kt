package com.grmemby.app.ui.screens.dashboard.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.grmemby.app.R
import com.grmemby.app.ui.components.glass.GlassDropdownMenu
import com.grmemby.app.ui.components.glass.GlassDropdownMenuItem
import com.grmemby.app.ui.screens.auth.ServerSwitchDialogsHost
import com.grmemby.app.ui.screens.auth.ServerSwitchViewModel
import com.grmemby.app.ui.screens.auth.rememberServerSwitchDialogsState
import com.grmemby.data.network.canonicalServerUrlKey
import com.grmemby.data.repository.AuthRepository
import com.grmemby.data.repository.ServerTransferRepository
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerManagementScreen(
    onBackPressed: () -> Unit,
    onAddServer: () -> Unit,
    onAddUser: (serverUrl: String, serverName: String?) -> Unit = { _, _ -> },
    onEditUser: (serverUrl: String, serverName: String?, username: String?) -> Unit = { serverUrl, serverName, _ ->
        onAddUser(serverUrl, serverName)
    },
    backgroundColor: Color = Color(0xFF2C3650)
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val settingsViewModel: SettingsViewModel = viewModel {
        SettingsViewModel(
            context = context.applicationContext,
            includeProfileData = false,
            includeLocalSettings = false
        )
    }
    val serverSwitchViewModel: ServerSwitchViewModel = viewModel {
        ServerSwitchViewModel(context.applicationContext as Application)
    }
    val serverManagementViewModel: ServerManagementViewModel = viewModel {
        ServerManagementViewModel(context.applicationContext)
    }
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val switchUiState by serverSwitchViewModel.uiState.collectAsStateWithLifecycle()
    val overviewUiState by serverManagementViewModel.uiState.collectAsStateWithLifecycle()
    val dialogsState = rememberServerSwitchDialogsState()
    val authRepository = remember(context) { AuthRepository(context.applicationContext) }
    val transferRepository = remember(context) { ServerTransferRepository(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var remarkGroup by remember { mutableStateOf<ServerManagementGroup?>(null) }
    var lineGroup by remember { mutableStateOf<ServerManagementGroup?>(null) }
    var remarkOverrides by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportSourceDialog by remember { mutableStateOf(false) }
    var showImportOptionsDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportSCode by remember { mutableStateOf<String?>(null) }
    var pendingImportChannel by remember { mutableStateOf<TransferChannel?>(null) }
    var importAvailability by remember { mutableStateOf<ServerTransferRepository.TransferContentAvailability?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var exportOptions by remember { mutableStateOf(ServerTransferRepository.TransferOptions(credentials = false)) }
    var importOptions by remember { mutableStateOf(ServerTransferRepository.TransferOptions(credentials = false)) }
    var exportChannel by remember { mutableStateOf(TransferChannel.LOCAL_FILE) }

    fun runTransfer(
        onSuccess: (ServerTransferRepository.TransferResult) -> Unit = {},
        block: suspend () -> Result<ServerTransferRepository.TransferResult>
    ) {
        coroutineScope.launch {
            transferBusy = true
            val message = block().fold(
                onSuccess = { result ->
                    onSuccess(result)
                    result.message
                },
                onFailure = { it.message ?: "操作失败" }
            )
            transferBusy = false
            transferMessage = message
        }
    }

    fun showImportOptionsFor(
        availability: ServerTransferRepository.TransferContentAvailability,
        channel: TransferChannel,
        uri: Uri? = null,
        sCode: String? = null
    ) {
        if (!availability.hasAny) {
            transferMessage = "所选备份没有可导入的数据。"
            return
        }
        importAvailability = availability
        importOptions = availability.defaultImportOptions()
        pendingImportChannel = channel
        pendingImportUri = uri
        pendingImportSCode = sCode
        showImportOptionsDialog = true
    }

    fun inspectImportUri(uri: Uri) {
        coroutineScope.launch {
            transferBusy = true
            val availability = transferRepository.inspectUri(uri)
            transferBusy = false
            availability.fold(
                onSuccess = { showImportOptionsFor(it, TransferChannel.LOCAL_FILE, uri = uri) },
                onFailure = { transferMessage = it.message ?: "读取备份文件失败" }
            )
        }
    }

    fun inspectImportSCode(sCode: String) {
        if (sCode.isBlank()) {
            transferMessage = "请输入完整 S码后再解析。"
            return
        }
        coroutineScope.launch {
            transferBusy = true
            val availability = transferRepository.inspectSCode(sCode)
            transferBusy = false
            availability.fold(
                onSuccess = { showImportOptionsFor(it, TransferChannel.S_CODE, sCode = sCode) },
                onFailure = { transferMessage = it.message ?: "S码读取失败" }
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runTransfer { transferRepository.exportToUri(uri, exportOptions) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val importUri = runCatching { cacheTransferImportUri(context, uri) }.getOrDefault(uri)
            inspectImportUri(importUri)
        }
    }

    val serverGroups = remember(uiState.savedServers, uiState.activeServerId, remarkOverrides) {
        uiState.savedServers
            .groupBy { canonicalServerUrlKey(it.serverUrl) }
            .map { (key, users) ->
                val sortedUsers = users.sortedWith(
                    compareByDescending<AuthRepository.SavedServer> { if (it.id == uiState.activeServerId) 1 else 0 }
                        .thenByDescending { if (authRepository.hasSavedSession(it.id)) 1 else 0 }
                        .thenBy { it.username.lowercase() }
                )
                val activeUser = sortedUsers.firstOrNull { it.id == uiState.activeServerId }
                val primary = activeUser ?: sortedUsers.first()
                val persistedRemark = sortedUsers.firstNotNullOfOrNull { savedServer ->
                    savedServer.serverRemark?.trim()?.takeIf { it.isNotBlank() }
                }
                val serverRemark = if (remarkOverrides.containsKey(key)) {
                    remarkOverrides[key]
                } else {
                    persistedRemark
                }
                ServerManagementGroup(
                    key = key,
                    serverName = primary.serverName,
                    serverTypeRaw = primary.serverTypeRaw,
                    users = sortedUsers,
                    activeUser = activeUser,
                    primaryUser = primary,
                    serverRemark = serverRemark,
                    lastUsedAt = sortedUsers.maxOfOrNull { it.lastUsedAt } ?: primary.lastUsedAt
                )
            }
            .sortedWith(
                compareByDescending<ServerManagementGroup> { if (it.activeUser != null) 1 else 0 }
                    .thenByDescending { it.lastUsedAt }
                    .thenBy { it.serverName.lowercase() }
            )
    }
    val keepAliveCandidates = remember(serverGroups) {
        serverGroups.map { it.activeUser ?: it.primaryUser }.distinctBy { it.id }
    }
    val overviewTargets = remember(serverGroups) {
        serverGroups.map { group ->
            ServerManagementLoadTarget(
                key = group.key,
                server = group.activeUser ?: group.primaryUser
            )
        }
    }
    val isOverviewRefreshing = overviewUiState.overviews.values.any { it.isLoading }

    LaunchedEffect(overviewTargets) {
        serverManagementViewModel.load(overviewTargets)
    }

    val inheritedBackdropBrush = remember(backgroundColor) { inheritedPageBrush(backgroundColor) }

    Scaffold(
        modifier = Modifier.background(backgroundColor),
        containerColor = backgroundColor
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .background(inheritedBackdropBrush)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                ServerManagementTopBar(
                    onBackPressed = onBackPressed,
                    onAddServer = onAddServer,
                    onRefreshClick = { serverManagementViewModel.refresh(overviewTargets) },
                    refreshEnabled = overviewTargets.isNotEmpty(),
                    isRefreshing = isOverviewRefreshing,
                    keepAliveEnabled = keepAliveCandidates.isNotEmpty() && !switchUiState.isKeepingAccounts,
                    isKeepAliveRunning = switchUiState.isKeepingAccounts,
                    surfaceColor = backgroundColor,
                    onKeepAliveClick = { dialogsState.openKeepAlive(keepAliveCandidates) },
                    onExportClick = { showExportDialog = true },
                    onImportClick = { showImportSourceDialog = true },
                    transferBusy = transferBusy
                )

                if (serverGroups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_no_saved_servers),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 6.dp,
                            bottom = 112.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = serverGroups,
                            key = { it.key }
                        ) { group ->
                            ServerPosterCard(
                                group = group,
                                overview = overviewUiState.overviews[group.key],
                                isSwitching = switchUiState.isSwitching && group.activeUser != null,
                                surfaceColor = backgroundColor,
                                onClick = {
                                    val target = group.activeUser ?: group.primaryUser
                                    if (!authRepository.hasSavedSession(target.id)) {
                                        onEditUser(target.serverUrl, target.serverName, target.username)
                                    } else if (target.id != uiState.activeServerId) {
                                        val refreshTarget = ServerManagementLoadTarget(
                                            key = group.key,
                                            server = target
                                        )
                                        serverSwitchViewModel.switchServer(
                                            serverId = target.id,
                                            activeServerId = uiState.activeServerId,
                                            onSwitchComplete = {
                                                serverManagementViewModel.refresh(listOf(refreshTarget))
                                            }
                                        )
                                    }
                                },
                                onLineClick = { lineGroup = group },
                                onRemarkClick = { remarkGroup = group },
                                onEditClick = {
                                    val target = group.activeUser ?: group.primaryUser
                                    onEditUser(target.serverUrl, target.serverName, target.username)
                                },
                                onDeleteClick = {
                                    dialogsState.requestRemoval(group.activeUser ?: group.primaryUser)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    remarkGroup?.let { group ->
        EditServerRemarkDialog(
            group = group,
            surfaceColor = backgroundColor,
            onDismiss = { remarkGroup = null },
            onSave = { remark ->
                val cleanedRemark = remark?.trim()?.takeIf { it.isNotBlank() }
                val serverIds = group.users.map { it.id }
                remarkOverrides = remarkOverrides + (group.key to cleanedRemark)
                coroutineScope.launch {
                    authRepository.updateSavedServerRemark(serverIds, cleanedRemark)
                }
                remarkGroup = null
            }
        )
    }

    lineGroup?.let { group ->
        EditServerLinesDialog(
            group = group,
            surfaceColor = backgroundColor,
            onDismiss = { lineGroup = null },
            onSave = { lines, activeLineId ->
                val serverIds = group.users.map { it.id }
                coroutineScope.launch {
                    authRepository.updateSavedServerLines(
                        serverIds = serverIds,
                        lines = lines,
                        activeLineId = activeLineId
                    )
                }
                lineGroup = null
            }
        )
    }


    if (showExportDialog) {
        TransferOptionsDialog(
            title = "数据导出",
            confirmText = "导出",
            options = exportOptions,
            selectedChannel = exportChannel,
            forceServersForCredentials = true,
            busy = transferBusy,
            onOptionsChange = { exportOptions = it },
            onChannelChange = { exportChannel = it },
            onDismiss = { if (!transferBusy) showExportDialog = false },
            onConfirm = {
                showExportDialog = false
                when (exportChannel) {
                    TransferChannel.S_CODE -> runTransfer(
                        onSuccess = { result ->
                            result.sCode?.let { clipboardManager.setText(AnnotatedString(it)) }
                        }
                    ) { transferRepository.exportToSCode(exportOptions) }
                    TransferChannel.LOCAL_FILE -> exportLauncher.launch("grmemby-backup-${System.currentTimeMillis()}.json")
                }
            }
        )
    }

    if (showImportSourceDialog) {
        ImportSourceDialog(
            busy = transferBusy,
            onDismiss = { if (!transferBusy) showImportSourceDialog = false },
            onSCodeImport = { sCode ->
                showImportSourceDialog = false
                inspectImportSCode(sCode)
            },
            onLocalFileImport = {
                showImportSourceDialog = false
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
        )
    }

    if (showImportOptionsDialog) {
        TransferOptionsDialog(
            title = "选择导入内容",
            confirmText = "导入",
            options = importOptions,
            availableContent = importAvailability,
            forceServersForCredentials = true,
            busy = transferBusy,
            onOptionsChange = { importOptions = it },
            onDismiss = {
                if (!transferBusy) {
                    showImportOptionsDialog = false
                    pendingImportUri = null
                    pendingImportSCode = null
                    pendingImportChannel = null
                    importAvailability = null
                }
            },
            onConfirm = {
                showImportOptionsDialog = false
                when (pendingImportChannel) {
                    TransferChannel.S_CODE -> {
                        val sCode = pendingImportSCode.orEmpty()
                        pendingImportSCode = null
                        pendingImportChannel = null
                        importAvailability = null
                        runTransfer { transferRepository.importFromSCode(sCode, importOptions) }
                    }
                    TransferChannel.LOCAL_FILE -> {
                        val uri = pendingImportUri
                        pendingImportUri = null
                        pendingImportChannel = null
                        importAvailability = null
                        if (uri != null) {
                            runTransfer { transferRepository.importFromUri(uri, importOptions) }
                        }
                    }
                    null -> {
                        importAvailability = null
                        transferMessage = "请先选择 S码导入或本地文件。"
                    }
                }
            }
        )
    }

    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 0.dp,
            title = { Text("服务器数据") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { transferMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    ServerSwitchDialogsHost(
        state = dialogsState,
        savedServers = uiState.savedServers,
        activeServerId = uiState.activeServerId,
        currentServerName = uiState.serverName,
        currentServerUrl = uiState.serverUrl,
        isSwitching = switchUiState.isBusy,
        isKeepAliveRunning = switchUiState.isKeepingAccounts,
        keepAliveMessage = switchUiState.keepAliveMessage,
        lastKeepAliveServerIds = switchUiState.lastKeepAliveServerIds,
        onKeepAliveMessageDismiss = serverSwitchViewModel::clearKeepAliveMessage,
        onAddServer = onAddServer,
        onAddUser = onAddUser,
        onEditUser = onEditUser,
        onServerSelected = { server, dismissDialog ->
            serverSwitchViewModel.switchServer(
                serverId = server.id,
                activeServerId = uiState.activeServerId,
                onSwitchComplete = dismissDialog
            )
        },
        onKeepAliveServers = { selectedServers ->
            val selectedIds = selectedServers.map { it.id }.toSet()
            val keepAliveTargets = overviewTargets.filter { it.server.id in selectedIds }
            serverSwitchViewModel.keepAliveServers(
                servers = selectedServers,
                onFinished = {
                    serverManagementViewModel.markLastPlayedNow(keepAliveTargets)
                    serverManagementViewModel.refresh(keepAliveTargets)
                }
            )
        },
        onRequestRemoveServer = dialogsState::requestRemoval,
        onRequestRemoveUser = dialogsState::requestRemoval,
        onRemoveServer = { serverId, onRemoveComplete ->
            serverSwitchViewModel.removeServer(
                serverId = serverId,
                onRemoveComplete = onRemoveComplete
            )
        }
    )

    LaunchedEffect(switchUiState.keepAliveMessage) {
        // Keep dialog host attached while background keep-alive reports progress/results.
    }
}

private enum class TransferChannel(
    val label: String,
    val description: String
) {
    S_CODE("S码", "复制/粘贴剪贴板中的压缩文本"),
    LOCAL_FILE("本地文件", "保存或选择 .json 备份文件")
}

@Composable
private fun ImportSourceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSCodeImport: (String) -> Unit,
    onLocalFileImport: () -> Unit
) {
    var selectedChannel by remember { mutableStateOf<TransferChannel?>(null) }
    var sCodeText by remember { mutableStateOf("") }
    var showSCodePlainText by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("数据导入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "先选择导入方式，读取到配置后再勾选要导入的内容。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransferChannelCard(
                        channel = TransferChannel.S_CODE,
                        selected = selectedChannel == TransferChannel.S_CODE,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedChannel = TransferChannel.S_CODE }
                    )
                    TransferChannelCard(
                        channel = TransferChannel.LOCAL_FILE,
                        selected = selectedChannel == TransferChannel.LOCAL_FILE,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = onLocalFileImport
                    )
                }
                if (selectedChannel == TransferChannel.S_CODE) {
                    OutlinedTextField(
                        value = sCodeText,
                        onValueChange = { sCodeText = it },
                        enabled = !busy,
                        visualTransformation = if (showSCodePlainText) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 132.dp),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("粘贴 S码") },
                        placeholder = { Text("请输入或粘贴完整 S码") },
                        trailingIcon = {
                            TextButton(
                                enabled = !busy,
                                onClick = { showSCodePlainText = !showSCodePlainText }
                            ) {
                                Text(if (showSCodePlainText) "密文" else "明文")
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (selectedChannel == TransferChannel.S_CODE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(enabled = !busy, onClick = onLocalFileImport) { Text("从本地文件导入") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            selectedChannel = null
                            sCodeText = ""
                            showSCodePlainText = false
                        }
                    ) { Text("取消") }
                    TextButton(
                        enabled = !busy && sCodeText.isNotBlank(),
                        onClick = { onSCodeImport(sCodeText) }
                    ) { Text("解析") }
                }
            }
        }
    )
}

@Composable
private fun TransferOptionsDialog(
    title: String,
    confirmText: String,
    options: ServerTransferRepository.TransferOptions,
    selectedChannel: TransferChannel? = null,
    availableContent: ServerTransferRepository.TransferContentAvailability? = null,
    forceServersForCredentials: Boolean,
    busy: Boolean,
    onOptionsChange: (ServerTransferRepository.TransferOptions) -> Unit,
    onChannelChange: ((TransferChannel) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val canImportPlaybackSettings = availableContent?.playbackSettings ?: true
    val canImportServers = availableContent?.servers ?: true
    val canImportRemarks = availableContent?.remarks ?: true
    val canImportCredentials = availableContent?.credentials ?: true
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedChannel != null) {
                    Text(
                        text = "方式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransferChannelCard(
                            channel = TransferChannel.S_CODE,
                            selected = selectedChannel == TransferChannel.S_CODE,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = { onChannelChange?.invoke(TransferChannel.S_CODE) }
                        )
                        TransferChannelCard(
                            channel = TransferChannel.LOCAL_FILE,
                            selected = selectedChannel == TransferChannel.LOCAL_FILE,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            onClick = { onChannelChange?.invoke(TransferChannel.LOCAL_FILE) }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
                }
                TransferOptionRow(
                    checked = options.playbackSettings && canImportPlaybackSettings,
                    text = "播放设置（设置页、缓存、弹幕等自定义项）",
                    enabled = !busy && canImportPlaybackSettings,
                    onCheckedChange = { onOptionsChange(options.copy(playbackSettings = it)) }
                )
                TransferOptionRow(
                    checked = options.servers && canImportServers,
                    text = "服务器（所有服务器卡片）",
                    enabled = !busy && canImportServers,
                    onCheckedChange = {
                        onOptionsChange(
                            options.copy(
                                servers = it,
                                credentials = if (!it && forceServersForCredentials) false else options.credentials,
                                remarks = if (!it && forceServersForCredentials) false else options.remarks
                            )
                        )
                    }
                )
                TransferOptionRow(
                    checked = options.remarks && canImportRemarks,
                    text = "备注（每个服务器卡片备注）",
                    enabled = !busy && options.servers && canImportRemarks,
                    onCheckedChange = { onOptionsChange(options.copy(remarks = it, servers = options.servers || it)) }
                )
                TransferOptionRow(
                    checked = options.credentials && canImportCredentials,
                    text = "账号密码/登录凭据（新设备可自动登录）",
                    enabled = !busy && options.servers && canImportCredentials,
                    onCheckedChange = { onOptionsChange(options.copy(credentials = it, servers = options.servers || it)) }
                )
                Text(
                    text = if (options.credentials) {
                        "已勾选账号密码：导出文件会包含登录凭据，请妥善保存。"
                    } else {
                        "未勾选账号密码：导入后的服务器卡片需要重新输入密码。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && ((options.playbackSettings && canImportPlaybackSettings) || (options.servers && canImportServers)),
                onClick = onConfirm
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TransferChannelCard(
    channel: TransferChannel,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.42f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = channel.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.86f else 0.42f)
        )
    }
}

@Composable
private fun TransferOptionRow(
    checked: Boolean,
    text: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ServerManagementTopBar(
    onBackPressed: () -> Unit,
    onAddServer: () -> Unit,
    onRefreshClick: () -> Unit,
    refreshEnabled: Boolean,
    isRefreshing: Boolean,
    keepAliveEnabled: Boolean,
    isKeepAliveRunning: Boolean,
    surfaceColor: Color,
    onKeepAliveClick: () -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    transferBusy: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val titleColor = if (surfaceColor.isDarkSurface()) Color.White else MaterialTheme.colorScheme.onSurface
    val iconTint = if (surfaceColor.isDarkSurface()) Color.White else Color(0xFF0F172A)
    val topActionShape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .settingsTopActionSurface(surfaceColor, topActionShape)
                .clickable(onClick = onBackPressed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_back_button),
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = stringResource(R.string.settings_server_label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            modifier = Modifier.align(Alignment.Center)
        )

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(144.dp)
                .height(44.dp)
                .settingsTopActionSurface(surfaceColor, topActionShape)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TopBarGlassIconSlot(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.settings_add_server),
                tint = iconTint,
                onClick = onAddServer
            )
            if (isRefreshing) {
                TopBarGlassIconSlot(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.server_management_refresh_all),
                    tint = iconTint,
                    enabled = false,
                    onClick = onRefreshClick
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = iconTint
                    )
                }
            } else {
                TopBarGlassIconSlot(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.server_management_refresh_all),
                    tint = iconTint,
                    enabled = refreshEnabled,
                    onClick = onRefreshClick
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .clickable(onClick = { expanded = true }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.settings_more_options),
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                GlassDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    minWidth = 178.dp,
                    dark = true,
                    surfaceColor = surfaceColor
                ) {
                    GlassDropdownMenuItem(
                        text = if (isKeepAliveRunning) {
                            stringResource(R.string.settings_keep_accounts_running)
                        } else {
                            stringResource(R.string.settings_keep_accounts)
                        },
                        enabled = keepAliveEnabled,
                        leadingIcon = Icons.Rounded.Verified,
                        dark = true,
                        surfaceColor = surfaceColor,
                        onClick = {
                            expanded = false
                            onKeepAliveClick()
                        }
                    )
                    GlassDropdownMenuItem(
                        text = "数据导出",
                        enabled = !transferBusy,
                        leadingIcon = Icons.Rounded.Storage,
                        dark = true,
                        surfaceColor = surfaceColor,
                        onClick = {
                            expanded = false
                            onExportClick()
                        }
                    )
                    GlassDropdownMenuItem(
                        text = "数据导入",
                        enabled = !transferBusy,
                        leadingIcon = Icons.Rounded.Movie,
                        dark = true,
                        surfaceColor = surfaceColor,
                        onClick = {
                            expanded = false
                            onImportClick()
                        }
                    )
                }
            }
        }
    }
}

private fun Modifier.settingsTopActionSurface(
    surfaceColor: Color,
    shape: RoundedCornerShape
): Modifier {
    val dark = surfaceColor.isDarkSurface()
    return this
        .shadow(
            elevation = 16.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.18f),
            spotColor = Color.Black.copy(alpha = 0.26f)
        )
        .clip(shape)
        .background(Color(0x1A1C1C1E), shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.075f else 0.12f),
                    Color.White.copy(alpha = if (dark) 0.022f else 0.05f),
                    Color.Black.copy(alpha = if (dark) 0.045f else 0.02f)
                )
            ),
            shape
        )
        .border(
            width = 0.6.dp,
            color = Color.Black.copy(alpha = if (dark) 0.42f else 0.24f),
            shape = shape
        )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TopBarGlassIconSlot(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    progressContent: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (progressContent != null) {
            progressContent()
        } else {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = tint.copy(alpha = if (enabled) 0.96f else 0.42f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ServerPosterCard(
    group: ServerManagementGroup,
    overview: ServerCardOverview?,
    isSwitching: Boolean,
    surfaceColor: Color,
    onClick: () -> Unit,
    onLineClick: () -> Unit,
    onRemarkClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val active = group.activeUser != null
    val primaryUser = group.activeUser ?: group.primaryUser
    val shape = RoundedCornerShape(16.dp)
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val primaryTextColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
    val tertiaryTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
    val activeTextColor = MaterialTheme.colorScheme.primary
    val accountLabel = primaryUser.username.ifBlank { stringResource(R.string.settings_unknown_username) }
    val remarkText = group.serverRemark ?: if (group.users.size > 1) {
        stringResource(R.string.server_management_remark_multiple, accountLabel, group.users.size)
    } else {
        accountLabel
    }
    val countPlaceholder = if (overview?.isLoading == true) "…" else "--"
    val movieCountText = overview?.movieCount?.toString() ?: countPlaceholder
    val seriesCountText = overview?.seriesCount?.toString() ?: countPlaceholder
    val lastPlayedText = overview?.lastPlayedAtEpochMs
        ?.let(::relativeLastPlayedText)
        ?: stringResource(R.string.server_management_recent_never)
    val timeText = if (active) {
        stringResource(R.string.server_management_watching_time, lastPlayedText)
    } else {
        lastPlayedText
    }
    val connected = overview?.isConnected == true
    val latencyText = overview?.latencyMs?.let { "${it}ms" }
        ?: if (overview?.isLoading == true) "…ms" else "--ms"
    val lineLatencyText = "${primaryUser.lineLabel()} · $latencyText"
    var actionMenuExpanded by remember(group.key) { mutableStateOf(false) }
    var actionMenuOffset by remember(group.key) { mutableStateOf(DpOffset.Zero) }
    val menuAnchorOffset = DpOffset(
        x = (actionMenuOffset.x - 12.dp).coerceAtLeast(0.dp),
        y = (actionMenuOffset.y - 10.dp).coerceAtLeast(0.dp)
    )
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.56f)
            .heightIn(min = 104.dp)
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, cardBorder, shape)
            .pointerInput(isSwitching, density) {
                detectTapGestures(
                    onTap = {
                        if (!isSwitching) onClick()
                    },
                    onLongPress = { pressOffset ->
                        if (!isSwitching) {
                            actionMenuOffset = with(density) {
                                DpOffset(
                                    x = pressOffset.x.toDp(),
                                    y = pressOffset.y.toDp()
                                )
                            }
                            actionMenuExpanded = true
                        }
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(0.68f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.serverName.ifBlank { stringResource(R.string.settings_media_server) },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (connected) Color(0xFF2FD46F) else Color(0xFFE5484D))
                        .border(1.dp, Color.White.copy(alpha = 0.86f), CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = remarkText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                ),
                color = secondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.68f)
            )

            Spacer(modifier = Modifier.weight(1f))

            ServerResourceCountRow(
                movieCount = movieCountText,
                seriesCount = seriesCountText,
                textColor = secondaryTextColor,
                iconTint = tertiaryTextColor,
                modifier = Modifier.fillMaxWidth(0.74f)
            )

            Spacer(modifier = Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lineLatencyText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 12.sp
                    ),
                    color = tertiaryTextColor,
                    maxLines = 1
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (active) activeTextColor else tertiaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        ServerLogoImage(
            imageUrl = overview?.logoUrl,
            fallbackText = group.serverName.ifBlank { primaryUser.username },
            tintColor = Color(overview?.logoAccentArgb ?: seedAccentArgb(group.key)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
        )

        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 31.dp, end = 1.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.96f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.settings_active_server),
                    tint = Color(0xFF258BFF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = menuAnchorOffset.x, y = menuAnchorOffset.y)
                .size(1.dp)
        ) {
            GlassDropdownMenu(
                expanded = actionMenuExpanded,
                onDismissRequest = { actionMenuExpanded = false },
                offset = DpOffset.Zero,
                minWidth = 184.dp,
                dark = true,
                surfaceColor = surfaceColor
            ) {
                GlassDropdownMenuItem(
                    text = stringResource(R.string.server_management_action_lines),
                    leadingIcon = Icons.Rounded.Storage,
                    dark = true,
                    surfaceColor = surfaceColor,
                    onClick = {
                        actionMenuExpanded = false
                        onLineClick()
                    }
                )
                GlassDropdownMenuItem(
                    text = stringResource(R.string.server_management_action_remark),
                    leadingIcon = Icons.Rounded.Edit,
                    dark = true,
                    surfaceColor = surfaceColor,
                    onClick = {
                        actionMenuExpanded = false
                        onRemarkClick()
                    }
                )
                GlassDropdownMenuItem(
                    text = stringResource(R.string.server_management_action_edit_server),
                    leadingIcon = Icons.Rounded.PeopleAlt,
                    dark = true,
                    surfaceColor = surfaceColor,
                    onClick = {
                        actionMenuExpanded = false
                        onEditClick()
                    }
                )
                GlassDropdownMenuItem(
                    text = stringResource(R.string.server_management_action_delete),
                    leadingIcon = Icons.Rounded.Delete,
                    dark = true,
                    surfaceColor = surfaceColor,
                    onClick = {
                        actionMenuExpanded = false
                        onDeleteClick()
                    }
                )
            }
        }

        if (isSwitching) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = if (surfaceColor.isDarkSurface()) Color.White else Color(0xFF258BFF)
                )
            }
        }
    }
}

@Composable
private fun ServerLogoImage(
    imageUrl: String?,
    fallbackText: String,
    tintColor: Color,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasError by remember(imageUrl) { mutableStateOf(false) }
    val logoRequest = remember(context, imageUrl) {
        imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(180)
                .build()
        }
    }
    val initial = remember(fallbackText) {
        fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (logoRequest != null && !hasError) {
            AsyncImage(
                model = logoRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                onError = { hasError = true }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                pastelTint(tintColor).copy(alpha = 0.86f),
                                blendTowardWhite(tintColor, 0.66f).copy(alpha = 0.72f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color(0xFF334155),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ServerResourceCountRow(
    movieCount: String,
    seriesCount: String,
    textColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = movieCount,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = seriesCount,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun EditServerLinesDialog(
    group: ServerManagementGroup,
    surfaceColor: Color,
    onDismiss: () -> Unit,
    onSave: (List<AuthRepository.ServerLine>, String?) -> Unit
) {
    val target = group.activeUser ?: group.primaryUser
    var lines by remember(group.key, target.serverLines) { mutableStateOf(target.serverLines) }
    var activeLineId by remember(group.key, target.activeLineId) { mutableStateOf(target.activeLineId) }
    var editingLineId by remember(group.key) { mutableStateOf<String?>(null) }
    var lineName by remember(group.key) { mutableStateOf("") }
    var lineUrl by remember(group.key) { mutableStateOf("") }
    var isReverseProxy by remember(group.key) { mutableStateOf(false) }
    val dark = surfaceColor.isDarkSurface()
    val container = remember(surfaceColor, dark) {
        if (dark) blendTowardWhite(surfaceColor, 0.16f).copy(alpha = 0.96f) else blendTowardWhite(surfaceColor, 0.88f)
    }
    val textColor = if (dark) Color.White else Color(0xFF111827)
    val secondaryTextColor = if (dark) Color.White.copy(alpha = 0.70f) else Color(0xFF64748B)
    val accent = if (dark) blendTowardWhite(surfaceColor, 0.58f) else Color(0xFF258BFF)

    fun resetEditor() {
        editingLineId = null
        lineName = ""
        lineUrl = ""
        isReverseProxy = false
    }

    fun upsertEditorLine() {
        val cleanedUrl = lineUrl.trim()
        if (cleanedUrl.isBlank()) return
        val id = editingLineId ?: "line-${System.currentTimeMillis()}-${cleanedUrl.hashCode()}"
        val nextLine = AuthRepository.ServerLine(
            id = id,
            name = lineName.trim(),
            url = cleanedUrl,
            isReverseProxy = isReverseProxy,
            createdAt = lines.firstOrNull { it.id == id }?.createdAt ?: System.currentTimeMillis()
        )
        lines = if (editingLineId == null) {
            (lines + nextLine).distinctBy { it.id }
        } else {
            lines.map { line -> if (line.id == id) nextLine else line }
        }
        activeLineId = activeLineId?.takeIf { activeId -> (lines + nextLine).any { it.id == activeId } }
        resetEditor()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = container,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = "服务器线路",
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = group.serverName.ifBlank { stringResource(R.string.settings_media_server) },
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ServerLineRow(
                    title = "主线路",
                    subtitle = target.serverUrl,
                    selected = activeLineId == null,
                    textColor = textColor,
                    secondaryTextColor = secondaryTextColor,
                    accent = accent,
                    onSelect = { activeLineId = null },
                    onEdit = null,
                    onDelete = null
                )
                lines.forEach { line ->
                    HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.16f))
                    ServerLineRow(
                        title = line.name.takeIf { it.isNotBlank() } ?: if (line.isReverseProxy) "反代线路" else "备用线路",
                        subtitle = if (line.isReverseProxy) "反代：${line.url}" else line.url,
                        selected = activeLineId == line.id,
                        textColor = textColor,
                        secondaryTextColor = secondaryTextColor,
                        accent = accent,
                        onSelect = { activeLineId = line.id },
                        onEdit = {
                            editingLineId = line.id
                            lineName = line.name
                            lineUrl = line.url
                            isReverseProxy = line.isReverseProxy
                        },
                        onDelete = {
                            lines = lines.filterNot { it.id == line.id }
                            if (activeLineId == line.id) activeLineId = null
                            if (editingLineId == line.id) resetEditor()
                        }
                    )
                }
                HorizontalDivider(color = secondaryTextColor.copy(alpha = 0.16f))
                Text(
                    text = if (editingLineId == null) "添加备用线路" else "编辑备用线路",
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    value = lineName,
                    onValueChange = { lineName = it.take(32) },
                    singleLine = true,
                    label = { Text("线路名称（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lineUrl,
                    onValueChange = { lineUrl = it.trim().take(240) },
                    singleLine = true,
                    label = { Text("备用服务器地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { isReverseProxy = !isReverseProxy }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isReverseProxy,
                        onCheckedChange = { isReverseProxy = it }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("作为反代地址", color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "勾选后请求走该地址，并把主线路地址作为路径参数转发。",
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editingLineId != null) {
                        TextButton(onClick = ::resetEditor) {
                            Text("取消编辑", color = secondaryTextColor)
                        }
                    }
                    TextButton(
                        enabled = lineUrl.trim().isNotBlank(),
                        onClick = ::upsertEditorLine
                    ) {
                        Text(if (editingLineId == null) "添加线路" else "保存线路", color = accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(lines, activeLineId) }) {
                Text(stringResource(R.string.save), color = accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = secondaryTextColor)
            }
        }
    )
}

@Composable
private fun ServerLineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    textColor: Color,
    secondaryTextColor: Color,
    accent: Color,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) accent else secondaryTextColor.copy(alpha = 0.42f), CircleShape)
                .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = secondaryTextColor,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        onEdit?.let {
            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Text("编辑", color = accent, fontSize = 12.sp)
            }
        }
        onDelete?.let {
            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Text("删除", color = Color(0xFFE5484D), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EditServerRemarkDialog(
    group: ServerManagementGroup,
    surfaceColor: Color,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var remark by remember(group.key) { mutableStateOf(group.serverRemark.orEmpty()) }
    val dark = surfaceColor.isDarkSurface()
    val container = remember(surfaceColor, dark) {
        if (dark) blendTowardWhite(surfaceColor, 0.16f).copy(alpha = 0.96f) else blendTowardWhite(surfaceColor, 0.88f)
    }
    val textColor = if (dark) Color.White else Color(0xFF111827)
    val secondaryTextColor = if (dark) Color.White.copy(alpha = 0.70f) else Color(0xFF64748B)
    val accent = if (dark) blendTowardWhite(surfaceColor, 0.58f) else blendTowardWhite(surfaceColor, 0.24f)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = container,
        tonalElevation = 0.dp,
        title = {
            Text(
                text = stringResource(R.string.server_management_remark_title),
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = group.serverName,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.server_management_remark_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedLabelColor = accent,
                        unfocusedLabelColor = secondaryTextColor,
                        cursorColor = accent,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = secondaryTextColor.copy(alpha = 0.46f),
                        focusedContainerColor = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.62f),
                        unfocusedContainerColor = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.50f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(remark) }) {
                Text(stringResource(R.string.save), color = accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = secondaryTextColor)
            }
        }
    )
}

private data class ServerManagementGroup(
    val key: String,
    val serverName: String,
    val serverTypeRaw: String?,
    val users: List<AuthRepository.SavedServer>,
    val activeUser: AuthRepository.SavedServer?,
    val primaryUser: AuthRepository.SavedServer,
    val serverRemark: String?,
    val lastUsedAt: Long
)

private val ServerCardSeedAccents = listOf(
    AndroidColor.rgb(226, 178, 74),
    AndroidColor.rgb(227, 126, 151),
    AndroidColor.rgb(152, 127, 219),
    AndroidColor.rgb(109, 194, 166),
    AndroidColor.rgb(111, 169, 219),
    AndroidColor.rgb(232, 163, 118),
    AndroidColor.rgb(174, 190, 178),
    AndroidColor.rgb(202, 154, 209)
)

private fun seedAccentArgb(key: String): Int {
    val hash = key.hashCode().let { if (it == Int.MIN_VALUE) 0 else abs(it) }
    return ServerCardSeedAccents[hash % ServerCardSeedAccents.size]
}

private fun pastelTint(color: Color): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.20f).coerceIn(0.08f, 0.22f)
    hsv[2] = 0.97f
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun blendTowardWhite(color: Color, fraction: Float): Color {
    return Color(
        red = color.red + (1f - color.red) * fraction,
        green = color.green + (1f - color.green) * fraction,
        blue = color.blue + (1f - color.blue) * fraction,
        alpha = 1f
    )
}

private fun inheritedPageBrush(surfaceColor: Color): Brush {
    return if (surfaceColor.isDarkSurface()) {
        val deep = Color(
            red = surfaceColor.red * 0.72f,
            green = surfaceColor.green * 0.72f,
            blue = surfaceColor.blue * 0.72f,
            alpha = 1f
        )
        Brush.verticalGradient(
            colors = listOf(
                blendTowardWhite(surfaceColor, 0.06f),
                surfaceColor,
                deep
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                blendTowardWhite(surfaceColor, 0.82f),
                blendTowardWhite(surfaceColor, 0.92f),
                Color(0xFFFDFDFE)
            )
        )
    }
}

private fun cacheTransferImportUri(context: Context, uri: Uri): Uri {
    val appContext = context.applicationContext
    val importCacheDir = File(appContext.cacheDir, "server-transfer-imports").apply { mkdirs() }
    importCacheDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith("grmemby-import-")) {
            file.delete()
        }
    }
    val cachedFile = File(importCacheDir, "grmemby-import-${System.currentTimeMillis()}.json")
    appContext.contentResolver.openInputStream(uri)?.use { input ->
        cachedFile.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取导入文件")
    return Uri.fromFile(cachedFile)
}

private fun Color.isDarkSurface(): Boolean = luminance() < 0.46f

private fun relativeLastPlayedText(lastPlayedAt: Long): String {
    val elapsedMs = (System.currentTimeMillis() - lastPlayedAt).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val month = 30L * day
    return when {
        elapsedMs < minute -> "刚刚"
        elapsedMs < hour -> "${elapsedMs / minute}分钟前"
        elapsedMs < day -> "${elapsedMs / hour}小时前"
        elapsedMs < month -> "${elapsedMs / day}天前"
        else -> "${elapsedMs / month}月前"
    }
}
