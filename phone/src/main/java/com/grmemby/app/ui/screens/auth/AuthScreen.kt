package com.grmemby.app.ui.screens.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grmemby.app.R
import com.grmemby.app.ui.components.common.amoledAuthFieldColors
import com.grmemby.shared.ui.theme.JellyBlue
import com.grmemby.shared.ui.theme.JellyRed
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.ServerTransferRepository
import kotlinx.coroutines.launch
import java.io.File

enum class AuthStep {
    SERVER_CONNECTION,
    LOGIN
}

private enum class InitialImportSource(
    val label: String,
    val description: String
) {
    S_CODE("S码", "粘贴压缩文本并解析"),
    LOCAL_FILE("本地文件", "选择 .json 备份文件")
}

@Composable
fun AuthScreen(
    serverUrl: String? = null,
    serverName: String? = null,
    initialUsername: String? = null,
    startAtLogin: Boolean = false,
    preferSavedServers: Boolean = false,
    onAddServer: () -> Unit = {},
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val authViewModel: AuthScreenViewModel = viewModel {
        AuthScreenViewModel(context.applicationContext as android.app.Application)
    }
    val authRepository = remember { AuthRepositoryProvider.getInstance(context) }
    val serverSwitchViewModel: ServerSwitchViewModel = viewModel {
        ServerSwitchViewModel(context.applicationContext as android.app.Application)
    }
    val initialSessionSnapshot = remember { authRepository.getActiveSessionSnapshot() }
    val uiState by authViewModel.uiState.collectAsState()
    val serverSwitchUiState by serverSwitchViewModel.uiState.collectAsState()
    val sessionSnapshot by authRepository.observeActiveSession().collectAsState(
        initial = initialSessionSnapshot
    )
    val serverSwitchDialogsState = rememberServerSwitchDialogsState()
    val transferRepository = remember(context) { ServerTransferRepository(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var transferBusy by remember { mutableStateOf(false) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var pendingInitialImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInitialImportSCode by remember { mutableStateOf<String?>(null) }
    var pendingInitialImportSource by remember { mutableStateOf<InitialImportSource?>(null) }
    var initialImportAvailability by remember { mutableStateOf<ServerTransferRepository.TransferContentAvailability?>(null) }
    var showInitialImportSourceDialog by remember { mutableStateOf(false) }
    var showInitialImportOptionsDialog by remember { mutableStateOf(false) }
    var initialImportOptions by remember {
        mutableStateOf(ServerTransferRepository.TransferOptions(credentials = false))
    }
    var openServerChooserAfterImport by remember { mutableStateOf(false) }

    fun handleInitialImportResult(
        options: ServerTransferRepository.TransferOptions,
        result: Result<ServerTransferRepository.TransferResult>
    ) {
        result.fold(
            onSuccess = { transferResult ->
                if (options.servers && transferResult.importedServers > 0) {
                    transferMessage = null
                    openServerChooserAfterImport = true
                } else {
                    transferMessage = transferResult.message
                }
            },
            onFailure = { error ->
                transferMessage = error.message ?: "导入失败"
            }
        )
    }

    fun runInitialImport(options: ServerTransferRepository.TransferOptions) {
        val source = pendingInitialImportSource
        val uri = pendingInitialImportUri
        val sCode = pendingInitialImportSCode.orEmpty()
        pendingInitialImportUri = null
        pendingInitialImportSCode = null
        pendingInitialImportSource = null
        initialImportAvailability = null
        coroutineScope.launch {
            transferBusy = true
            val result = when (source) {
                InitialImportSource.S_CODE -> transferRepository.importFromSCode(sCode, options)
                InitialImportSource.LOCAL_FILE -> if (uri != null) {
                    transferRepository.importFromUri(uri, options)
                } else {
                    Result.failure(IllegalStateException("未选择本地文件"))
                }
                null -> Result.failure(IllegalStateException("请先选择 S码导入或本地文件"))
            }
            transferBusy = false
            handleInitialImportResult(options, result)
        }
    }

    fun showInitialImportOptionsFor(
        availability: ServerTransferRepository.TransferContentAvailability,
        source: InitialImportSource,
        uri: Uri? = null,
        sCode: String? = null
    ) {
        if (!availability.hasAny) {
            transferMessage = "所选备份没有可导入的数据。"
            return
        }
        initialImportAvailability = availability
        initialImportOptions = availability.defaultImportOptions()
        pendingInitialImportSource = source
        pendingInitialImportUri = uri
        pendingInitialImportSCode = sCode
        showInitialImportOptionsDialog = true
    }

    fun inspectInitialImportUri(uri: Uri) {
        coroutineScope.launch {
            transferBusy = true
            val availability = transferRepository.inspectUri(uri)
            transferBusy = false
            availability.fold(
                onSuccess = { showInitialImportOptionsFor(it, InitialImportSource.LOCAL_FILE, uri = uri) },
                onFailure = { transferMessage = it.message ?: "读取备份文件失败" }
            )
        }
    }

    fun inspectInitialImportSCode(sCode: String) {
        if (sCode.isBlank()) {
            transferMessage = "请输入完整 S码后再解析。"
            return
        }
        coroutineScope.launch {
            transferBusy = true
            val availability = transferRepository.inspectSCode(sCode)
            transferBusy = false
            availability.fold(
                onSuccess = { showInitialImportOptionsFor(it, InitialImportSource.S_CODE, sCode = sCode) },
                onFailure = { transferMessage = it.message ?: "S码读取失败" }
            )
        }
    }

    val initialImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val importUri = runCatching { cacheInitialImportUri(context, it) }.getOrDefault(it)
            inspectInitialImportUri(importUri)
        }
    }

    val login = startAtLogin && !serverUrl.isNullOrBlank()
    val displaySavedServers = preferSavedServers && !login && sessionSnapshot.savedServers.isNotEmpty()
    var currentStep by remember(login) {
        mutableStateOf(if (login) AuthStep.LOGIN else AuthStep.SERVER_CONNECTION)
    }
    val showServerConnection = currentStep == AuthStep.SERVER_CONNECTION && displaySavedServers
    var selectedServerName by remember(serverName) { mutableStateOf(serverName) }
    var selectedServerUrl by remember(serverUrl) { mutableStateOf(serverUrl.orEmpty()) }
    val canNavigateBackToServerStep = currentStep == AuthStep.LOGIN && !login

    LaunchedEffect(displaySavedServers, currentStep) {
        if (
            displaySavedServers &&
            currentStep == AuthStep.SERVER_CONNECTION &&
            !serverSwitchDialogsState.showServerSwitchDialog &&
            !serverSwitchDialogsState.showUserSwitchDialog
        ) {
            serverSwitchDialogsState.openServers()
        }
    }

    LaunchedEffect(openServerChooserAfterImport, sessionSnapshot.savedServers.size) {
        if (openServerChooserAfterImport && sessionSnapshot.savedServers.isNotEmpty()) {
            currentStep = AuthStep.SERVER_CONNECTION
            serverSwitchDialogsState.openServers()
            openServerChooserAfterImport = false
        }
    }

    BackHandler(enabled = canNavigateBackToServerStep && !uiState.isLoginLoading) {
        authViewModel.clearLoginError()
        currentStep = AuthStep.SERVER_CONNECTION
    }

    LaunchedEffect(currentStep, selectedServerUrl) {
        if (currentStep == AuthStep.LOGIN) {
            authViewModel.refreshQuickConnectVisibility(selectedServerUrl)
        }
    }

    LaunchedEffect(login, initialUsername, serverUrl) {
        if (login) {
            authViewModel.updateServerUrl(serverUrl.orEmpty())
            authViewModel.updateUsername(initialUsername.orEmpty())
            authViewModel.updatePassword("")
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black,
                            Color.Black,
                            Color(0xFF030406),
                            Color.Black
                        )
                    )
                )
                .imePadding()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            ServerSwitchDialogsHost(
                state = serverSwitchDialogsState,
                savedServers = sessionSnapshot.savedServers,
                activeServerId = sessionSnapshot.activeServerId,
                currentServerName = selectedServerName,
                currentServerUrl = selectedServerUrl,
                isSwitching = serverSwitchUiState.isBusy,
                isKeepAliveRunning = serverSwitchUiState.isKeepingAccounts,
                keepAliveMessage = serverSwitchUiState.keepAliveMessage,
                lastKeepAliveServerIds = serverSwitchUiState.lastKeepAliveServerIds,
                onKeepAliveMessageDismiss = serverSwitchViewModel::clearKeepAliveMessage,
                onAddServer = onAddServer,
                onAddUser = { restoredServerUrl, restoredServerName ->
                    selectedServerUrl = restoredServerUrl
                    selectedServerName = restoredServerName
                    authViewModel.updateServerUrl(restoredServerUrl)
                    authViewModel.updatePassword("")
                    currentStep = AuthStep.LOGIN
                },
                onEditUser = { restoredServerUrl, restoredServerName, restoredUsername ->
                    selectedServerUrl = restoredServerUrl
                    selectedServerName = restoredServerName
                    authViewModel.updateServerUrl(restoredServerUrl)
                    authViewModel.updateUsername(restoredUsername.orEmpty())
                    authViewModel.updatePassword("")
                    currentStep = AuthStep.LOGIN
                },
                onServerSelected = { savedServer, dismissDialog ->
                    serverSwitchViewModel.switchServer(
                        serverId = savedServer.id,
                        activeServerId = sessionSnapshot.activeServerId,
                        onSwitchComplete = {
                            dismissDialog()
                            onAuthSuccess()
                        },
                        onSwitchFailed = { error ->
                            authViewModel.updateServerUrl(savedServer.serverUrl)
                            authViewModel.updateUsername(savedServer.username)
                            authViewModel.updatePassword("")
                            authViewModel.setLoginError(error)
                            selectedServerUrl = savedServer.serverUrl
                            selectedServerName = savedServer.serverName
                            dismissDialog()
                            currentStep = AuthStep.LOGIN
                        }
                    )
                },
                onKeepAliveServers = serverSwitchViewModel::keepAliveServers,
                showRemoveAction = false,
                showEditAction = false,
                showKeepAliveAction = false,
                showAddUserAction = false,
                dismissServerDialogOnRequest = false,
                dismissUserDialogOnRequest = true,
                showServerCloseAction = false,
                useLightGlass = false,
                onServerDialogDismiss = {},
                onUserDialogDismiss = {
                    if (displaySavedServers) {
                        serverSwitchDialogsState.returnToServers()
                    } else {
                        serverSwitchDialogsState.dismissUsers()
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp)
            ) {
                when (currentStep) {
                    AuthStep.SERVER_CONNECTION -> ServerConnectionContent(
                        modifier = Modifier.fillMaxSize(),
                        serverUrl = uiState.serverUrl,
                        isAwaitingSavedServers = showServerConnection,
                        isLoading = uiState.isServerLoading,
                        isImporting = transferBusy,
                        errorMessage = uiState.serverErrorMessage,
                        onServerUrlChange = authViewModel::updateServerUrl,
                        onImportData = {
                            showInitialImportSourceDialog = true
                        },
                        onConnect = {
                            authViewModel.connectToServer { url, name ->
                                selectedServerUrl = url
                                selectedServerName = name
                                currentStep = AuthStep.LOGIN
                            }
                        }
                    )

                    AuthStep.LOGIN -> LoginContent(
                        modifier = Modifier.fillMaxSize(),
                        serverUrl = selectedServerUrl,
                        serverName = selectedServerName,
                        username = uiState.username,
                        password = uiState.password,
                        isLoading = uiState.isLoginLoading,
                        errorMessage = uiState.loginErrorMessage,
                        showQuickConnect = uiState.showQuickConnect,
                        isQuickConnectLoading = uiState.isQuickConnectLoading,
                        quickConnectCode = uiState.quickConnectCode,
                        onServerUrlChange = { updatedServerUrl ->
                            selectedServerUrl = updatedServerUrl
                            authViewModel.updateServerUrl(updatedServerUrl)
                        },
                        onUsernameChange = authViewModel::updateUsername,
                        onPasswordChange = authViewModel::updatePassword,
                        onLogin = { authViewModel.login(selectedServerUrl, onAuthSuccess) },
                        onQuickConnect = {
                            authViewModel.loginWithQuickConnect(selectedServerUrl, onAuthSuccess)
                        }
                    )
                }
            }
        }
    }

    if (showInitialImportSourceDialog) {
        InitialImportSourceDialog(
            busy = transferBusy,
            onDismiss = { if (!transferBusy) showInitialImportSourceDialog = false },
            onSCodeImport = { sCode ->
                showInitialImportSourceDialog = false
                inspectInitialImportSCode(sCode)
            },
            onLocalFileImport = {
                showInitialImportSourceDialog = false
                initialImportLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
        )
    }

    if (showInitialImportOptionsDialog) {
        InitialImportOptionsDialog(
            options = initialImportOptions,
            availability = initialImportAvailability,
            busy = transferBusy,
            onOptionsChange = { initialImportOptions = it },
            onDismiss = {
                if (!transferBusy) {
                    showInitialImportOptionsDialog = false
                    pendingInitialImportUri = null
                    pendingInitialImportSCode = null
                    pendingInitialImportSource = null
                    initialImportAvailability = null
                }
            },
            onConfirm = {
                showInitialImportOptionsDialog = false
                runInitialImport(initialImportOptions)
            }
        )
    }

    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            title = { Text("数据导入") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { transferMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun InitialImportSourceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSCodeImport: (String) -> Unit,
    onLocalFileImport: () -> Unit
) {
    var selectedSource by remember { mutableStateOf<InitialImportSource?>(null) }
    var sCodeText by remember { mutableStateOf("") }
    var showSCodePlainText by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("数据导入", fontWeight = FontWeight.Bold) },
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
                    InitialImportSourceCard(
                        source = InitialImportSource.S_CODE,
                        selected = selectedSource == InitialImportSource.S_CODE,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSource = InitialImportSource.S_CODE }
                    )
                    InitialImportSourceCard(
                        source = InitialImportSource.LOCAL_FILE,
                        selected = selectedSource == InitialImportSource.LOCAL_FILE,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = onLocalFileImport
                    )
                }
                if (selectedSource == InitialImportSource.S_CODE) {
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
            if (selectedSource == InitialImportSource.S_CODE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(enabled = !busy, onClick = onLocalFileImport) { Text("从本地文件导入") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            selectedSource = null
                            sCodeText = ""
                            showSCodePlainText = false
                        }
                    ) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        enabled = !busy && sCodeText.isNotBlank(),
                        onClick = { onSCodeImport(sCodeText) }
                    ) {
                        Text("解析", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    )
}

