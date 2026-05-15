package com.grmemby.app.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.grmemby.app.R
import com.grmemby.app.ui.components.common.AmoledDialogFrame
import com.grmemby.data.network.canonicalServerUrlKey
import com.grmemby.data.repository.AuthRepository
import kotlinx.coroutines.launch

private fun AuthRepository.SavedServer.isActiveServer(activeServerId: String?): Boolean {
    return id == activeServerId
}

@Stable
internal class ServerSwitchDialogsState {
    var showServerSwitchDialog by mutableStateOf(false)
        private set
    var showUserSwitchDialog by mutableStateOf(false)
        private set
    var userSwitchUsers by mutableStateOf<List<AuthRepository.SavedServer>>(emptyList())
        private set
    var userSwitchServerName by mutableStateOf<String?>(null)
        private set
    var userSwitchServerUrl by mutableStateOf<String?>(null)
        private set
    var serverPendingRemoval by mutableStateOf<AuthRepository.SavedServer?>(null)
        private set
    var showKeepAliveDialog by mutableStateOf(false)
        private set
    var keepAliveCandidates by mutableStateOf<List<AuthRepository.SavedServer>>(emptyList())
        private set

    fun openServers() {
        showServerSwitchDialog = true
    }

    fun dismissServers() {
        showServerSwitchDialog = false
    }

    fun openUsers(serverName: String?, users: List<AuthRepository.SavedServer>) {
        showServerSwitchDialog = false
        userSwitchServerName = serverName
        userSwitchServerUrl = users.firstOrNull()?.serverUrl
        userSwitchUsers = users
        showUserSwitchDialog = true
    }

    fun dismissUsers() {
        showUserSwitchDialog = false
        userSwitchUsers = emptyList()
        userSwitchServerName = null
        userSwitchServerUrl = null
    }

    fun returnToServers() {
        showUserSwitchDialog = false
        userSwitchUsers = emptyList()
        userSwitchServerName = null
        userSwitchServerUrl = null
        showServerSwitchDialog = true
    }

    fun requestRemoval(server: AuthRepository.SavedServer) {
        serverPendingRemoval = server
    }

    fun openKeepAlive(candidates: List<AuthRepository.SavedServer>) {
        keepAliveCandidates = candidates.distinctBy { it.id }
        showKeepAliveDialog = keepAliveCandidates.isNotEmpty()
    }

    fun dismissKeepAlive() {
        showKeepAliveDialog = false
        keepAliveCandidates = emptyList()
    }

    fun clearRemoval() {
        serverPendingRemoval = null
    }
}

@Composable
internal fun rememberServerSwitchDialogsState(): ServerSwitchDialogsState {
    return remember { ServerSwitchDialogsState() }
}

