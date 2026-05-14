package com.grmemby.app.ui.screens.auth

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grmemby.app.ui.screens.dashboard.home.CachedData
import com.grmemby.data.datastore.DataStoreProvider
import com.grmemby.data.repository.AuthRepository
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.MediaRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

data class ServerSwitchUiState(
    val isSwitching: Boolean = false,
    val isRemoving: Boolean = false,
    val isKeepingAccounts: Boolean = false,
    val keepAliveMessage: String? = null,
    val lastKeepAliveServerIds: Set<String> = emptySet()
) {
    val isBusy: Boolean get() = isSwitching || isRemoving
}

class ServerSwitchViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val LAST_KEEP_ALIVE_SERVER_IDS_KEY = stringPreferencesKey("last_keep_alive_server_ids_v1")
    }

    private val dataStore = DataStoreProvider.getDataStore(application)
    private val authRepository = AuthRepositoryProvider.getInstance(application)
    private val mediaRepository = MediaRepositoryProvider.getInstance(application)

    private val _uiState = MutableStateFlow(ServerSwitchUiState())
    val uiState: StateFlow<ServerSwitchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val selectedIds = dataStore.data.first()[LAST_KEEP_ALIVE_SERVER_IDS_KEY]
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            if (selectedIds.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(lastKeepAliveServerIds = selectedIds)
            }
        }
    }

    private suspend fun saveLastKeepAliveServerIds(serverIds: List<String>) {
        val normalizedIds = serverIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        dataStore.edit { preferences ->
            preferences[LAST_KEEP_ALIVE_SERVER_IDS_KEY] = normalizedIds.joinToString(separator = "\n")
        }
        _uiState.value = _uiState.value.copy(lastKeepAliveServerIds = normalizedIds.toSet())
    }

    fun switchServer(
        serverId: String,
        activeServerId: String?,
        onSwitchComplete: () -> Unit = {},
        onSwitchFailed: (Throwable) -> Unit = {}
    ) {
        if (serverId.isBlank()) return
        if (_uiState.value.isBusy) return
        if (activeServerId == serverId) {
            onSwitchComplete()
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSwitching = true
            )

            val switchResult = try {
                switchServerWithRetry(serverId = serverId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

            switchResult.fold(
                onSuccess = {
                    mediaRepository.invalidateSessionCaches()
                    mediaRepository.getUserViews()
                    CachedData.clearAllCache()
                    _uiState.value = ServerSwitchUiState(
                        lastKeepAliveServerIds = _uiState.value.lastKeepAliveServerIds
                    )
                    onSwitchComplete()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSwitching = false
                    )
                    onSwitchFailed(error)
                }
            )
        }
    }

    private suspend fun switchServerWithRetry(
        serverId: String,
        maxAttempts: Int = 2
    ): Result<AuthRepository.SavedServer> {
        var lastFailure: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            authRepository.savedServer()
            val result = authRepository.switchServer(serverId)
            if (result.isSuccess) return result

            lastFailure = result.exceptionOrNull()
            mediaRepository.invalidateSessionCaches(clearHomeSnapshot = false)
            if (attempt < maxAttempts - 1) {
                delay(180L)
            }
        }
        return Result.failure(lastFailure ?: Exception("Failed to switch server"))
    }

    fun keepAliveServers(
        servers: List<AuthRepository.SavedServer>,
        onStarted: () -> Unit = {},
        onFinished: (String) -> Unit = {}
    ) {
        val distinctServers = servers
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        if (distinctServers.isEmpty()) return
        if (_uiState.value.isKeepingAccounts) return

        viewModelScope.launch {
            saveLastKeepAliveServerIds(distinctServers.map { it.id })
            _uiState.value = _uiState.value.copy(
                isKeepingAccounts = true,
                keepAliveMessage = "正在保号 0/${distinctServers.size}：真实播放请求 1 分钟…"
            )
            onStarted()

            val completedCount = AtomicInteger(0)
            val results = mutableListOf<Pair<AuthRepository.SavedServer, Result<String>>>()
            val batchSize = 5
            distinctServers.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                val totalBatches = (distinctServers.size + batchSize - 1) / batchSize
                _uiState.value = _uiState.value.copy(
                    keepAliveMessage = "正在保号 ${completedCount.get()}/${distinctServers.size}：第 ${batchIndex + 1} 组/$totalBatches，${batch.size} 个服务器同步发起播放并保持 1 分钟…"
                )
                val batchResults = coroutineScope {
                    val deferredResults = batch.map { savedServer ->
                        async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            val result = try {
                                val perServerTimeoutMs = 180_000L
                                withTimeoutOrNull(perServerTimeoutMs) {
                                    simulateSavedServerPlaybackWithDnsRetry(savedServer)
                                } ?: Result.failure(Exception("Keepalive timed out after ${perServerTimeoutMs / 1000}s"))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            val finished = completedCount.incrementAndGet()
                            _uiState.value = _uiState.value.copy(
                                keepAliveMessage = "正在保号 $finished/${distinctServers.size}：第 ${batchIndex + 1} 组/$totalBatches，已完成 ${savedServer.serverName}"
                            )
                            savedServer to result
                        }
                    }
                    deferredResults.forEach { deferred -> deferred.start() }
                    deferredResults.awaitAll()
                }
                results += batchResults
            }

            results.forEach { (server, result) ->
                val serverHash = server.id.hashCode()
                result.fold(
                    onSuccess = {
                        Log.i("KeepAlive", "Server keepalive success serverHash=$serverHash")
                    },
                    onFailure = { error ->
                        Log.w(
                            "KeepAlive",
                            "Server keepalive failed serverHash=$serverHash reason=${error.message ?: error::class.java.simpleName}"
                        )
                    }
                )
            }

            val successCount = results.count { it.second.isSuccess }
            val failedCount = results.size - successCount
            // A failed keep-alive can still have opened a real playback session before the
            // server rejected/failed verification. Always drop local home snapshots after
            // the batch so stale Continue Watching rows from the probe are not kept on 首页.
            mediaRepository.invalidateSessionCaches(clearHomeSnapshot = true)
            CachedData.clearAllCache()
            val failureSummary = results
                .filter { it.second.isFailure }
                .take(3)
                .joinToString(separator = "；") { (server, result) ->
                    "${server.serverName}：${result.exceptionOrNull()?.keepAliveUserMessage().orEmpty()}"
                }
            val message = if (failedCount == 0) {
                "保号完成：$successCount 个服务器已完成真实播放请求 1 分钟，并已后台清理继续观看"
            } else if (failureSummary.isNotBlank()) {
                "保号完成：$successCount 个成功，$failedCount 个失败；失败原因：$failureSummary"
            } else {
                "保号完成：$successCount 个成功，$failedCount 个失败；成功项已后台清理继续观看"
            }
            _uiState.value = _uiState.value.copy(
                isKeepingAccounts = false,
                keepAliveMessage = message
            )
            onFinished(message)
        }
    }

    private suspend fun simulateSavedServerPlaybackWithDnsRetry(
        savedServer: AuthRepository.SavedServer,
        maxAttempts: Int = 3
    ): Result<String> {
        var lastResult: Result<String> = Result.failure(Exception("Keepalive not started"))
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val result = mediaRepository.simulateSavedServerPlayback(savedServer)
            if (result.isSuccess) return result
            lastResult = result
            val error = result.exceptionOrNull()
            if (!isKeepAliveDnsResolutionFailure(error) || attempt >= maxAttempts - 1) {
                return result
            }
            val backoffMs = 700L * (attempt + 1)
            Log.w(
                "KeepAlive",
                "DNS resolution failed; retrying serverHash=${savedServer.id.hashCode()} attempt=${attempt + 1}/$maxAttempts delayMs=$backoffMs"
            )
            delay(backoffMs)
        }
        return lastResult
    }

    fun clearKeepAliveMessage() {
        _uiState.value = _uiState.value.copy(keepAliveMessage = null)
    }

    private fun Throwable.keepAliveUserMessage(): String = keepAliveErrorUserMessage(this)

    fun removeServer(
        serverId: String,
        onRemoveComplete: () -> Unit = {},
        onRemoveFailed: (Throwable) -> Unit = {}
    ) {
        if (serverId.isBlank()) return
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRemoving = true
            )

            val removeResult = try {
                authRepository.savedServer()
                authRepository.removeSavedServer(serverId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

            removeResult.fold(
                onSuccess = {
                    _uiState.value = ServerSwitchUiState(
                        lastKeepAliveServerIds = _uiState.value.lastKeepAliveServerIds
                    )
                    onRemoveComplete()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isRemoving = false
                    )
                    onRemoveFailed(error)
                }
            )
        }
    }
}