@Composable
private fun InitialImportSourceCard(
    source: InitialImportSource,
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
                text = source.label,
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
            text = source.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.86f else 0.42f)
        )
    }
}

@Composable
private fun InitialImportOptionsDialog(
    options: ServerTransferRepository.TransferOptions,
    availability: ServerTransferRepository.TransferContentAvailability?,
    busy: Boolean,
    onOptionsChange: (ServerTransferRepository.TransferOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val canImportPlaybackSettings = availability?.playbackSettings ?: true
    val canImportServers = availability?.servers ?: true
    val canImportRemarks = availability?.remarks ?: true
    val canImportCredentials = availability?.credentials ?: true
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text("选择导入数据项", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InitialImportOptionRow(
                    checked = options.playbackSettings && canImportPlaybackSettings,
                    text = "设置内容（播放、缓存、网络、弹幕等）",
                    enabled = !busy && canImportPlaybackSettings,
                    onCheckedChange = { onOptionsChange(options.copy(playbackSettings = it)) }
                )
                InitialImportOptionRow(
                    checked = options.servers && canImportServers,
                    text = "服务器（所有服务器卡片）",
                    enabled = !busy && canImportServers,
                    onCheckedChange = {
                        onOptionsChange(
                            options.copy(
                                servers = it,
                                remarks = if (!it) false else options.remarks,
                                credentials = if (!it) false else options.credentials
                            )
                        )
                    }
                )
                InitialImportOptionRow(
                    checked = options.remarks && canImportRemarks,
                    text = "备注（每个服务器卡片备注）",
                    enabled = !busy && options.servers && canImportRemarks,
                    onCheckedChange = { onOptionsChange(options.copy(remarks = it, servers = options.servers || it)) }
                )
                InitialImportOptionRow(
                    checked = options.credentials && canImportCredentials,
                    text = "账号密码/登录凭据（勾选后可自动登录）",
                    enabled = !busy && options.servers && canImportCredentials,
                    onCheckedChange = { onOptionsChange(options.copy(credentials = it, servers = options.servers || it)) }
                )
                Text(
                    text = if (options.credentials) {
                        "已勾选账号密码：仅用于恢复你备份中的 Grmemby 登录凭据。"
                    } else {
                        "未勾选账号密码：导入后的服务器需重新输入密码。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && ((options.playbackSettings && canImportPlaybackSettings) || (options.servers && canImportServers)),
                onClick = onConfirm
            ) { Text("导入", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun InitialImportOptionRow(
    checked: Boolean,
    text: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AnimatedBrandHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val logoMotion = rememberInfiniteTransition(label = "logo_motion")
    val driftX by logoMotion.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_drift_x"
    )
    val driftY by logoMotion.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_drift_y"
    )
    val tilt by logoMotion.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_tilt"
    )
    val pulse by logoMotion.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.grmemby_black_icon),
            contentDescription = stringResource(
                R.string.feature_logo_content_description,
                stringResource(R.string.app_name)
            ),
            modifier = Modifier
                .size(132.dp)
                .graphicsLayer {
                    translationX = driftX
                    translationY = driftY
                    rotationZ = tilt
                }
                .scale(pulse),
            contentScale = ContentScale.Fit
        )

        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.88f)
        )
    }
}