@Composable
internal fun ServerSwitchDialogsHost(
    state: ServerSwitchDialogsState,
    savedServers: List<AuthRepository.SavedServer>,
    activeServerId: String?,
    currentServerName: String?,
    currentServerUrl: String?,
    isSwitching: Boolean,
    isKeepAliveRunning: Boolean = false,
    keepAliveMessage: String? = null,
    lastKeepAliveServerIds: Set<String> = emptySet(),
    onKeepAliveMessageDismiss: () -> Unit = {},
    onAddServer: () -> Unit,
    onAddUser: (serverUrl: String, serverName: String?) -> Unit,
    onEditUser: (serverUrl: String, serverName: String?, username: String?) -> Unit = { serverUrl, serverName, _ ->
        onAddUser(serverUrl, serverName)
    },
    onServerSelected: (AuthRepository.SavedServer, () -> Unit) -> Unit,
    onKeepAliveServers: (List<AuthRepository.SavedServer>) -> Unit = {},
    onRequestRemoveServer: (AuthRepository.SavedServer) -> Unit = {},
    onRequestRemoveUser: (AuthRepository.SavedServer) -> Unit = {},
    onRemoveServer: ((String, () -> Unit) -> Unit)? = null,
    showRemoveAction: Boolean = true,
    showEditAction: Boolean = true,
    showKeepAliveAction: Boolean = true,
    showAddServerAction: Boolean = true,
    showAddUserAction: Boolean = true,
    dismissServerDialogOnRequest: Boolean = true,
    dismissUserDialogOnRequest: Boolean = true,
    showServerCloseAction: Boolean = true,
    useLightGlass: Boolean? = null,
    onServerDialogDismiss: (() -> Unit)? = null,
    onUserDialogDismiss: (() -> Unit)? = null
) {
    val dismissServers = onServerDialogDismiss ?: state::dismissServers
    val dismissUsers = onUserDialogDismiss ?: state::dismissUsers

    if (state.showServerSwitchDialog) {
        ServerSwitchDialog(
            servers = savedServers,
            activeServerId = activeServerId,
            isSwitching = isSwitching,
            showRemoveAction = showRemoveAction,
            showEditAction = showEditAction,
            showKeepAliveAction = showKeepAliveAction,
            showAddServerAction = showAddServerAction,
            dismissOnRequest = dismissServerDialogOnRequest,
            showCloseAction = showServerCloseAction,
            useLightGlass = useLightGlass,
            onDismiss = dismissServers,
            onKeepAliveClick = state::openKeepAlive,
            onAddServer = {
                state.dismissServers()
                onAddServer()
            },
            onRequestRemoveServer = onRequestRemoveServer,
            onEditServer = { server ->
                state.dismissServers()
                onEditUser(server.serverUrl, server.serverName, server.username)
            },
            onOpenServerUsers = { serverName, users ->
                state.openUsers(serverName, users)
            },
            onServerSelected = { server ->
                onServerSelected(server, state::dismissServers)
            }
        )
    }

    if (state.showUserSwitchDialog) {
        UserSwitchDialog(
            users = state.userSwitchUsers,
            activeServerId = activeServerId,
            serverName = state.userSwitchServerName,
            isSwitching = isSwitching,
            showRemoveAction = showRemoveAction,
            showEditAction = showEditAction,
            showAddUserAction = showAddUserAction,
            dismissOnRequest = dismissUserDialogOnRequest,
            useLightGlass = useLightGlass,
            onDismiss = dismissUsers,
            onAddUser = {
                val targetServerUrl = state.userSwitchServerUrl ?: currentServerUrl
                targetServerUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { serverUrl ->
                        state.dismissUsers()
                        onAddUser(serverUrl, state.userSwitchServerName ?: currentServerName)
                    }
            },
            onEditUser = { server ->
                state.dismissUsers()
                onEditUser(server.serverUrl, server.serverName, server.username)
            },
            onRequestRemoveUser = onRequestRemoveUser,
            onUserSelected = { server ->
                onServerSelected(server, state::dismissUsers)
            }
        )
    }

    if (state.showKeepAliveDialog) {
        KeepAliveServerSelectDialog(
            candidates = state.keepAliveCandidates,
            lastSelectedServerIds = lastKeepAliveServerIds,
            isRunning = isKeepAliveRunning,
            onDismiss = state::dismissKeepAlive,
            onConfirm = { selectedServers ->
                onKeepAliveServers(selectedServers)
                state.dismissKeepAlive()
            }
        )
    }

    if (showRemoveAction && onRemoveServer != null) {
        state.serverPendingRemoval?.let { server ->
            RemoveServerConfirmDialog(
                server = server,
                isRemoving = isSwitching,
                onDismiss = {
                    if (!isSwitching) {
                        state.clearRemoval()
                    }
                },
                onConfirm = {
                    onRemoveServer(server.id, state::clearRemoval)
                }
            )
        }
    }
}

@Composable
internal fun ProfileImageLoader(
    imageUrl: String?,
    serverTypeRaw: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasError by remember { mutableStateOf(false) }
    val profileRequest = remember(imageUrl) {
        imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(300)
                .build()
        }
    }

    LaunchedEffect(profileRequest) {
        hasError = false
    }
    val avatarImage = profileRequest == null || hasError
    val placeholderResId = remember(serverTypeRaw) {
        when {
            serverTypeRaw.equals("EMBY", ignoreCase = true) -> R.drawable.ic_emby_placeholder
            else -> R.drawable.ic_jellyfin_placeholder
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (avatarImage) {
            Image(
                painter = painterResource(id = placeholderResId),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.62f)
            )
        } else {
            AsyncImage(
                model = profileRequest,
                contentDescription = stringResource(R.string.settings_profile_picture),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                onError = {
                    hasError = true
                }
            )
        }
    }
}