internal fun keepAliveErrorUserMessage(error: Throwable): String {
    val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val cleaned = raw
        .replace(Regex("(?i)(api[_-]?key|access[_-]?token|token)=([^&\\s]+)")) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        .replace(Regex("(?i)(X-Emby-Token|X-MediaBrowser-Token)[:=]\\s*[^,;\\s]+")) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
    return when {
        isKeepAliveDnsResolutionFailure(error) -> "DNS 解析失败，请检查当前网络/私人 DNS/线路域名"
        cleaned.contains("non-media", ignoreCase = true) -> "播放流返回非媒体内容"
        cleaned.contains("no media bytes", ignoreCase = true) -> "播放流没有返回媒体数据"
        cleaned.contains("Playback stream request failed", ignoreCase = true) -> cleaned.replace("Playback stream request failed", "播放流请求失败")
        cleaned.contains("PlaybackInfo", ignoreCase = true) -> cleaned.replace("PlaybackInfo", "播放信息")
        cleaned.contains("Continue Watching", ignoreCase = true) -> "真实播放已发出，但继续观看清理未验证通过"
        cleaned.contains("Saved session expired", ignoreCase = true) -> "登录已过期"
        cleaned.contains("No playable", ignoreCase = true) -> "没有找到可播放保号资源"
        else -> cleaned.take(96)
    }
}

internal fun isKeepAliveDnsResolutionFailure(error: Throwable?): Boolean {
    var current = error
    repeat(6) {
        if (current == null) return false
        if (current is java.net.UnknownHostException) return true
        val text = current?.message.orEmpty()
        if (text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("No address associated with hostname", ignoreCase = true) ||
            text.contains("UnknownHost", ignoreCase = true)
        ) {
            return true
        }
        current = current?.cause
    }
    return false
}