@Composable
private fun ServerConnectionContent(
    modifier: Modifier = Modifier,
    serverUrl: String,
    isAwaitingSavedServers: Boolean,
    isLoading: Boolean,
    isImporting: Boolean,
    errorMessage: String?,
    onServerUrlChange: (String) -> Unit,
    onImportData: () -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedBrandHero(
            title = stringResource(R.string.auth_connect_title),
            subtitle = stringResource(R.string.auth_connect_subtitle),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (isAwaitingSavedServers) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            }
        } else {
            ConnectionForm(
                serverUrl = serverUrl,
                isLoading = isLoading,
                isImporting = isImporting,
                errorMessage = errorMessage,
                onServerUrlChange = onServerUrlChange,
                onImportData = onImportData,
                onConnect = onConnect,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LoginContent(
    modifier: Modifier = Modifier,
    serverUrl: String,
    serverName: String?,
    username: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    showQuickConnect: Boolean,
    isQuickConnectLoading: Boolean,
    quickConnectCode: String?,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onQuickConnect: () -> Unit
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedBrandHero(
            title = serverName ?: stringResource(R.string.auth_welcome_back),
            subtitle = if (serverUrl.isNotBlank()) serverUrl else stringResource(R.string.auth_sign_in_subtitle),
            modifier = Modifier.padding(bottom = 20.dp)
        )

        LoginForm(
            serverUrl = serverUrl,
            username = username,
            password = password,
            isLoading = isLoading,
            errorMessage = errorMessage,
            showQuickConnect = showQuickConnect,
            isQuickConnectLoading = isQuickConnectLoading,
            quickConnectCode = quickConnectCode,
            onServerUrlChange = onServerUrlChange,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onLogin = onLogin,
            onQuickConnect = onQuickConnect
        )
    }
}

@Composable
private fun ConnectionForm(
    modifier: Modifier = Modifier,
    serverUrl: String,
    isLoading: Boolean,
    isImporting: Boolean,
    errorMessage: String?,
    onServerUrlChange: (String) -> Unit,
    onImportData: () -> Unit,
    onConnect: () -> Unit
) {
    val serverUrlBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.auth_connection_settings),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = {
                    Text(
                        stringResource(R.string.auth_server_url_placeholder),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(serverUrlBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch { serverUrlBringIntoView.bringIntoView() }
                        }
                    },
                enabled = !isLoading,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = amoledAuthFieldColors()
            )

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = androidx.compose.animation.fadeIn(animationSpec = tween(240)),
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(180))
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = JellyRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = onConnect,
                enabled = !isLoading && !isImporting && serverUrl.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JellyBlue,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF1E1E1E),
                    disabledContentColor = Color.White.copy(alpha = 0.4f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.auth_connect_to_server))
                }
            }

            OutlinedButton(
                onClick = onImportData,
                enabled = !isLoading && !isImporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.4f)
                )
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("数据导入")
                }
            }
        }
    }
}