@Composable
internal fun ServerSwitchDialog(
    servers: List<AuthRepository.SavedServer>,
    activeServerId: String?,
    isSwitching: Boolean,
    showRemoveAction: Boolean = true,
    showEditAction: Boolean = true,
    showKeepAliveAction: Boolean = true,
    showAddServerAction: Boolean = true,
    dismissOnRequest: Boolean = true,
    showCloseAction: Boolean = true,
    useLightGlass: Boolean? = null,
    onDismiss: () -> Unit,
    onKeepAliveClick: (List<AuthRepository.SavedServer>) -> Unit,
    onAddServer: () -> Unit,
    onRequestRemoveServer: (AuthRepository.SavedServer) -> Unit,
    onEditServer: (AuthRepository.SavedServer) -> Unit,
    onOpenServerUsers: (String, List<AuthRepository.SavedServer>) -> Unit,
    onServerSelected: (AuthRepository.SavedServer) -> Unit
) {
    val serverGroups = remember(servers, activeServerId) {
        servers
            .groupBy { canonicalServerUrlKey(it.serverUrl) }
            .map { (_, groupedUsers) ->
                val sortedUsers = groupedUsers.sortedWith(
                    compareByDescending<AuthRepository.SavedServer> {
                        if (it.isActiveServer(activeServerId)) 1 else 0
                    }.thenBy { it.username.lowercase() }
                )
                val activeUser = sortedUsers.firstOrNull { it.isActiveServer(activeServerId) }
                val primary = activeUser ?: sortedUsers.first()
                ServerGroupUiModel(
                    serverName = primary.serverName,
                    serverUrl = primary.serverUrl,
                    users = sortedUsers,
                    activeUser = activeUser
                )
            }
            .sortedWith(
                compareByDescending<ServerGroupUiModel> { if (it.activeUser != null) 1 else 0 }
                    .thenBy { it.serverName.lowercase() }
            )
    }
    val keepAliveCandidates = remember(serverGroups) {
        serverGroups.mapNotNull { group -> group.activeUser ?: group.users.firstOrNull() }
    }

    AmoledDialogFrame(
        dismissOnRequest = dismissOnRequest,
        onDismiss = onDismiss,
        lightGlass = useLightGlass ?: !isSystemInDarkTheme()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.settings_switch_server),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (serverGroups.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_saved_servers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    serverGroups.forEachIndexed { index, group ->
                        val hasMultipleUsers = group.users.size > 1
                        val singleUser = group.users.firstOrNull()
                        val clickGroup = !isSwitching && (hasMultipleUsers || singleUser != null)
                        val displayServer = group.activeUser ?: singleUser

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = clickGroup) {
                                    if (hasMultipleUsers) {
                                        onOpenServerUsers(group.serverName, group.users)
                                    } else {
                                        singleUser?.let(onServerSelected)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileImageLoader(
                                imageUrl = displayServer?.serverLogoUrl,
                                serverTypeRaw = displayServer?.serverTypeRaw,
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.serverName.ifBlank { stringResource(R.string.settings_media_server) },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (group.activeUser != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (group.activeUser != null) stringResource(R.string.settings_active_server) else stringResource(R.string.settings_tap_to_switch),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (hasMultipleUsers) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.settings_saved_users_count,
                                            group.users.size,
                                            group.users.size
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                                    )
                                }
                            }
                            val editableUser = group.activeUser ?: group.users.firstOrNull()
                            if (showEditAction && editableUser != null) {
                                TextButton(
                                    enabled = !isSwitching,
                                    onClick = { onEditServer(editableUser) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = stringResource(R.string.edit),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.edit),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            when {
                                isSwitching && group.activeUser != null -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                hasMultipleUsers -> {
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = stringResource(R.string.settings_change_user),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                singleUser?.isActiveServer(activeServerId) == true -> {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = stringResource(R.string.settings_active_server),
                                        tint = Color(0xFF4FD06B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                else -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (showRemoveAction && singleUser != null) {
                                            IconButton(
                                                enabled = !isSwitching,
                                                onClick = { onRequestRemoveServer(singleUser) }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Delete,
                                                    contentDescription = stringResource(R.string.settings_remove_user),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (showCloseAction || showKeepAliveAction || showAddServerAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (showCloseAction) Arrangement.SpaceBetween else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showCloseAction) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showKeepAliveAction) {
                            TextButton(
                                enabled = !isSwitching && keepAliveCandidates.isNotEmpty(),
                                onClick = { onKeepAliveClick(keepAliveCandidates) }
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_keep_accounts),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (showAddServerAction) {
                            TextButton(
                                enabled = !isSwitching,
                                onClick = onAddServer
                            ) {
                                Text(stringResource(R.string.settings_add_server), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ServerGroupUiModel(
    val serverName: String,
    val serverUrl: String,
    val users: List<AuthRepository.SavedServer>,
    val activeUser: AuthRepository.SavedServer?
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserSwitchDialog(
    users: List<AuthRepository.SavedServer>,
    activeServerId: String?,
    serverName: String?,
    isSwitching: Boolean,
    showRemoveAction: Boolean = true,
    showEditAction: Boolean = true,
    showAddUserAction: Boolean = true,
    dismissOnRequest: Boolean = true,
    useLightGlass: Boolean? = null,
    onDismiss: () -> Unit,
    onAddUser: () -> Unit,
    onEditUser: (AuthRepository.SavedServer) -> Unit,
    onRequestRemoveUser: (AuthRepository.SavedServer) -> Unit,
    onUserSelected: (AuthRepository.SavedServer) -> Unit
) {
    AmoledDialogFrame(
        dismissOnRequest = dismissOnRequest,
        onDismiss = onDismiss,
        lightGlass = useLightGlass ?: !isSystemInDarkTheme()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = serverName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.settings_whos_watching),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(28.dp))

            if (users.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_saved_users_for_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compactLayout = maxWidth < 640.dp
                    val activeUserIndex = remember(users, activeServerId) {
                        users.indexOfFirst { it.isActiveServer(activeServerId) }.coerceAtLeast(0)
                    }

                    if (compactLayout) {
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        val showLeftIndicator by remember(listState) {
                            derivedStateOf { listState.canScrollBackward }
                        }
                        val showRightIndicator by remember(listState) {
                            derivedStateOf { listState.canScrollForward }
                        }

                        LaunchedEffect(users, activeUserIndex) {
                            if (users.isNotEmpty()) {
                                listState.scrollToItem(activeUserIndex)
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            LazyRow(
                                state = listState,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                items(items = users, key = { it.id }) { user ->
                                    WhoWatchingUserCard(
                                        user = user,
                                        activeServerId = activeServerId,
                                        isSwitching = isSwitching,
                                        showRemoveAction = showRemoveAction,
                                        showEditAction = showEditAction,
                                        onUserSelected = onUserSelected,
                                        onEditUser = onEditUser,
                                        onRequestRemoveUser = onRequestRemoveUser,
                                        modifier = Modifier.width(104.dp)
                                    )
                                }
                            }

                            if (showLeftIndicator) {
                                WhoWatchingScrollIndicator(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 4.dp),
                                    rotateDegrees = 180f,
                                    onClick = {
                                        val targetIndex =
                                            (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                        scope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                        }
                                    }
                                )
                            }

                            if (showRightIndicator) {
                                WhoWatchingScrollIndicator(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 4.dp),
                                    onClick = {
                                        val targetIndex =
                                            (listState.firstVisibleItemIndex + 1)
                                                .coerceAtMost((users.lastIndex).coerceAtLeast(0))
                                        scope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        val maxItemsPerRow = if (maxWidth >= 920.dp) 5 else 4

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                space = 28.dp,
                                alignment = Alignment.CenterHorizontally
                            ),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            maxItemsInEachRow = maxItemsPerRow
                        ) {
                            users.forEach { user ->
                                WhoWatchingUserCard(
                                    user = user,
                                    activeServerId = activeServerId,
                                    isSwitching = isSwitching,
                                    showRemoveAction = showRemoveAction,
                                    showEditAction = showEditAction,
                                    onUserSelected = onUserSelected,
                                    onEditUser = onEditUser,
                                    onRequestRemoveUser = onRequestRemoveUser,
                                    modifier = Modifier.width(112.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (showAddUserAction) {
                OutlinedButton(
                enabled = !isSwitching,
                onClick = onAddUser,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAddAlt1,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_add_user),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            }
        }
    }
}

@Composable
private fun WhoWatchingScrollIndicator(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotateDegrees: Float = 0f
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                shape = CircleShape
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotateDegrees)
        )
    }
}

@Composable
private fun WhoWatchingUserCard(
    user: AuthRepository.SavedServer,
    activeServerId: String?,
    isSwitching: Boolean,
    showRemoveAction: Boolean,
    showEditAction: Boolean,
    onUserSelected: (AuthRepository.SavedServer) -> Unit,
    onEditUser: (AuthRepository.SavedServer) -> Unit,
    onRequestRemoveUser: (AuthRepository.SavedServer) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActiveUser = user.isActiveServer(activeServerId)
    val canSelect = !isSwitching
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.clickable(
            enabled = canSelect,
            indication = null,
            interactionSource = interactionSource
        ) { onUserSelected(user) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ProfileImageLoader(
                    imageUrl = user.profileImageUrl,
                    serverTypeRaw = user.serverTypeRaw,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }

            if (showRemoveAction && !isActiveUser) {
                FilledIconButton(
                    enabled = !isSwitching,
                    onClick = { onRequestRemoveUser(user) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                        disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.40f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.settings_remove_user),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (showEditAction) {
                FilledIconButton(
                enabled = !isSwitching,
                onClick = { onEditUser(user) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit),
                    modifier = Modifier.size(14.dp)
                )
            }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = user.username.ifBlank { stringResource(R.string.settings_unknown_username) },
            style = MaterialTheme.typography.titleSmall,
            color = if (isActiveUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier.height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isSwitching && isActiveUser -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                isActiveUser -> {
                    Text(
                        text = stringResource(R.string.settings_watching),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun KeepAliveServerSelectDialog(
    candidates: List<AuthRepository.SavedServer>,
    lastSelectedServerIds: Set<String>,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<AuthRepository.SavedServer>) -> Unit
) {
    var selectedIds by remember(candidates, lastSelectedServerIds) {
        val candidateIds = candidates.map { it.id }.toSet()
        val rememberedIds = lastSelectedServerIds.intersect(candidateIds)
        mutableStateOf(rememberedIds.takeIf { it.isNotEmpty() } ?: candidateIds)
    }
    val selectedServers = candidates.filter { it.id in selectedIds }

    AlertDialog(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.settings_keep_accounts_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_keep_accounts_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    candidates.forEach { server ->
                        val checked = server.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !isRunning) {
                                    selectedIds = if (checked) {
                                        selectedIds - server.id
                                    } else {
                                        selectedIds + server.id
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = !isRunning,
                                onCheckedChange = { isChecked ->
                                    selectedIds = if (isChecked) {
                                        selectedIds + server.id
                                    } else {
                                        selectedIds - server.id
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.serverName.ifBlank { stringResource(R.string.settings_media_server) },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = server.username.ifBlank { stringResource(R.string.settings_unknown_username) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (isRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_keep_accounts_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isRunning && selectedServers.isNotEmpty(),
                onClick = { onConfirm(selectedServers) }
            ) {
                Text(
                    text = stringResource(R.string.ok),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isRunning,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun KeepAliveStatusDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.settings_keep_accounts), fontWeight = FontWeight.Bold) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun RemoveServerConfirmDialog(
    server: AuthRepository.SavedServer,
    isRemoving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.settings_remove_saved_account), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = stringResource(
                    R.string.settings_remove_saved_account_message,
                    server.username,
                    server.serverName
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isRemoving,
                onClick = onConfirm
            ) {
                if (isRemoving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isRemoving,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
