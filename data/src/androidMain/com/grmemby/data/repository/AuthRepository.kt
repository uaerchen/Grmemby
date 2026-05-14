package com.grmemby.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grmemby.data.datastore.DataStoreProvider
import com.grmemby.data.model.AuthenticationRequest
import com.grmemby.data.model.AuthenticationResult
import com.grmemby.data.model.QuickConnectDto
import com.grmemby.data.model.QuickConnectResult
import com.grmemby.data.model.ServerInfo
import com.grmemby.data.network.ServerEndpoint
import com.grmemby.data.network.ServerType
import com.grmemby.data.network.canonicalServerUrl
import com.grmemby.data.network.canonicalServerUrlWithDefaultPort
import com.grmemby.data.network.NetworkModule
import com.grmemby.data.preferences.NetworkPreferences
import com.grmemby.data.security.AuthSessionIds
import com.grmemby.data.security.LEGACY_ACCESS_TOKEN_KEY
import com.grmemby.data.security.SecureSessionStore
import com.grmemby.data.network.GrmembyJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

class AuthRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences> = DataStoreProvider.getDataStore(context)
    private val networkPreferences = NetworkPreferences(context)
    private val secureSessionStore = SecureSessionStore(context)
    private val legacyMigrationMutex = Mutex()

    @Volatile
    private var migrationExecuted = false

    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val SERVER_NAME_KEY = stringPreferencesKey("server_name")
        private val SERVER_TYPE_KEY = stringPreferencesKey("server_type")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val IS_AUTHENTICATED_KEY = booleanPreferencesKey("is_authenticated")
        private val SAVED_SERVERS_KEY = stringPreferencesKey("saved_servers_v1")
        private val ACTIVE_SERVER_ID_KEY = stringPreferencesKey("active_server_id")
    }

    @Serializable
    data class SavedServer(
        @SerialName("id")
        val id: String,
        @SerialName("serverUrl")
        val serverUrl: String,
        @SerialName("serverName")
        val serverName: String,
        @SerialName("serverTypeRaw")
        val serverTypeRaw: String,
        @SerialName("username")
        val username: String,
        @SerialName("userId")
        val userId: String,
        @SerialName("profileImageUrl")
        val profileImageUrl: String? = null,
        @SerialName("serverLogoUrl")
        val serverLogoUrl: String? = null,
        @SerialName("serverRemark")
        val serverRemark: String? = null,
        @SerialName("serverLines")
        val serverLines: List<ServerLine> = emptyList(),
        @SerialName("activeLineId")
        val activeLineId: String? = null,
        @SerialName("lastUsedAt")
        val lastUsedAt: Long
    ) {
        val activeLine: ServerLine?
            get() = activeLineId?.let { lineId -> serverLines.firstOrNull { it.id == lineId } }

        val effectiveServerUrl: String
            get() = activeLine?.effectiveUrl(serverUrl) ?: serverUrl

        fun lineLabel(): String = activeLine?.let { line ->
            when {
                line.isReverseProxy -> "反代线路"
                else -> line.name.takeIf { it.isNotBlank() } ?: "备用线路"
            }
        } ?: "主线路"
    }

    @Serializable
    data class ServerLine(
        @SerialName("id")
        val id: String,
        @SerialName("name")
        val name: String = "",
        @SerialName("url")
        val url: String,
        @SerialName("isReverseProxy")
        val isReverseProxy: Boolean = false,
        @SerialName("createdAt")
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun effectiveUrl(primaryServerUrl: String): String {
            val normalizedLineUrl = canonicalServerUrlWithDefaultPort(url)
            if (!isReverseProxy) return normalizedLineUrl
            val encodedPrimary = URLEncoder.encode(canonicalServerUrlWithDefaultPort(primaryServerUrl), "UTF-8")
            return normalizedLineUrl.trimEnd('/') + "/" + encodedPrimary
        }
    }

    @Serializable
    data class ImportedServerCandidate(
        @SerialName("serverUrl")
        val serverUrl: String,
        @SerialName("serverName")
        val serverName: String,
        @SerialName("serverTypeRaw")
        val serverTypeRaw: String = ServerType.EMBY.name,
        @SerialName("username")
        val username: String = "",
        @SerialName("userId")
        val userId: String = "",
        @SerialName("profileImageUrl")
        val profileImageUrl: String? = null,
        @SerialName("serverLogoUrl")
        val serverLogoUrl: String? = null,
        @SerialName("serverRemark")
        val serverRemark: String? = null,
        @SerialName("serverLines")
        val serverLines: List<ServerLine> = emptyList(),
        @SerialName("activeLineId")
        val activeLineId: String? = null,
        @SerialName("accessToken")
        val accessToken: String? = null,
        @SerialName("source")
        val source: String? = null,
        @SerialName("lastUsedAt")
        val lastUsedAt: Long = System.currentTimeMillis()
    )

    @Serializable
    private data class StoredSavedServer(
        @SerialName("id")
        val id: String,
        @SerialName("serverUrl")
        val serverUrl: String,
        @SerialName("serverName")
        val serverName: String,
        @SerialName("serverTypeRaw")
        val serverTypeRaw: String,
        @SerialName("username")
        val username: String,
        @SerialName("userId")
        val userId: String,
        @SerialName("profileImageUrl")
        val profileImageUrl: String? = null,
        @SerialName("serverLogoUrl")
        val serverLogoUrl: String? = null,
        @SerialName("serverRemark")
        val serverRemark: String? = null,
        @SerialName("serverLines")
        val serverLines: List<ServerLine> = emptyList(),
        @SerialName("activeLineId")
        val activeLineId: String? = null,
        @SerialName("lastUsedAt")
        val lastUsedAt: Long,
        @SerialName("accessToken")
        val accessToken: String? = null
    )

    data class ActiveSessionSnapshot(
        val serverName: String?,
        val serverUrl: String?,
        val serverType: String?,
        val username: String?,
        val savedServers: List<SavedServer>,
        val activeServerId: String?
    )

    private fun defaultServerName(serverType: ServerType): String {
        return when (serverType) {
            ServerType.EMBY -> "Emby Server"
            ServerType.JELLYFIN -> "Jellyfin Server"
            ServerType.UNKNOWN -> "Media Server"
        }
    }

    private fun serverName(
        serverInfo: ServerInfo,
        serverType: ServerType
    ): String {
        return serverInfo.serverName
            ?.takeIf { it.isNotBlank() }
            ?: serverInfo.productName?.takeIf { it.isNotBlank() }
            ?: defaultServerName(serverType)
    }

    private fun buildServerId(serverUrl: String, userId: String): String {
        return AuthSessionIds.buildServerId(serverUrl, userId)
    }

    private fun currentServerId(preferences: Preferences): String? {
        val explicitId = preferences[ACTIVE_SERVER_ID_KEY]?.takeIf { it.isNotBlank() }
        if (explicitId != null) return explicitId

        val serverUrl = preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val userId = preferences[USER_ID_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return buildServerId(serverUrl = serverUrl, userId = userId)
    }

    private fun persistedSavedServers(raw: String?): List<StoredSavedServer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            GrmembyJson.decodeFromString<List<StoredSavedServer>>(raw)
                ?.filter {
                    it.id.isNotBlank() &&
                        it.serverUrl.isNotBlank() &&
                        it.userId.isNotBlank()
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun savedServers(raw: String?): List<SavedServer> {
        return persistedSavedServers(raw)
            .mapNotNull { storedServer -> storedServer.toSavedServerOrNull() }
    }

    private fun serializeSavedServers(savedServers: List<SavedServer>): String {
        return GrmembyJson.encodeToString(savedServers)
    }

    private fun upsertSavedServer(
        existing: List<SavedServer>,
        incoming: SavedServer
    ): List<SavedServer> {
        val withoutMatch = existing.filterNot { it.id == incoming.id }
        return (withoutMatch + incoming)
            .sortedByDescending { it.lastUsedAt }
    }

    private fun activeServer(preferences: Preferences): SavedServer? {
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val explicitServerId = preferences[ACTIVE_SERVER_ID_KEY]?.takeIf { it.isNotBlank() }
        if (explicitServerId != null) {
            if (!secureSessionStore.hasToken(explicitServerId)) return null
            storedServers.firstOrNull { savedServer -> savedServer.id == explicitServerId }?.let { storedServer ->
                return storedServer.copy(lastUsedAt = System.currentTimeMillis())
            }
        }

        val serverUrl = preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val userId = preferences[USER_ID_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val serverId = buildServerId(serverUrl = serverUrl, userId = userId)
        if (!secureSessionStore.hasToken(serverId)) return null
        val existingSavedServer = storedServers.firstOrNull { savedServer -> savedServer.id == serverId }
        val serverTypeRaw = preferences[SERVER_TYPE_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: existingSavedServer?.serverTypeRaw
            ?: ServerType.UNKNOWN.name
        val serverType = runCatching { ServerType.valueOf(serverTypeRaw) }
            .getOrDefault(ServerType.UNKNOWN)
        val serverName = preferences[SERVER_NAME_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: existingSavedServer?.serverName
            ?: defaultServerName(serverType)
        val username = preferences[USERNAME_KEY].orEmpty().ifBlank { existingSavedServer?.username.orEmpty() }

        return SavedServer(
            id = serverId,
            serverUrl = existingSavedServer?.serverUrl ?: serverUrl,
            serverName = serverName,
            serverTypeRaw = serverTypeRaw,
            username = username,
            userId = userId,
            profileImageUrl = existingSavedServer?.profileImageUrl,
            serverLogoUrl = existingSavedServer?.serverLogoUrl,
            serverRemark = existingSavedServer?.serverRemark,
            serverLines = existingSavedServer?.serverLines.orEmpty(),
            activeLineId = existingSavedServer?.activeLineId,
            lastUsedAt = System.currentTimeMillis()
        )
    }

    val isAuthenticated: Flow<Boolean> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        (preferences[IS_AUTHENTICATED_KEY] ?: false) &&
            secureSessionStore.hasToken(currentServerId(preferences))
    }

    fun getServerUrl(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]
    }

    fun getServerName(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_NAME_KEY]
    }

    fun getServerType(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_TYPE_KEY]
    }

    fun getUsername(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    fun observeActiveSession(): Flow<ActiveSessionSnapshot> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val activeServer = activeServer(preferences)
        val currentSavedServers = if (activeServer != null && storedServers.none { it.id == activeServer.id }) {
            upsertSavedServer(storedServers, activeServer)
        } else {
            storedServers.sortedByDescending { it.lastUsedAt }
        }
        val selectedServerId = preferences[ACTIVE_SERVER_ID_KEY]
            ?.takeIf { candidateId ->
                candidateId.isNotBlank() && currentSavedServers.any { savedServer -> savedServer.id == candidateId }
            }
            ?: activeServer?.id
        val resolvedActiveServer = selectedServerId
            ?.let { candidateId ->
                currentSavedServers.firstOrNull { savedServer -> savedServer.id == candidateId }
            }
            ?: activeServer

        ActiveSessionSnapshot(
            serverName = preferences[SERVER_NAME_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.serverName,
            serverUrl = resolvedActiveServer?.serverUrl
                ?: preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() },
            serverType = preferences[SERVER_TYPE_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.serverTypeRaw,
            username = preferences[USERNAME_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: resolvedActiveServer?.username,
            savedServers = currentSavedServers,
            activeServerId = selectedServerId
        )
    }

    fun getActiveSessionSnapshot(): ActiveSessionSnapshot {
        return runCatching {
            runBlocking { observeActiveSession().first() }
        }.getOrElse {
            ActiveSessionSnapshot(
                serverName = null,
                serverUrl = null,
                serverType = null,
                username = null,
                savedServers = emptyList(),
                activeServerId = null
            )
        }
    }

    fun getAccessToken(): Flow<String?> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        currentServerId(preferences)?.let(secureSessionStore::getToken)
    }

    fun getSavedServers(): Flow<List<SavedServer>> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val activeServer = activeServer(preferences)
        val currentSavedServers = if (activeServer != null && storedServers.none { it.id == activeServer.id }) {
            upsertSavedServer(storedServers, activeServer)
        } else {
            storedServers.sortedByDescending { it.lastUsedAt }
        }
        currentSavedServers
    }

    fun getActiveServerId(): Flow<String?> = dataStore.data.map { preferences ->
        legacyStorageMigrated()
        activeServer(preferences)?.id
            ?: preferences[ACTIVE_SERVER_ID_KEY]
                ?.takeIf { candidateId ->
                    candidateId.isNotBlank() && savedServers(preferences[SAVED_SERVERS_KEY]).any { it.id == candidateId }
                }
    }

    fun hasSavedSession(serverId: String?): Boolean = secureSessionStore.hasToken(serverId)

    suspend fun exportSavedServerCandidates(
        includeRemarks: Boolean,
        includeCredentials: Boolean
    ): List<ImportedServerCandidate> {
        legacyStorageMigrated()
        val preferences = dataStore.data.first()
        return savedServers(preferences[SAVED_SERVERS_KEY]).map { server ->
            ImportedServerCandidate(
                serverUrl = server.serverUrl,
                serverName = server.serverName,
                serverTypeRaw = server.serverTypeRaw,
                username = server.username,
                userId = server.userId,
                profileImageUrl = server.profileImageUrl,
                serverLogoUrl = server.serverLogoUrl,
                serverRemark = if (includeRemarks) server.serverRemark else null,
                serverLines = server.serverLines,
                activeLineId = server.activeLineId,
                accessToken = if (includeCredentials) secureSessionStore.getToken(server.id) else null,
                source = "Grmemby",
                lastUsedAt = server.lastUsedAt
            )
        }
    }

    suspend fun importSavedServerCandidates(candidates: List<ImportedServerCandidate>): Int {
        val cleanedCandidates = candidates
            .mapNotNull { candidate -> candidate.toSavedServerOrNull() }
            .distinctBy { it.id }
        if (cleanedCandidates.isEmpty()) return 0

        legacyStorageMigrated()
        dataStore.edit { prefs ->
            val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
            val mergedServers = existingServers.toMutableList()
            cleanedCandidates.forEach { incoming ->
                val existingIndex = mergedServers.indexOfFirst { existing ->
                    existing.id == incoming.id || existing.isSameImportedAccount(incoming)
                }
                if (existingIndex >= 0) {
                    val existing = mergedServers[existingIndex]
                    val existingHasSession = secureSessionStore.hasToken(existing.id)
                    val incomingHasSession = secureSessionStore.hasToken(incoming.id)
                    mergedServers[existingIndex] = when {
                        existingHasSession && !incomingHasSession -> existing.mergeImportedMetadata(incoming)
                        incomingHasSession && !existingHasSession -> incoming.mergeImportedMetadata(existing)
                        existing.lastUsedAt >= incoming.lastUsedAt -> existing.mergeImportedMetadata(incoming)
                        else -> incoming.mergeImportedMetadata(existing)
                    }
                } else {
                    mergedServers += incoming
                }
            }
            val updatedServers = mergedServers
                .dedupeImportedServerAliases()
                .sortedByDescending { it.lastUsedAt }
            prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
        }
        candidates.forEach { candidate ->
            val token = candidate.accessToken?.takeIf { it.isNotBlank() } ?: return@forEach
            candidate.toSavedServerOrNull()?.let { savedServer -> secureSessionStore.putToken(savedServer.id, token) }
        }
        return cleanedCandidates.size
    }

    private fun SavedServer.isSameImportedAccount(other: SavedServer): Boolean {
        if (!isSameServer(serverUrl, other.serverUrl)) return false
        if (userId == other.userId) return true
        val thisImported = userId.startsWith("imported:") || userId.startsWith("yamby:")
        val otherImported = other.userId.startsWith("imported:") || other.userId.startsWith("yamby:")
        val sameKnownUsername = username.isNotBlank() && other.username.isNotBlank() &&
            username.equals(other.username, ignoreCase = true)
        return sameKnownUsername || thisImported || otherImported || username.isBlank() || other.username.isBlank()
    }

    private fun SavedServer.mergeImportedMetadata(imported: SavedServer): SavedServer {
        val mergedLines = (serverLines + imported.serverLines)
            .distinctBy { line -> canonicalServerUrlWithDefaultPort(line.url).lowercase() }
        return copy(
            serverName = serverName.takeIf { it.isNotBlank() } ?: imported.serverName,
            serverTypeRaw = serverTypeRaw.takeIf { it.isNotBlank() && it != ServerType.UNKNOWN.name }
                ?: imported.serverTypeRaw,
            username = username.takeIf { it.isNotBlank() } ?: imported.username,
            profileImageUrl = profileImageUrl ?: imported.profileImageUrl,
            serverLogoUrl = serverLogoUrl ?: imported.serverLogoUrl,
            serverRemark = serverRemark ?: imported.serverRemark,
            serverLines = mergedLines,
            activeLineId = activeLineId ?: imported.activeLineId?.takeIf { id -> mergedLines.any { it.id == id } },
            lastUsedAt = maxOf(lastUsedAt, imported.lastUsedAt)
        )
    }

    private fun List<SavedServer>.dedupeImportedServerAliases(): List<SavedServer> {
        val result = mutableListOf<SavedServer>()
        for (server in sortedWith(compareByDescending<SavedServer> { secureSessionStore.hasToken(it.id) }
            .thenByDescending { it.lastUsedAt })) {
            val existingIndex = result.indexOfFirst { existing -> existing.isSameImportedAccount(server) }
            if (existingIndex >= 0) {
                val existing = result[existingIndex]
                result[existingIndex] = if (secureSessionStore.hasToken(existing.id)) {
                    existing.mergeImportedMetadata(server)
                } else if (secureSessionStore.hasToken(server.id)) {
                    server.mergeImportedMetadata(existing)
                } else {
                    existing.mergeImportedMetadata(server)
                }
            } else {
                result += server
            }
        }
        return result
    }

    private fun ImportedServerCandidate.toSavedServerOrNull(): SavedServer? {
        val cleanedUrl = serverUrl.trim().takeIf { it.isNotBlank() } ?: return null
        val normalizedUrl = canonicalServerUrlWithDefaultPort(cleanedUrl)
        val cleanedUserId = userId.trim().ifBlank {
            "imported:${username.trim().ifBlank { normalizedUrl.hashCode().toString() }}"
        }
        val serverId = buildServerId(normalizedUrl, cleanedUserId)
        val cleanedLines = serverLines
            .mapNotNull { line ->
                val cleanedLineUrl = line.url.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                line.copy(
                    id = line.id.ifBlank { "line-${cleanedLineUrl.hashCode()}" },
                    name = line.name.trim(),
                    url = canonicalServerUrlWithDefaultPort(cleanedLineUrl)
                )
            }
            .distinctBy { it.id }
        return SavedServer(
            id = serverId,
            serverUrl = normalizedUrl,
            serverName = serverName.trim().ifBlank { normalizedUrl },
            serverTypeRaw = serverTypeRaw.takeIf { raw -> runCatching { ServerType.valueOf(raw) }.isSuccess }
                ?: ServerType.EMBY.name,
            username = username.trim(),
            userId = cleanedUserId,
            profileImageUrl = profileImageUrl?.takeIf { it.isNotBlank() },
            serverLogoUrl = serverLogoUrl?.takeIf { it.isNotBlank() },
            serverRemark = serverRemark?.trim()?.takeIf { it.isNotBlank() },
            serverLines = cleanedLines,
            activeLineId = activeLineId?.takeIf { id -> cleanedLines.any { it.id == id } },
            lastUsedAt = lastUsedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    suspend fun savedServer() {
        legacyStorageMigrated()
        dataStore.edit { preferences ->
            val activeServer = activeServer(preferences) ?: return@edit
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val updatedServers = upsertSavedServer(existingServers, activeServer)
            preferences[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
            if (preferences[ACTIVE_SERVER_ID_KEY].isNullOrBlank()) {
                preferences[ACTIVE_SERVER_ID_KEY] = activeServer.id
            }
        }
    }

    suspend fun switchServer(serverId: String): Result<SavedServer> {
        if (serverId.isBlank()) {
            return Result.failure(Exception("Invalid server id"))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val targetServer = existingServers.firstOrNull { it.id == serverId }
                ?: activeServer(preferences)?.takeIf { it.id == serverId }
                ?: return Result.failure(Exception("Saved server not found"))
            val accessToken = secureSessionStore.getToken(targetServer.id)
                ?: return Result.failure(Exception("Saved session expired. Please sign in again."))

            val switchedServer = targetServer.copy(lastUsedAt = System.currentTimeMillis())

            dataStore.edit { prefs ->
                val latestServers = savedServers(prefs[SAVED_SERVERS_KEY])
                val updatedServers = upsertSavedServer(latestServers, switchedServer)
                prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                prefs[ACTIVE_SERVER_ID_KEY] = switchedServer.id
                prefs[SERVER_URL_KEY] = switchedServer.effectiveServerUrl
                prefs[SERVER_NAME_KEY] = switchedServer.serverName
                prefs[SERVER_TYPE_KEY] = switchedServer.serverTypeRaw
                prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                prefs[USER_ID_KEY] = switchedServer.userId
                prefs[USERNAME_KEY] = switchedServer.username
                prefs[IS_AUTHENTICATED_KEY] = accessToken.isNotBlank() &&
                    switchedServer.userId.isNotBlank()
            }

            Result.success(switchedServer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeSavedServer(serverId: String): Result<Unit> {
        if (serverId.isBlank()) {
            return Result.failure(Exception("Invalid server id"))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val existingServers = savedServers(preferences[SAVED_SERVERS_KEY])
            val activeServerId = preferences[ACTIVE_SERVER_ID_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: activeServer(preferences)?.id

            val removeServer = existingServers.firstOrNull { it.id == serverId }
                ?: return Result.failure(Exception("Saved server not found"))

            if (removeServer.id == activeServerId) {
                return Result.failure(
                    Exception("Switch to another server before removing the active one.")
                )
            }

            val updatedServers = existingServers
                .filterNot { it.id == removeServer.id }
                .sortedByDescending { it.lastUsedAt }

            dataStore.edit { prefs ->
                prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                if (prefs[ACTIVE_SERVER_ID_KEY] == removeServer.id) {
                    prefs[ACTIVE_SERVER_ID_KEY] = ""
                }
            }
            secureSessionStore.removeToken(removeServer.id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSavedServerProfileImage(
        serverId: String,
        profileImageUrl: String?
    ) {
        if (serverId.isBlank()) return

        legacyStorageMigrated()
        dataStore.edit { prefs ->
            val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
            val targetServer = existingServers.firstOrNull { savedServer -> savedServer.id == serverId }
                ?: return@edit
            val updatedServers = upsertSavedServer(
                existing = existingServers,
                incoming = targetServer.copy(profileImageUrl = profileImageUrl)
            )
            prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
        }
    }

    suspend fun updateActiveServerProfileImage(profileImageUrl: String?) {
        legacyStorageMigrated()
        val preferences = dataStore.data.first()
        val activeServerId = preferences[ACTIVE_SERVER_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?: activeServer(preferences)?.id
            ?: return
        updateSavedServerProfileImage(
            serverId = activeServerId,
            profileImageUrl = profileImageUrl
        )
    }

    suspend fun updateSavedServerLogo(
        serverId: String,
        serverLogoUrl: String?
    ) {
        if (serverId.isBlank()) return

        legacyStorageMigrated()
        dataStore.edit { prefs ->
            val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
            val targetServer = existingServers.firstOrNull { savedServer -> savedServer.id == serverId }
                ?: return@edit
            val updatedServers = upsertSavedServer(
                existing = existingServers,
                incoming = targetServer.copy(serverLogoUrl = serverLogoUrl?.takeIf { it.isNotBlank() })
            )
            prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
        }
    }

    suspend fun updateSavedServerRemark(
        serverIds: List<String>,
        remark: String?
    ) {
        val targetIds = serverIds.filter { it.isNotBlank() }.toSet()
        if (targetIds.isEmpty()) return

        legacyStorageMigrated()
        dataStore.edit { prefs ->
            val cleanedRemark = remark?.trim()?.takeIf { it.isNotBlank() }
            val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
            val updatedServers = existingServers
                .map { savedServer ->
                    if (savedServer.id in targetIds) savedServer.copy(serverRemark = cleanedRemark) else savedServer
                }
                .sortedByDescending { it.lastUsedAt }
            prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
        }
    }

    suspend fun updateSavedServerLines(
        serverIds: List<String>,
        lines: List<ServerLine>,
        activeLineId: String?
    ): Result<Unit> {
        val targetIds = serverIds.filter { it.isNotBlank() }.toSet()
        if (targetIds.isEmpty()) return Result.failure(Exception("Invalid server id"))
        val cleanedLines = lines
            .mapNotNull { line ->
                val cleanedUrl = line.url.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                line.copy(
                    id = line.id.ifBlank { "line-${System.currentTimeMillis()}-${cleanedUrl.hashCode()}" },
                    name = line.name.trim(),
                    url = canonicalServerUrlWithDefaultPort(cleanedUrl)
                )
            }
            .distinctBy { line -> line.id }
        val cleanedActiveLineId = activeLineId?.takeIf { id -> cleanedLines.any { it.id == id } }

        return try {
            legacyStorageMigrated()
            dataStore.edit { prefs ->
                val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
                val activeServerId = prefs[ACTIVE_SERVER_ID_KEY]?.takeIf { it.isNotBlank() }
                val updatedServers = existingServers
                    .map { savedServer ->
                        if (savedServer.id in targetIds) {
                            savedServer.copy(
                                serverLines = cleanedLines,
                                activeLineId = cleanedActiveLineId,
                                lastUsedAt = if (savedServer.id == activeServerId) System.currentTimeMillis() else savedServer.lastUsedAt
                            )
                        } else {
                            savedServer
                        }
                    }
                    .sortedByDescending { it.lastUsedAt }
                prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                updatedServers.firstOrNull { it.id == activeServerId }?.let { activeServer ->
                    prefs[SERVER_URL_KEY] = activeServer.effectiveServerUrl
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setSavedServerActiveLine(
        serverIds: List<String>,
        activeLineId: String?
    ): Result<Unit> {
        val preferences = dataStore.data.first()
        val targetServers = savedServers(preferences[SAVED_SERVERS_KEY])
            .filter { savedServer -> savedServer.id in serverIds.toSet() }
        val firstTarget = targetServers.firstOrNull()
            ?: return Result.failure(Exception("Saved server not found"))
        return updateSavedServerLines(
            serverIds = serverIds,
            lines = firstTarget.serverLines,
            activeLineId = activeLineId
        )
    }

    suspend fun testServerConnection(serverUrl: String): Result<ServerInfo> {
        legacyStorageMigrated()
        return try {
            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                return Result.failure(Exception("Invalid URL format. URL must start with http:// or https://"))
            }

            val resolved = NetworkModule.serverEndpoint(
                serverUrl = serverUrl,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig(),
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            ).getOrElse { error ->
                return Result.failure(Exception(error.message ?: "Unable to connect to server"))
            }

            val normalizedServerInfo = resolved.serverInfo.copy(
                serverName = serverName(
                    serverInfo = resolved.serverInfo,
                    serverType = resolved.serverType
                )
            )

            Result.success(normalizedServerInfo)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Cannot reach server. Please check your internet connection and server URL."))
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception("Connection refused. Please check if the server is running and the URL is correct."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Connection timeout. Please check your network connection and try again."))
        } catch (e: javax.net.ssl.SSLException) {
            Result.failure(Exception("SSL connection failed. Please check if the server supports HTTPS or try HTTP instead."))
        } catch (e: java.security.cert.CertificateException) {
            Result.failure(Exception("Certificate verification failed. The server's SSL certificate may be invalid."))
        } catch (e: java.io.IOException) {
            Result.failure(Exception("Network error: ${e.message ?: "Unable to connect to server"}"))
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("Failed to connect", ignoreCase = true) == true ->
                    "Failed to connect to server. Please check the URL and your network connection."
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Connection timeout. Server may be slow or unavailable."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "Connection refused. Please check if the server is running."
                else -> e.message ?: "Unknown connection error"
            }
            Result.failure(Exception(errorMessage))
        }
    }

    private suspend fun authEndpoint(
        serverUrl: String,
        preferences: Preferences
    ): Result<ServerEndpoint> {
        legacyStorageMigrated()
        val savedServerUrl = preferences[SERVER_URL_KEY]
        val savedServerType = preferences[SERVER_TYPE_KEY]?.let {
            runCatching { ServerType.valueOf(it) }.getOrNull()
        }
        if (isSameServer(serverUrl, savedServerUrl) && savedServerUrl != null && savedServerType != null) {
            return Result.success(
                ServerEndpoint(
                    baseUrl = savedServerUrl,
                    serverType = savedServerType,
                    serverInfo = ServerInfo(
                        serverName = preferences[SERVER_NAME_KEY]
                    )
                )
            )
        }

        return NetworkModule.serverEndpoint(
            serverUrl = serverUrl,
            storageDir = context.filesDir,
            timeoutConfig = networkPreferences.getTimeoutConfig(),
            blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
        ).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                Result.failure(Exception(error.message ?: "Unable to resolve server endpoint"))
            }
        )
    }

    suspend fun authenticateUser(
        serverUrl: String,
        username: String,
        password: String
    ): Result<AuthenticationResult> {
        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val savedServerUrl = preferences[SERVER_URL_KEY]
            val savedServerType = preferences[SERVER_TYPE_KEY]?.let {
                runCatching { ServerType.valueOf(it) }.getOrNull()
            }

            val endpoint = if (isSameServer(serverUrl, savedServerUrl) && savedServerUrl != null && savedServerType != null) {
                ServerEndpoint(
                    baseUrl = savedServerUrl,
                    serverType = savedServerType,
                    serverInfo = ServerInfo(
                        serverName = preferences[SERVER_NAME_KEY] ?: "",
                        productName = when (savedServerType) {
                            ServerType.EMBY -> "Emby"
                            ServerType.JELLYFIN -> "Jellyfin"
                            ServerType.UNKNOWN -> "Media Server"
                        }
                    )
                )
            } else {
                NetworkModule.serverEndpoint(
                    serverUrl = serverUrl,
                    storageDir = context.filesDir,
                    timeoutConfig = networkPreferences.getTimeoutConfig(),
                    blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
                ).getOrElse { error ->
                    return Result.failure(Exception(error.message ?: "Unable to resolve server endpoint"))
                }
            }

            val serverName = serverName(
                serverInfo = endpoint.serverInfo,
                serverType = endpoint.serverType
            )
            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig(),
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )

            val response = api.authenticateByName(AuthenticationRequest(username, password))
            if (response.isSuccessful && response.body() != null) {
                val authResult = response.body()!!
                val savedServerId = buildServerId(serverUrl = endpoint.baseUrl, userId = authResult.user.id)
                val existingServersBeforeAuth = savedServers(preferences[SAVED_SERVERS_KEY])
                val existingServer = existingServersBeforeAuth
                    .firstOrNull { it.id == savedServerId }
                    ?: existingServersBeforeAuth.firstOrNull {
                        isSameServer(it.serverUrl, endpoint.baseUrl) &&
                            it.username.equals(username, ignoreCase = true)
                    }
                val savedServer = SavedServer(
                    id = savedServerId,
                    serverUrl = endpoint.baseUrl,
                    serverName = serverName,
                    serverTypeRaw = endpoint.serverType.name,
                    username = username,
                    userId = authResult.user.id,
                    profileImageUrl = existingServer?.profileImageUrl,
                    serverLogoUrl = existingServer?.serverLogoUrl,
                    serverRemark = existingServer?.serverRemark,
                    serverLines = existingServer?.serverLines.orEmpty(),
                    activeLineId = existingServer?.activeLineId,
                    lastUsedAt = System.currentTimeMillis()
                )

                secureSessionStore.putToken(savedServer.id, authResult.accessToken)
                try {
                    dataStore.edit { prefs ->
                        val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
                        val updatedServers = upsertSavedServer(existingServers, savedServer)
                        prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                        prefs[ACTIVE_SERVER_ID_KEY] = savedServer.id
                        prefs[SERVER_URL_KEY] = endpoint.baseUrl
                        prefs[SERVER_NAME_KEY] = serverName
                        prefs[SERVER_TYPE_KEY] = endpoint.serverType.name
                        prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                        prefs[USER_ID_KEY] = authResult.user.id
                        prefs[USERNAME_KEY] = username
                        prefs[IS_AUTHENTICATED_KEY] = true
                    }
                } catch (error: Exception) {
                    secureSessionStore.removeToken(savedServer.id)
                    throw error
                }
                Result.success(authResult)
            } else {
                Result.failure(Exception("Authentication failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initiateQuickConnect(serverUrl: String): Result<QuickConnectResult> {
        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrElse { error ->
                return Result.failure(error)
            }

            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig(),
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )
            val response = api.initiateQuickConnect()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Unable to start Quick Connect: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isQuickConnectSupported(serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return false
        return runCatching {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrNull()
            endpoint?.serverType != ServerType.EMBY
        }.getOrDefault(true)
    }

    suspend fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String
    ): Result<AuthenticationResult> {
        if (secret.isBlank()) {
            return Result.failure(Exception("Quick Connect secret is missing."))
        }

        return try {
            legacyStorageMigrated()
            val preferences = dataStore.data.first()
            val endpoint = authEndpoint(serverUrl, preferences).getOrElse { error ->
                return Result.failure(error)
            }

            val serverName = serverName(
                serverInfo = endpoint.serverInfo,
                serverType = endpoint.serverType
            )
            val api = NetworkModule.createMediaServerApi(
                baseUrl = endpoint.baseUrl,
                serverType = endpoint.serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig(),
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )

            val response = api.authenticateWithQuickConnect(QuickConnectDto(secret = secret))
            if (response.isSuccessful && response.body() != null) {
                val authResult = response.body()!!
                val persistedUsername = authResult.user.name.trim().ifBlank { authResult.user.id }
                val savedServerId = buildServerId(serverUrl = endpoint.baseUrl, userId = authResult.user.id)
                val existingServersBeforeAuth = savedServers(preferences[SAVED_SERVERS_KEY])
                val existingServer = existingServersBeforeAuth
                    .firstOrNull { it.id == savedServerId }
                    ?: existingServersBeforeAuth.firstOrNull {
                        isSameServer(it.serverUrl, endpoint.baseUrl) &&
                            it.username.equals(persistedUsername, ignoreCase = true)
                    }
                val savedServer = SavedServer(
                    id = savedServerId,
                    serverUrl = endpoint.baseUrl,
                    serverName = serverName,
                    serverTypeRaw = endpoint.serverType.name,
                    username = persistedUsername,
                    userId = authResult.user.id,
                    profileImageUrl = existingServer?.profileImageUrl,
                    serverLogoUrl = existingServer?.serverLogoUrl,
                    serverRemark = existingServer?.serverRemark,
                    serverLines = existingServer?.serverLines.orEmpty(),
                    activeLineId = existingServer?.activeLineId,
                    lastUsedAt = System.currentTimeMillis()
                )

                secureSessionStore.putToken(savedServer.id, authResult.accessToken)
                try {
                    dataStore.edit { prefs ->
                        val existingServers = savedServers(prefs[SAVED_SERVERS_KEY])
                        val updatedServers = upsertSavedServer(existingServers, savedServer)
                        prefs[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                        prefs[ACTIVE_SERVER_ID_KEY] = savedServer.id
                        prefs[SERVER_URL_KEY] = endpoint.baseUrl
                        prefs[SERVER_NAME_KEY] = serverName
                        prefs[SERVER_TYPE_KEY] = endpoint.serverType.name
                        prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                        prefs[USER_ID_KEY] = authResult.user.id
                        prefs[USERNAME_KEY] = persistedUsername
                        prefs[IS_AUTHENTICATED_KEY] = true
                    }
                } catch (error: Exception) {
                    secureSessionStore.removeToken(savedServer.id)
                    throw error
                }
                Result.success(authResult)
            } else {
                Result.failure(Exception("Authentication failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        legacyStorageMigrated()
        dataStore.edit { preferences ->
            val activeServerId = currentServerId(preferences)
            if (activeServerId != null) {
                val updatedServers = savedServers(preferences[SAVED_SERVERS_KEY])
                    .filterNot { it.id == activeServerId }
                preferences[SAVED_SERVERS_KEY] = serializeSavedServers(updatedServers)
                secureSessionStore.removeToken(activeServerId)
            }
            preferences[LEGACY_ACCESS_TOKEN_KEY] = ""
            preferences[USER_ID_KEY] = ""
            preferences[USERNAME_KEY] = ""
            preferences[SERVER_URL_KEY] = ""
            preferences[SERVER_NAME_KEY] = ""
            preferences[SERVER_TYPE_KEY] = ""
            preferences[ACTIVE_SERVER_ID_KEY] = ""
            preferences[IS_AUTHENTICATED_KEY] = false
        }
    }

    private suspend fun legacyStorageMigrated() {
        if (migrationExecuted) return

        legacyMigrationMutex.withLock {
            if (migrationExecuted) return

            val preferences = dataStore.data.first()
            val storedServers = persistedSavedServers(preferences[SAVED_SERVERS_KEY])
            val activeServerId = currentServerId(preferences)
            val legacyAccessToken = preferences[LEGACY_ACCESS_TOKEN_KEY]?.takeIf { it.isNotBlank() }

            storedServers.forEach { storedServer ->
                storedServer.accessToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { secureSessionStore.putToken(storedServer.id, it) }
            }

            if (activeServerId != null && !legacyAccessToken.isNullOrBlank()) {
                secureSessionStore.putToken(activeServerId, legacyAccessToken)
            }

            val authenticatedServers = storedServers
                .mapNotNull { storedServer -> storedServer.toSavedServerOrNull() }
                .sortedByDescending { it.lastUsedAt }

            val serializedServers = serializeSavedServers(authenticatedServers)
            if (
                preferences[LEGACY_ACCESS_TOKEN_KEY].orEmpty().isNotBlank() ||
                preferences[SAVED_SERVERS_KEY] != serializedServers
            ) {
                dataStore.edit { prefs ->
                    prefs[LEGACY_ACCESS_TOKEN_KEY] = ""
                    prefs[SAVED_SERVERS_KEY] = serializedServers
                }
            }

            migrationExecuted = true
        }
    }

    private fun StoredSavedServer.toSavedServerOrNull(): SavedServer? {
        if (
            id.isBlank() ||
            serverUrl.isBlank() ||
            userId.isBlank()
        ) {
            return null
        }

        return SavedServer(
            id = id,
            serverUrl = serverUrl,
            serverName = serverName,
            serverTypeRaw = serverTypeRaw,
            username = username,
            userId = userId,
            profileImageUrl = profileImageUrl,
            serverLogoUrl = serverLogoUrl,
            serverRemark = serverRemark,
            serverLines = serverLines,
            activeLineId = activeLineId?.takeIf { lineId -> serverLines.any { it.id == lineId } },
            lastUsedAt = lastUsedAt
        )
    }

    private fun isSameServer(inputUrl: String, savedUrl: String?): Boolean {
        if (savedUrl.isNullOrBlank()) return false

        val normalizedInput = canonicalServerUrl(inputUrl)
        val normalizedSaved = canonicalServerUrl(savedUrl)
        val normalizedSavedWithoutEmby = normalizedSaved.removeSuffix("/emby")

        return normalizedInput.equals(normalizedSaved, ignoreCase = true) ||
            normalizedInput.equals(normalizedSavedWithoutEmby, ignoreCase = true)
    }
}