@Composable
private fun LoginForm(
    serverUrl: String,
    username: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    showQuickConnect: Boolean,
    isQuickConnectLoading: Boolean,
    quickConnectCode: String?,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onQuickConnect: () -> Unit
) {
    val serverUrlBringIntoView = remember { BringIntoViewRequester() }
    val usernameBringIntoView = remember { BringIntoViewRequester() }
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    var isPasswordVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isBusy = isLoading || isQuickConnectLoading
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = {
                    Text(
                        stringResource(R.string.auth_server_url_placeholder),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(serverUrlBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch { serverUrlBringIntoView.bringIntoView() }
                        }
                    },
                enabled = !isBusy,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = amoledAuthFieldColors()
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.username)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(usernameBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch { usernameBringIntoView.bringIntoView() }
                        }
                    },
                enabled = !isBusy,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = amoledAuthFieldColors(hasLeadingIcon = true)
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null
                    )
                },
                visualTransformation = if (isPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (isPasswordVisible) {
                                stringResource(R.string.auth_hide_password)
                            } else {
                                stringResource(R.string.auth_show_password)
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(passwordBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch { passwordBringIntoView.bringIntoView() }
                        }
                    },
                enabled = !isBusy,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = amoledAuthFieldColors(hasLeadingIcon = true)
            )

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = androidx.compose.animation.fadeIn(animationSpec = tween(240)),
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(180))
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = JellyRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = onLogin,
                enabled = !isBusy && serverUrl.isNotBlank() && username.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JellyBlue,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF1E1E1E),
                    disabledContentColor = Color.White.copy(alpha = 0.4f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.auth_sign_in))
                }
            }

            if (showQuickConnect) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onQuickConnect,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isQuickConnectLoading && quickConnectCode == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = quickConnectCode?.let {
                                    stringResource(R.string.auth_quick_connect_code, it)
                                } ?: if (isQuickConnectLoading) {
                                    stringResource(R.string.auth_generating_code)
                                } else {
                                    stringResource(R.string.auth_quick_connect)
                                },
                                color = Color.White
                            )
                        }
                    }

                    if (quickConnectCode != null) {
                        Text(
                            text = stringResource(R.string.auth_quick_connect_approval_hint),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun cacheInitialImportUri(context: Context, uri: Uri): Uri {
    val appContext = context.applicationContext
    val importCacheDir = File(appContext.cacheDir, "initial-server-imports").apply { mkdirs() }
    importCacheDir.listFiles()?.forEach { file ->
        if (file.isFile && file.name.startsWith("grmemby-initial-import-")) {
            file.delete()
        }
    }
    val cachedFile = File(importCacheDir, "grmemby-initial-import-${System.currentTimeMillis()}.json")
    appContext.contentResolver.openInputStream(uri)?.use { input ->
        cachedFile.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取导入文件")
    return Uri.fromFile(cachedFile)
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun AuthScreenPreview() {
    AuthScreen(onAuthSuccess = {})
}

@Preview(showBackground = true)
@Composable
private fun ServerConnectionContentPreview() {
    ServerConnectionContent(
        modifier = Modifier,
        serverUrl = "https://media.example.test",
        isAwaitingSavedServers = false,
        isLoading = false,
        isImporting = false,
        errorMessage = null,
        onServerUrlChange = {},
        onImportData = {},
        onConnect = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun LoginContentPreview() {
    LoginContent(
        modifier = Modifier,
        serverUrl = "https://media.example.test",
        serverName = "Home Media Server",
        username = "john_doe",
        password = "",
        isLoading = false,
        errorMessage = null,
        showQuickConnect = true,
        isQuickConnectLoading = false,
        quickConnectCode = null,
        onServerUrlChange = {},
        onUsernameChange = {},
        onPasswordChange = {},
        onLogin = {},
        onQuickConnect = {}
    )
}
