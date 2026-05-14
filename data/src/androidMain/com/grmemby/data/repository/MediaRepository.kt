package com.grmemby.data.repository

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grmemby.data.DataModuleConfig
import com.grmemby.data.api.MediaServerApi
import com.grmemby.data.datastore.DataStoreProvider
import com.grmemby.data.datastore.HomeSnapshotStore
import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.HomeLibrarySectionData
import com.grmemby.data.model.MediaSource
import com.grmemby.data.model.MediaSourceInfo
import com.grmemby.data.model.PlaybackSegments
import com.grmemby.data.model.PlaybackAuthContext
import com.grmemby.data.model.PlaybackInfoResponse
import com.grmemby.data.model.PlaybackUrlBuilder
import com.grmemby.data.model.PlaybackRequest
import com.grmemby.data.model.PlaybackStreamOptions
import com.grmemby.data.model.PersistedHomeSnapshot
import com.grmemby.data.model.QueryResult
import com.grmemby.data.model.PlaybackInfoRequest
import com.grmemby.data.model.PlaybackProgressRequest
import com.grmemby.data.model.PlaybackStartRequest
import com.grmemby.data.model.PlaybackStoppedRequest
import com.grmemby.data.model.RecommendationDto
import com.grmemby.data.model.UserDto
import com.grmemby.data.network.NetworkModule
import com.grmemby.data.network.GrmembyJson
import com.grmemby.data.network.ServerType
import com.grmemby.data.network.trimTrailingSlash
import com.grmemby.data.preferences.NetworkPreferences
import com.grmemby.data.preferences.NetworkTimeoutConfig
import com.grmemby.data.security.AuthSessionIds
import com.grmemby.data.security.LEGACY_ACCESS_TOKEN_KEY
import com.grmemby.data.security.SecureSessionStore
import com.grmemby.data.util.buildServerUrl
import com.grmemby.data.util.getServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MediaRepository(private val context: Context) {
    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val SERVER_TYPE_KEY = stringPreferencesKey("server_type")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val SAVED_SERVERS_KEY = stringPreferencesKey("saved_servers_v1")
        private val ACTIVE_SERVER_ID_KEY = stringPreferencesKey("active_server_id")

        private const val IMAGE_ROW_FIELDS =
            "ImageTags,BackdropImageTags,PrimaryImageAspectRatio,ParentPrimaryImageItemId,ParentPrimaryImageTag,SeriesPrimaryImageTag"
        private const val DETAIL_FIELDS =
            "People,Studios,Genres,Overview,ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,OfficialRating,UserData,Chapters,ProviderIds,IndexNumber,ParentIndexNumber,$IMAGE_ROW_FIELDS"
        private const val SIMILAR_ROW_FIELDS =
            "ProductionYear,SeriesName,SeriesId,ParentIndexNumber,IndexNumber,EpisodeCount,RecursiveItemCount,ChildCount,$IMAGE_ROW_FIELDS"
        private const val PERSON_CREDIT_ROW_FIELDS =
            "ProductionYear,SeriesName,SeriesId,ParentIndexNumber,IndexNumber,EpisodeCount,RecursiveItemCount,ChildCount,UserData,$IMAGE_ROW_FIELDS"
        private const val SEASON_ROW_FIELDS =
            "ChildCount,RecursiveItemCount,EpisodeCount,$IMAGE_ROW_FIELDS"
        private const val EPISODE_ROW_FIELDS =
            "Overview,RunTimeTicks,PremiereDate,UserData,MediaStreams,SeriesName,SeriesId,SeasonName,SeasonId,ParentIndexNumber,IndexNumber,$IMAGE_ROW_FIELDS"
    }

    private val dataStore: DataStore<Preferences> = DataStoreProvider.getDataStore(context)
    private val networkPreferences = NetworkPreferences(context)
    private val secureSessionStore = SecureSessionStore(context)

    private data class ImageAuthState(
        val serverUrl: String?
    )

    private data class ApiSession(
        val api: MediaServerApi,
        val userId: String,
        val serverType: ServerType?,
        val baseUrl: String
    )

    private data class SuggestionsRoute(
        val endpoint: String,
        val userIdQuery: String?
    )

    private data class SessionConfig(
        val serverUrl: String,
        val primaryServerUrl: String?,
        val activeServerId: String?,
        val activeLineId: String?,
        val serverTypeRaw: String?,
        val serverType: ServerType?,
        val accessToken: String?,
        val userId: String,
        val timeoutConfig: NetworkTimeoutConfig
    )

    data class ItemDownloadRequest(
        val itemId: String,
        val displayName: String,
        val downloadUrl: String,
        val authToken: String?,
        val fileExtension: String?
    )

    data class SavedServerOverview(
        val movieCount: Int? = null,
        val seriesCount: Int? = null,
        val lastPlayedAtEpochMs: Long? = null,
        val latencyMs: Int? = null,
        val isConnected: Boolean = false
    )

    @Volatile
    private var cachedSession: ApiSession? = null

    @Volatile
    private var cachedSessionKey: String? = null

    @Volatile
    private var cachedSessionConfig: SessionConfig? = null

    private val imageAuthCacheTtlMs = 1500L

    @Volatile
    private var cachedImageAuthState: ImageAuthState? = null

    @Volatile
    private var cachedImageAuthAt: Long = 0L

    private val homeSnapshotStore = HomeSnapshotStore(context.filesDir)
    private val theIntroDbClient = TheIntroDbClient(
        getSeriesItem = { seriesId -> getItemById(seriesId).getOrNull() }
    )
    private val introDbClient = IntroDbClient(
        getSeriesItem = { seriesId -> getItemById(seriesId).getOrNull() }
    )

    private val imageAuthStateFlow: Flow<ImageAuthState> = dataStore.data
        .map { preferences ->
            ImageAuthState(
                serverUrl = preferences[SERVER_URL_KEY]
            )
        }
        .distinctUntilChanged()

    private suspend fun getApi(): MediaServerApi? = getApiSession()?.api

    private suspend fun getUserId(): String? = getApiSession()?.userId

    private fun normalizeSubtitleStreamIndex(subtitleStreamIndex: Int?): Int? {
        return subtitleStreamIndex?.takeIf { it >= 0 }
    }

    private fun createPlaybackAuthContext(config: SessionConfig): PlaybackAuthContext {
        return PlaybackAuthContext(
            serverUrl = config.serverUrl,
            serverType = config.serverType,
            accessToken = config.accessToken,
            deviceId = NetworkModule.getClientDeviceId(),
            clientVersion = DataModuleConfig.CLIENT_VERSION
        )
    }

    private fun savedServers(raw: String?): List<AuthRepository.SavedServer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            GrmembyJson.decodeFromString<List<AuthRepository.SavedServer>>(raw)
                .filter { savedServer ->
                    savedServer.id.isNotBlank() &&
                        savedServer.serverUrl.isNotBlank() &&
                        savedServer.userId.isNotBlank()
                }
        }.getOrDefault(emptyList())
    }

    private fun accessTokenForSession(
        activeServer: AuthRepository.SavedServer?,
        fallbackServerUrl: String,
        userId: String,
        preferences: Preferences
    ): String? {
        return activeServer?.id
            ?.let { secureSessionStore.getToken(it) }
            ?: secureSessionStore.getToken(AuthSessionIds.buildServerId(fallbackServerUrl, userId))
            ?: activeServer?.serverUrl
                ?.let { primaryUrl -> secureSessionStore.getToken(AuthSessionIds.buildServerId(primaryUrl, userId)) }
            ?: preferences[LEGACY_ACCESS_TOKEN_KEY]
    }

    private suspend fun fallbackActiveLineToPrimary(config: SessionConfig): SessionConfig {
        val activeServerId = config.activeServerId ?: return config
        val primaryServerUrl = config.primaryServerUrl ?: return config
        if (config.activeLineId.isNullOrBlank()) return config

        dataStore.edit { preferences ->
            val updatedServers = savedServers(preferences[SAVED_SERVERS_KEY])
                .map { savedServer ->
                    if (savedServer.id == activeServerId) {
                        savedServer.copy(
                            activeLineId = null,
                            lastUsedAt = System.currentTimeMillis()
                        )
                    } else {
                        savedServer
                    }
                }
                .sortedByDescending { it.lastUsedAt }
            preferences[SAVED_SERVERS_KEY] = GrmembyJson.encodeToString(updatedServers)
            if (preferences[ACTIVE_SERVER_ID_KEY] == activeServerId) {
                preferences[SERVER_URL_KEY] = primaryServerUrl
            }
        }
        return config.copy(serverUrl = primaryServerUrl, activeLineId = null)
    }

    private suspend fun resolveReachableSessionConfig(config: SessionConfig): SessionConfig {
        if (config.activeLineId.isNullOrBlank() || config.primaryServerUrl.isNullOrBlank()) return config
        val lineReachable = NetworkModule.serverEndpoint(
            serverUrl = config.serverUrl,
            storageDir = context.filesDir,
            timeoutConfig = config.timeoutConfig,
            blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
        ).isSuccess
        return if (lineReachable) config else fallbackActiveLineToPrimary(config)
    }

    private suspend fun getSessionConfig(): SessionConfig? {
        val preferences = dataStore.data.first()
        val storedServers = savedServers(preferences[SAVED_SERVERS_KEY])
        val activeServerId = preferences[ACTIVE_SERVER_ID_KEY]?.takeIf { it.isNotBlank() }
        val activeServer = activeServerId?.let { id -> storedServers.firstOrNull { it.id == id } }
        val fallbackServerUrl = activeServer?.serverUrl ?: preferences[SERVER_URL_KEY] ?: return null
        val userId = activeServer?.userId ?: preferences[USER_ID_KEY] ?: return null
        val serverTypeRaw = activeServer?.serverTypeRaw ?: preferences[SERVER_TYPE_KEY]
        val serverType = serverTypeRaw?.let {
            runCatching { ServerType.valueOf(it) }.getOrNull()
        }
        val serverUrl = activeServer?.effectiveServerUrl ?: fallbackServerUrl
        val accessToken = accessTokenForSession(
            activeServer = activeServer,
            fallbackServerUrl = fallbackServerUrl,
            userId = userId,
            preferences = preferences
        )

        return SessionConfig(
            serverUrl = serverUrl,
            primaryServerUrl = activeServer?.serverUrl,
            activeServerId = activeServer?.id ?: activeServerId,
            activeLineId = activeServer?.activeLineId,
            serverTypeRaw = serverTypeRaw,
            serverType = serverType,
            accessToken = accessToken,
            userId = userId,
            timeoutConfig = networkPreferences.getTimeoutConfig()
        ).also {
            cachedSessionConfig = it
        }
    }

    private suspend fun getApiSession(): ApiSession? {
        val initialConfig = getSessionConfig() ?: return null
        fun sessionKey(config: SessionConfig): String = buildString {
            append(config.serverUrl)
            append("|")
            append(config.serverTypeRaw ?: "")
            append("|")
            append(config.accessToken ?: "")
            append("|")
            append(config.userId)
            append("|")
            append(config.timeoutConfig.requestTimeoutMs)
            append("|")
            append(config.timeoutConfig.connectionTimeoutMs)
            append("|")
            append(config.timeoutConfig.socketTimeoutMs)
        }

        val initialSessionKey = sessionKey(initialConfig)
        cachedSession?.let { session ->
            if (cachedSessionKey == initialSessionKey) {
                return session
            }
        }

        val config = resolveReachableSessionConfig(initialConfig)
        val newSessionKey = sessionKey(config)

        cachedSession?.let { session ->
            if (cachedSessionKey == newSessionKey) {
                return session
            }
        }

        synchronized(this) {
            cachedSession?.let { session ->
                if (cachedSessionKey == newSessionKey) {
                    return session
                }
            }

            val api = NetworkModule.createMediaServerApi(
                baseUrl = config.serverUrl,
                accessToken = config.accessToken,
                serverType = config.serverType,
                storageDir = context.filesDir,
                timeoutConfig = config.timeoutConfig,
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )

            val session = ApiSession(
                api = api,
                userId = config.userId,
                serverType = config.serverType,
                baseUrl = config.serverUrl
            )
            cachedSession = session
            cachedSessionKey = newSessionKey
            return session
        }
    }

    private fun buildSnapshotKey(config: SessionConfig): String {
        return "${trimTrailingSlash(config.serverUrl)}|${config.userId}"
    }

    fun getPersistedHomeSnapshot(): PersistedHomeSnapshot? {
        return homeSnapshotStore.getPersistedHomeSnapshot()
    }

    suspend fun loadPersistedHomeSnapshot(
        maxAgeMs: Long? = null
    ): PersistedHomeSnapshot? {
        val config = getSessionConfig() ?: return null
        return homeSnapshotStore.loadPersistedHomeSnapshot(
            expectedSnapshotKey = buildSnapshotKey(config),
            maxAgeMs = maxAgeMs
        )
    }

    suspend fun persistHomeSnapshot(
        featuredHomeItems: List<BaseItemDto>? = null,
        continueWatchingItems: List<BaseItemDto>? = null,
        nextUpItems: List<BaseItemDto>? = null,
        homeLibrarySections: List<HomeLibrarySectionData>? = null,
        myMediaLibraries: List<BaseItemDto>? = null,
        username: String? = null,
        serverName: String? = null,
        serverUrl: String? = null,
        profileImageUrl: String? = null,
        isAdministrator: Boolean? = null,
        isVideoTranscodingAllowed: Boolean? = null,
        isAudioTranscodingAllowed: Boolean? = null
    ) {
        val config = getSessionConfig() ?: return
        homeSnapshotStore.persistHomeSnapshot(
            snapshotKey = buildSnapshotKey(config),
            featuredHomeItems = featuredHomeItems,
            continueWatchingItems = continueWatchingItems,
            nextUpItems = nextUpItems,
            homeLibrarySections = homeLibrarySections,
            myMediaLibraries = myMediaLibraries,
            username = username,
            serverName = serverName,
            serverUrl = serverUrl,
            profileImageUrl = profileImageUrl,
            isAdministrator = isAdministrator,
            isVideoTranscodingAllowed = isVideoTranscodingAllowed,
            isAudioTranscodingAllowed = isAudioTranscodingAllowed
        )
    }

    suspend fun clearPersistedHomeSnapshot() {
        homeSnapshotStore.clearPersistedHomeSnapshot()
    }

    suspend fun invalidateSessionCaches(clearHomeSnapshot: Boolean = true) {
        synchronized(this) {
            cachedSession = null
            cachedSessionKey = null
            cachedSessionConfig = null
            cachedImageAuthState = null
            cachedImageAuthAt = 0L
        }
        if (clearHomeSnapshot) {
            homeSnapshotStore.clearPersistedHomeSnapshot()
        }
    }

    suspend fun getLatestItems(
        parentId: String? = null,
        includeItemTypes: String? = "Movie,Series",
        limit: Int? = 20,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,ProductionYear,OfficialRating,Overview,$IMAGE_ROW_FIELDS"
    ): Result<List<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))

            val response = session.api.getLatestItems(
                userId = session.userId,
                parentId = parentId,
                includeItemTypes = includeItemTypes,
                limit = limit,
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch latest items: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSuggestions(
        mediaType: String = "Movie,Series",
        limit: Int = 15,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,ProductionYear,Overview,$IMAGE_ROW_FIELDS"
    ): Result<List<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))
            val route = SuggestionsEndpoint(
                serverType = session.serverType,
                userId = session.userId
            )
            val isEmby = route.userIdQuery == null
            val response = session.api.getSuggestions(
                endpoint = route.endpoint,
                userId = route.userIdQuery,
                mediaType = null,
                type = if (isEmby) null else mediaType,
                includeItemTypes = if (isEmby) mediaType else null,
                limit = limit,
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                val items = response.body()!!.items
                    .orEmpty()
                    .asSequence()
                    .filter { it.id != null && !it.name.isNullOrBlank() }
                    .distinctBy { it.id }
                    .take(limit)
                    .toList()
                Result.success(items)
            } else {
                Result.failure(Exception("Failed to fetch suggestions: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMovieRecommendations(
        parentId: String? = null,
        categoryLimit: Int = 8,
        itemLimit: Int = 16,
        fields: String? = "Genres,CommunityRating,ProductionYear,Overview,SeriesName,SeriesId,ParentIndexNumber,IndexNumber,EpisodeCount,RecursiveItemCount,ChildCount,UserData,People"
    ): Result<List<RecommendationDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getMovieRecommendations(
                userId = userId,
                parentId = parentId,
                categoryLimit = categoryLimit,
                itemLimit = itemLimit,
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                val recommendations = response.body().orEmpty()
                    .map { row ->
                        row.copy(
                            items = row.items
                                .orEmpty()
                                .filter { item -> item.id != null && !item.name.isNullOrBlank() }
                                .distinctBy { item -> item.id }
                        )
                    }
                    .filter { it.items.orEmpty().isNotEmpty() }
                Result.success(recommendations)
            } else {
                Result.failure(Exception("Failed to fetch movie recommendations: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun SuggestionsEndpoint(
        serverType: ServerType?,
        userId: String
    ): SuggestionsRoute {
        val isEmby = serverType == ServerType.EMBY
        return if (isEmby) {
            SuggestionsRoute(endpoint = "Users/$userId/Suggestions", userIdQuery = null)
        } else {
            SuggestionsRoute(endpoint = "Items/Suggestions", userIdQuery = userId)
        }
    }

    suspend fun getItemById(itemId: String): Result<BaseItemDto> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))
            val detailFields = "People,Studios,Genres,Overview,ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,OfficialRating,UserData,Chapters,ProviderIds,IndexNumber,ParentIndexNumber"

            val response = api.getItemById(
                userId = userId,
                itemId = itemId,
                fields = detailFields
            )

            if (response.isSuccessful && response.body() != null) {
                val item = response.body()!!
                val mappedItem = if (
                    item.type == "Episode" &&
                    (item.officialRating.isNullOrBlank() || item.seriesName.isNullOrBlank()) &&
                    !item.seriesId.isNullOrBlank()
                ) {
                    val seriesResponse = api.getItemById(
                        userId = userId,
                        itemId = item.seriesId!!,
                        fields = "OfficialRating,Name"
                    )

                    val seriesItem = seriesResponse.body()
                    val seriesRating = seriesItem?.officialRating?.takeIf { it.isNotBlank() }
                    val seriesName = seriesItem?.name?.takeIf { it.isNotBlank() }

                    if (seriesResponse.isSuccessful && (seriesRating != null || seriesName != null)) {
                        item.copy(
                            officialRating = seriesRating ?: item.officialRating,
                            seriesName = item.seriesName?.takeIf { it.isNotBlank() } ?: seriesName
                        )
                    } else {
                        item
                    }
                } else {
                    item
                }

                Result.success(mappedItem)
            } else {
                Result.failure(Exception("Failed to fetch item: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCommunityPlaybackSegments(item: BaseItemDto): Result<PlaybackSegments?> {
        val theIntroDbSegments = try {
            theIntroDbClient.getPlaybackSegments(item)
        } catch (e: Exception) {
            null
        }
        if (theIntroDbSegments?.intro != null) {
            return Result.success(theIntroDbSegments)
        }

        val introDbSegments = try {
            introDbClient.getPlaybackSegments(item)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val mergedSegments = when {
            theIntroDbSegments == null && introDbSegments == null -> null
            theIntroDbSegments == null -> introDbSegments
            introDbSegments?.intro == null -> theIntroDbSegments.takeIf { it.hasAnySegments() }
            else -> theIntroDbSegments.copy(intro = theIntroDbSegments.intro ?: introDbSegments.intro)
        }

        return Result.success(mergedSegments)
    }

    suspend fun getSimilarItems(
        itemId: String,
        limit: Int = 12,
        fields: String? = "Overview,Genres,CommunityRating,OfficialRating,UserData,$SIMILAR_ROW_FIELDS"
    ): Result<List<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getSimilarItems(
                itemId = itemId,
                userId = userId,
                limit = limit,
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                val queryResult = response.body()!!
                Result.success(queryResult.items.orEmpty().filter { it.id != itemId })
            } else {
                Result.failure(Exception("Failed to fetch similar items: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserItems(
        parentId: String? = null,
        personIds: String? = null,
        genres: String? = null,
        genreIds: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        limit: Int? = null,
        startIndex: Int? = null,
        filters: String? = null,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,ProductionYear,OfficialRating,Overview,$IMAGE_ROW_FIELDS"
    ): Result<QueryResult<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getUserItems(
                userId = userId,
                parentId = parentId,
                personIds = personIds,
                genres = genres,
                genreIds = genreIds,
                includeItemTypes = includeItemTypes,
                recursive = recursive,
                sortBy = sortBy,
                sortOrder = sortOrder,
                limit = limit,
                startIndex = startIndex,
                filters = filters,
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch user items: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getItemsForPerson(
        personId: String,
        limit: Int = 240
    ): Result<List<BaseItemDto>> {
        return getUserItems(
            personIds = personId,
            includeItemTypes = "Movie,Series,Episode",
            recursive = true,
            sortBy = "SortName",
            sortOrder = "Ascending",
            limit = limit,
            fields = PERSON_CREDIT_ROW_FIELDS
        ).map { result ->
            result.items
                .orEmpty()
                .filter { it.id != null && !it.name.isNullOrBlank() }
                .distinctBy { it.id }
        }
    }

    suspend fun getFavoriteItems(
        includeItemTypes: String? = "Movie,Series,Episode",
        limit: Int? = null,
        startIndex: Int? = null
    ): Result<QueryResult<BaseItemDto>> {
        return getUserItems(
            includeItemTypes = includeItemTypes,
            recursive = true,
            sortBy = "DateCreated",
            sortOrder = "Descending",
            limit = limit,
            startIndex = startIndex,
            filters = "IsFavorite",
            fields = "SeriesName,SeriesId,EpisodeCount,RecursiveItemCount,ChildCount,IndexNumber,ParentIndexNumber,ProductionYear,RunTimeTicks,Overview,DateCreated,$IMAGE_ROW_FIELDS"
        )
    }

    suspend fun setFavoriteStatus(itemId: String, isFavorite: Boolean): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = if (isFavorite) {
                api.markAsFavorite(userId = userId, itemId = itemId)
            } else {
                api.unmarkAsFavorite(userId = userId, itemId = itemId)
            }

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(
                    Exception(
                        "Failed to ${if (isFavorite) "favorite" else "unfavorite"} item: ${response.code()} - ${response.message()}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPlayedStatus(itemId: String, played: Boolean): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = if (played) {
                api.markAsPlayed(userId = userId, itemId = itemId)
            } else {
                api.unmarkAsPlayed(userId = userId, itemId = itemId)
            }

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(
                    Exception(
                        "Failed to ${if (played) "mark played" else "mark unplayed"} item: ${response.code()} - ${response.message()}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFromContinueWatching(item: BaseItemDto, playedOverride: Boolean? = null): Result<Unit> {
        val itemId = item.id ?: return Result.failure(Exception("Item ID not available"))
        return clearContinueWatchingOnServer(
            itemId = itemId,
            played = playedOverride ?: item.userData?.played,
            isFavorite = item.userData?.isFavorite,
            likes = item.userData?.likes,
            rating = item.userData?.rating,
            key = item.userData?.key
        )
    }

    suspend fun removeFromContinueWatching(itemId: String): Result<Unit> {
        return clearContinueWatchingOnServer(itemId = itemId, played = null)
    }

    suspend fun markAsWatchedAndRemoveFromContinueWatching(item: BaseItemDto): Result<Unit> {
        val itemId = item.id ?: return Result.failure(Exception("Item ID not available"))
        val clearResult = clearContinueWatchingOnServer(
            itemId = itemId,
            played = null,
            isFavorite = item.userData?.isFavorite,
            likes = item.userData?.likes,
            rating = item.userData?.rating,
            key = item.userData?.key,
            verifyRemoval = false
        )
        if (clearResult.isFailure) return clearResult

        val playedResult = setPlayedStatus(itemId = itemId, played = true)
        if (playedResult.isFailure) return playedResult

        return verifyPlayedAndNotInResume(itemId)
    }

    private suspend fun clearContinueWatchingOnServer(
        itemId: String,
        played: Boolean? = null,
        isFavorite: Boolean? = null,
        likes: Boolean? = null,
        rating: Double? = null,
        key: String? = null,
        verifyRemoval: Boolean = true
    ): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))
            val userDataResponse = api.updateUserItemData(
                userId = userId,
                itemId = itemId,
                request = com.grmemby.data.model.UpdateUserItemDataRequest(
                    rating = rating,
                    playbackPositionTicks = 0L,
                    isFavorite = isFavorite,
                    likes = likes,
                    played = played,
                    key = key,
                    itemId = itemId
                )
            )

            val progressResult = reportPlaybackProgress(itemId = itemId, positionTicks = 0L, isPaused = true)
            val stoppedResult = reportPlaybackStopped(itemId = itemId, positionTicks = 0L)

            val acceptedByServer = userDataResponse.isSuccessful || progressResult.isSuccess || stoppedResult.isSuccess
            if (!acceptedByServer) {
                return Result.failure(
                    Exception(
                        "Failed to clear resume on server: ${userDataResponse.code()} - ${userDataResponse.message()}"
                    )
                )
            }

            if (verifyRemoval) {
                verifyResumeItemRemoved(api = api, userId = userId, itemId = itemId)
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun verifyResumeItemRemoved(
        api: MediaServerApi,
        userId: String,
        itemId: String
    ): Result<Unit> {
        repeat(5) { attempt ->
            if (attempt > 0) delay(500L)
            val response = api.getResumeItems(
                userId = userId,
                includeItemTypes = "Movie,Episode",
                limit = 200,
                fields = "UserData"
            )
            if (!response.isSuccessful) {
                return Result.failure(
                    Exception("Failed to verify resume removal: ${response.code()} - ${response.message()}")
                )
            }
            val stillInResume = response.body()?.items.orEmpty().any { it.id == itemId }
            if (!stillInResume) return Result.success(Unit)
        }
        return Result.failure(Exception("Resume item still present after server update"))
    }

    private suspend fun verifyPlayedAndNotInResume(itemId: String): Result<Unit> {
        val api = getApi() ?: return Result.failure(Exception("API not available"))
        val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))
        repeat(5) { attempt ->
            if (attempt > 0) delay(500L)
            val itemResponse = api.getItemById(
                userId = userId,
                itemId = itemId,
                fields = "UserData"
            )
            val resumeResponse = api.getResumeItems(
                userId = userId,
                includeItemTypes = "Movie,Episode",
                limit = 200,
                fields = "UserData"
            )
            if (!itemResponse.isSuccessful) {
                return Result.failure(
                    Exception("Failed to verify played status: ${itemResponse.code()} - ${itemResponse.message()}")
                )
            }
            if (!resumeResponse.isSuccessful) {
                return Result.failure(
                    Exception("Failed to verify resume removal: ${resumeResponse.code()} - ${resumeResponse.message()}")
                )
            }
            val played = itemResponse.body()?.userData?.played == true
            val stillInResume = resumeResponse.body()?.items.orEmpty().any { it.id == itemId }
            if (played && !stillInResume) return Result.success(Unit)
        }
        return Result.failure(Exception("Item was not persisted as played or still appears in resume"))
    }


    suspend fun getUserViews(): Result<QueryResult<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))
            val response = session.api.getUserViews(session.userId)

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch user views: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getHomeLibrarySections(
        maxLibraries: Int? = null,
        itemsPerLibrary: Int = 20
    ): Result<List<HomeLibrarySectionData>> = coroutineScope {
        val viewsResult = getUserViews()
        viewsResult.fold(
            onSuccess = { queryResult ->
                val libraries = queryResult.items
                    .orEmpty()
                    .asSequence()
                    .filter { library ->
                        val libraryId = library.id
                        val libraryName = library.name
                        val type = library.type
                        val collectionType = library.collectionType
                        libraryId != null &&
                            !libraryName.isNullOrBlank() &&
                            collectionType != "boxsets" &&
                            collectionType != "playlists" &&
                            collectionType != "folders" &&
                            (type == "CollectionFolder" || type == "Folder") &&
                            (collectionType == "movies" || collectionType == "tvshows" || collectionType == null)
                    }
                    .distinctBy { it.id }
                    .let { sequence ->
                        if (maxLibraries != null) sequence.take(maxLibraries) else sequence
                    }
                    .toList()

                if (libraries.isEmpty()) {
                    return@fold Result.success(emptyList<HomeLibrarySectionData>())
                }

                val session = getApiSession() ?: return@fold Result.failure(
                    Exception("Session not available")
                )
                val fields = "SeriesName,SeriesId,EpisodeCount,RecursiveItemCount,ChildCount,ProductionYear,EndDate,IndexNumber,ParentIndexNumber,$IMAGE_ROW_FIELDS"
                val sectionFetchSemaphore = Semaphore(4)

                val sections = libraries.map { library ->
                    async(Dispatchers.IO) {
                        sectionFetchSemaphore.withPermit {
                            val libraryId = library.id ?: return@withPermit null
                            val includeItemTypes = when (library.collectionType) {
                                "movies" -> "Movie"
                                "tvshows" -> "Episode,Series"
                                else -> "Movie,Series,Episode"
                            }

                            val latestItemsResponse = runCatching {
                                session.api.getLatestItems(
                                    userId = session.userId,
                                    parentId = libraryId,
                                    includeItemTypes = includeItemTypes,
                                    limit = itemsPerLibrary,
                                    fields = fields
                                )
                            }.getOrNull()

                            val latestItems: List<BaseItemDto> =
                                if (latestItemsResponse?.isSuccessful == true) {
                                    latestItemsResponse.body().orEmpty()
                                } else {
                                    emptyList()
                                }

                            val items = latestItems
                                .asSequence()
                                .filter { it.id != null && !it.name.isNullOrBlank() }
                                .distinctBy { it.id }
                                .toList()

                            HomeLibrarySectionData(
                                library = library,
                                items = items
                            )
                        }
                    }
                }.awaitAll()
                    .filterNotNull()
                    .filter { it.items.isNotEmpty() }

                Result.success(sections)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    private suspend fun getImageAuthState(): ImageAuthState {
        val now = System.currentTimeMillis()
        cachedImageAuthState?.let { cached ->
            if (now - cachedImageAuthAt < imageAuthCacheTtlMs) {
                return cached
            }
        }

        val config = getSessionConfig()
        val state = ImageAuthState(
            serverUrl = config?.serverUrl
        )
        cachedImageAuthState = state
        cachedImageAuthAt = now
        return state
    }

    suspend fun getImageUrlString(
        itemId: String,
        imageType: String = "Primary",
        width: Int? = null,
        height: Int? = null,
        quality: Int? = 90,
        enableImageEnhancers: Boolean = true,
        imageTag: String? = null
    ): String? {
        val authState = getImageAuthState()
        return getImageUrlStringForServer(
            serverUrl = authState.serverUrl,
            itemId = itemId,
            imageType = imageType,
            width = width,
            height = height,
            quality = quality,
            enableImageEnhancers = enableImageEnhancers,
            imageTag = imageTag
        )
    }

    fun getImageUrlStringForServer(
        serverUrl: String?,
        itemId: String,
        imageType: String = "Primary",
        width: Int? = null,
        height: Int? = null,
        quality: Int? = 90,
        enableImageEnhancers: Boolean = true,
        imageTag: String? = null
    ): String? {
        if (serverUrl.isNullOrEmpty() || itemId.isBlank()) {
            return null
        }

        val queryParams = mutableListOf<Pair<String, String?>>()
        width?.let { queryParams.add("maxWidth" to it.toString()) }
        height?.let { queryParams.add("maxHeight" to it.toString()) }
        quality?.let { queryParams.add("quality" to it.toString()) }
        imageTag?.takeIf { it.isNotBlank() }?.let { queryParams.add("tag" to it) }
        if (!enableImageEnhancers) {
            queryParams.add("HasImageEnhancers" to "false")
            queryParams.add("EnableImageEnhancers" to "false")
        }
        return buildServerUrl(baseUrl = serverUrl, encodedPath = "Items/$itemId/Images/$imageType", queryParams = queryParams)
    }

    fun getImageUrl(
        itemId: String,
        imageType: String = "Primary",
        width: Int? = null,
        height: Int? = null,
        quality: Int? = 90,
        enableImageEnhancers: Boolean = true,
        imageTag: String? = null
    ): Flow<String?> {
        return imageAuthStateFlow.map { authState ->
            val serverUrl = authState.serverUrl
            if (serverUrl != null && itemId.isNotEmpty()) {
                val queryParams = mutableListOf<Pair<String, String?>>()
                width?.let { queryParams.add("maxWidth" to it.toString()) }
                height?.let { queryParams.add("maxHeight" to it.toString()) }
                quality?.let { queryParams.add("quality" to it.toString()) }
                imageTag?.takeIf { it.isNotBlank() }?.let { queryParams.add("tag" to it) }
                if (!enableImageEnhancers) {
                    queryParams.add("HasImageEnhancers" to "false")
                    queryParams.add("EnableImageEnhancers" to "false")
                }
                buildServerUrl(baseUrl = serverUrl, encodedPath = "Items/$itemId/Images/$imageType", queryParams = queryParams)
            } else {
                null
            }
        }
    }

    fun getBackdropImageUrl(
        itemId: String,
        imageIndex: Int = 0,
        width: Int? = null,
        height: Int? = null,
        quality: Int? = 90,
        enableImageEnhancers: Boolean = true,
        imageTag: String? = null
    ): Flow<String?> {
        return imageAuthStateFlow.map { authState ->
            val serverUrl = authState.serverUrl
            if (serverUrl != null && itemId.isNotEmpty()) {
                val queryParams = mutableListOf<Pair<String, String?>>()
                width?.let { queryParams.add("maxWidth" to it.toString()) }
                height?.let { queryParams.add("maxHeight" to it.toString()) }
                quality?.let { queryParams.add("quality" to it.toString()) }
                imageTag?.takeIf { it.isNotBlank() }?.let { queryParams.add("tag" to it) }
                if (!enableImageEnhancers) {
                    queryParams.add("HasImageEnhancers" to "false")
                    queryParams.add("EnableImageEnhancers" to "false")
                }
                buildServerUrl(baseUrl = serverUrl, encodedPath = "Items/$itemId/Images/Backdrop/$imageIndex", queryParams = queryParams)
            } else {
                null
            }
        }
    }

    suspend fun getRecentlyAddedMovies(limit: Int = 10): Result<List<BaseItemDto>> {
        return getLatestItems(
            includeItemTypes = "Movie",
            limit = limit,
            fields = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,ProductionYear,Overview,$IMAGE_ROW_FIELDS"
        )
    }

    suspend fun getRecentlyAddedSeries(limit: Int = 10): Result<List<BaseItemDto>> {
        return getLatestItems(
            includeItemTypes = "Series",
            limit = limit,
            fields = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,ProductionYear,Overview,$IMAGE_ROW_FIELDS"
        )
    }

    suspend fun getRecentlyAddedEpisodes(limit: Int = 10): Result<List<BaseItemDto>> {
        return getLatestItems(
            includeItemTypes = "Episode",
            limit = limit
        )
    }

    suspend fun getGenres(
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean? = true,
        sortBy: String? = "SortName",
        sortOrder: String? = "Ascending"
    ): Result<List<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getGenres(
                userId = userId,
                parentId = parentId,
                includeItemTypes = includeItemTypes,
                recursive = recursive,
                sortBy = sortBy,
                sortOrder = sortOrder,
                enableTotalRecordCount = true,
                enableImages = false
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val items = body.items ?: emptyList()

                Result.success(items)
            } else {
                val errorMsg = "Failed to fetch genres: ${response.code()} - ${response.message()}"

                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {

            Result.failure(e)
        }
    }

    /**
     * Get filtered genres like the official Jellyfin web client
     * This filters out redundant individual genres when compound genres exist
     */
    suspend fun getFilteredGenres(
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean? = true,
        sortBy: String? = "SortName",
        sortOrder: String? = "Ascending"
    ): Result<List<BaseItemDto>> {
        return try {
            val genresResult = getGenres(parentId, includeItemTypes, recursive, sortBy, sortOrder)

            genresResult.fold(
                onSuccess = { genres ->
                    val filteredGenres = filterRedundantGenres(genres, includeItemTypes)
                    Result.success(filteredGenres)
                },
                onFailure = { exception ->
                    Result.failure(exception)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Filter out redundant individual genres when compound genres exist
     * Based on how the official Jellyfin web client handles genre display
     */
    private fun filterRedundantGenres(genres: List<BaseItemDto>, includeItemTypes: String? = null): List<BaseItemDto> {
        val genreNames = genres.mapNotNull { it.name }.toSet()

        // Define compound genre patterns and their individual components
        // Based on common TMDB/TVDB genre patterns
        val compoundGenreMap = mapOf(
            "Action & Adventure" to setOf("Action", "Adventure"),
            "Sci-Fi & Fantasy" to setOf("Sci-Fi", "Science Fiction", "Fantasy", "Sci Fi", "SciFi"),
            "Crime & Mystery" to setOf("Crime", "Mystery"),
            "Comedy & Drama" to setOf("Comedy", "Drama"),
            "Horror & Thriller" to setOf("Horror", "Thriller"),
            "Romance & Drama" to setOf("Romance", "Drama"),
            "War & Politics" to setOf("War", "Politics"),
            "Kids & Family" to setOf("Kids", "Family", "Children"),
            "News & Documentary" to setOf("News", "Documentary"),
            "Reality & Talk Show" to setOf("Reality", "Talk Show", "Talk"),
            "Soap & Drama" to setOf("Soap", "Drama")
        )

        // Additional genre consolidation rules (prefer one over the other)
        val genreConsolidationMap = mapOf(
            "Talk" to setOf("Talk-Show", "Talk Show"),
            "Reality" to setOf("Reality TV", "Reality-TV"),
            "Mystery" to setOf("Thriller"),
            "Animation" to setOf("Anime")
        )

        // Find which compound genres actually exist in the data
        val existingCompoundGenres = genreNames.filter { genreName ->
            compoundGenreMap.keys.any { compound ->
                genreName.equals(compound, ignoreCase = true)
            }
        }

        // Collect all individual genres that should be filtered out
        val genresToFilter = mutableSetOf<String>()
        existingCompoundGenres.forEach { compoundGenre ->
            compoundGenreMap.entries.find {
                it.key.equals(compoundGenre, ignoreCase = true)
            }?.value?.let { individuals ->
                genresToFilter.addAll(individuals)
            }
        }

        // Also handle any other " & " patterns dynamically
        val dynamicCompoundGenres = genreNames.filter {
            it.contains(" & ") && !compoundGenreMap.keys.any { compound ->
                it.equals(compound, ignoreCase = true)
            }
        }

        dynamicCompoundGenres.forEach { compound ->
            val parts = compound.split(" & ").map { it.trim() }
            genresToFilter.addAll(parts)
        }

        // Apply genre consolidation rules
        genreConsolidationMap.forEach { (preferredGenre, genresToMerge) ->
            // If the preferred genre exists, filter out the genres to merge
            if (genreNames.any { it.equals(preferredGenre, ignoreCase = true) }) {
                genresToFilter.addAll(genresToMerge)
            }
        }

        // Additional filtering for TV Shows - exclude Romance and Game Show
        val tvShowExcludedGenres = setOf("Romance", "Game Show", "Game-Show")
        val isTVShows = includeItemTypes?.contains("Series", ignoreCase = true) == true

        // Filter the genres
        return genres.filter { genre ->
            val genreName = genre.name ?: return@filter false

            // Exclude specific genres for TV Shows
            if (isTVShows && tvShowExcludedGenres.any { it.equals(genreName, ignoreCase = true) }) {
                return@filter false
            }

            // Always keep compound genres
            if (genreName.contains(" & ")) {
                true
            } else {
                // Keep individual genres only if they're not part of any compound genre
                !genresToFilter.any { filterGenre ->
                    genreName.equals(filterGenre, ignoreCase = true)
                }
            }
        }.sortedBy { it.name } // Sort alphabetically for consistent display
    }

    suspend fun getItemsByGenre(
        genreId: String,
        includeItemTypes: String? = null,
        limit: Int? = 20
    ): Result<List<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))

            val response = session.api.getItemsByGenre(
                userId = session.userId,
                genreIds = genreId,
                includeItemTypes = includeItemTypes,
                recursive = true,
                limit = limit,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                fields = "Genres,RecursiveItemCount,ChildCount,EpisodeCount,ProductionYear,PremiereDate,EndDate,SeriesName,SeriesId"
            )

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val items = body.items ?: emptyList()

                Result.success(items)
            } else {
                val errorMsg = "Failed to fetch items by genreId '$genreId': ${response.code()} - ${response.message()}"

                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {

            Result.failure(Exception("Error fetching items by genreId '$genreId': ${e.message}", e))
        }
    }

    suspend fun getResumeItems(
        parentId: String? = null,
        includeItemTypes: String? = "Movie,Episode",
        limit: Int? = null,
        startIndex: Int? = null,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,ProductionYear," +
            "ImageTags,PrimaryImageAspectRatio,BackdropImageTags,ParentPrimaryImageItemId," +
            "ParentPrimaryImageTag,SeriesPrimaryImageTag"
    ): Result<List<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))

            val response = session.api.getResumeItems(
                userId = session.userId,
                parentId = parentId,
                includeItemTypes = includeItemTypes,
                limit = limit,
                startIndex = startIndex,
                recursive = true,
                sortBy = "DatePlayed",
                sortOrder = "Descending",
                fields = fields
            )

            if (response.isSuccessful && response.body() != null) {
                val queryResult = response.body()!!
                Result.success(queryResult.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch resume items: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getNextUpItems(
        seriesId: String? = null,
        parentId: String? = null,
        limit: Int? = null,
        startIndex: Int? = null,
        fields: String? = "Overview,SeriesName,SeriesId,SeasonName,SeasonId,ImageTags," +
            "PrimaryImageAspectRatio,BackdropImageTags,ParentPrimaryImageItemId," +
            "ParentPrimaryImageTag,SeriesPrimaryImageTag"
    ): Result<List<BaseItemDto>> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))
            val legacyNextUp = if (session.serverType == ServerType.EMBY) true else null

            val response = session.api.getNextUp(
                userId = session.userId,
                seriesId = seriesId,
                parentId = parentId,
                limit = limit,
                startIndex = startIndex,
                legacyNextUp = legacyNextUp,
                fields = fields,
                enableUserData = true,
                enableImages = true
            )

            if (response.isSuccessful && response.body() != null) {
                val queryResult = response.body()!!
                Result.success(queryResult.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch next up items: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSeasons(seriesId: String): Result<List<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getSeasons(
                seriesId = seriesId,
                userId = userId,
                fields = SEASON_ROW_FIELDS
            )

            if (response.isSuccessful && response.body() != null) {
                val queryResult = response.body()!!
                Result.success(queryResult.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch seasons: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getEpisodes(
        seriesId: String,
        seasonId: String? = null,
        limit: Int? = null,
        startIndex: Int? = null
    ): Result<List<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getEpisodes(
                seriesId = seriesId,
                userId = userId,
                seasonId = seasonId,
                fields = EPISODE_ROW_FIELDS,
                limit = limit,
                startIndex = startIndex
            )

            if (response.isSuccessful && response.body() != null) {
                val queryResult = response.body()!!
                Result.success(queryResult.items ?: emptyList())
            } else {
                Result.failure(Exception("Failed to fetch episodes: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): Result<UserDto> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))

            val response = api.getUserById(userId)

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch user info: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserProfileImageUrl(primaryImageTag: String? = null): String? {
        val config = getSessionConfig()
        val serverUrl = config?.serverUrl
        val userId = config?.userId

        return if (serverUrl != null && userId != null) {
            buildServerUrl(
                baseUrl = serverUrl,
                encodedPath = "Users/$userId/Images/Primary",
                queryParams = listOf("tag" to primaryImageTag)
            )
        } else {
            null
        }
    }

    // Player-related methods

    /**
     * Get playback information for a media item
     */
    suspend fun getPlaybackInfo(
        itemId: String,
        maxStreamingBitrate: Int? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO
    ): Result<com.grmemby.data.model.PlaybackInfoResponse> {
        return try {
            val session = getApiSession() ?: return Result.failure(Exception("Session not available"))
            val api = session.api
            val userId = session.userId
            val serverUrl = session.baseUrl
            val serverType = session.serverType
            val forceTranscode = (maxStreamingBitrate ?: 0) > 0
            val normalizedSubtitleStreamIndex = normalizeSubtitleStreamIndex(subtitleStreamIndex)
            val preferGetPlaybackInfo = (
                serverType == ServerType.EMBY || serverType == ServerType.JELLYFIN
            ) &&
                !forceTranscode && audioTranscodeMode == AudioTranscodeMode.AUTO
            val enableDirectPlay = if (forceTranscode) false else true
            val enableDirectStream = if (forceTranscode) false else true
            val enableTranscoding = true
            val deviceProfile = PlaybackDeviceProfileFactory.create(
                maxStreamingBitrate = maxStreamingBitrate?.toLong(),
                audioTranscodeMode = audioTranscodeMode
            )
            var preferredGetFailure: Throwable? = null
            if (preferGetPlaybackInfo) {
                val preferredGetResult = runCatching {
                    api.getPlaybackInfoGet(
                        itemId = itemId,
                        userId = userId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        audioStreamIndex = audioStreamIndex,
                        subtitleStreamIndex = normalizedSubtitleStreamIndex,
                        enableDirectPlay = enableDirectPlay,
                        enableDirectStream = enableDirectStream,
                        enableTranscoding = enableTranscoding
                    )
                }
                val getResponse = preferredGetResult.getOrNull()
                if (getResponse != null && getResponse.isSuccessful && getResponse.body() != null) {
                    val responseBody = PlaybackUrlBuilder.playbackInfoUrls(
                        serverUrl = serverUrl,
                        playbackInfo = getResponse.body()!!
                    )
                    return Result.success(responseBody)
                }
                preferredGetFailure = preferredGetResult.exceptionOrNull()
                if (preferredGetFailure != null) {
                    Log.w(
                        "PlaybackInfo",
                        "Preferred GET PlaybackInfo failed; falling back to POST reason=${preferredGetFailure.message ?: preferredGetFailure::class.java.simpleName}"
                    )
                }
            }

            val playbackInfoRequest = PlaybackInfoRequest(
                userId = userId,
                mediaSourceId = null,
                maxStreamingBitrate = maxStreamingBitrate?.toLong(),
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = normalizedSubtitleStreamIndex,
                enableDirectPlay = enableDirectPlay,
                enableDirectStream = enableDirectStream,
                enableTranscoding = enableTranscoding,
                deviceProfile = deviceProfile
            )

            val postResponse = api.getPlaybackInfoPost(
                itemId = itemId,
                request = playbackInfoRequest
            )

            if (postResponse.isSuccessful && postResponse.body() != null) {
                val responseBody = PlaybackUrlBuilder.playbackInfoUrls(
                    serverUrl = serverUrl,
                    playbackInfo = postResponse.body()!!
                )
                return Result.success(responseBody)
            }

            val fallbackGetResponse = if (preferGetPlaybackInfo) {
                null
            } else {
                runCatching {
                    api.getPlaybackInfoGet(
                        itemId = itemId,
                        userId = userId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        audioStreamIndex = audioStreamIndex,
                        subtitleStreamIndex = normalizedSubtitleStreamIndex,
                        enableDirectPlay = enableDirectPlay,
                        enableDirectStream = enableDirectStream,
                        enableTranscoding = enableTranscoding
                    )
                }.getOrElse { error ->
                    Log.w(
                        "PlaybackInfo",
                        "Fallback GET PlaybackInfo failed reason=${error.message ?: error::class.java.simpleName}"
                    )
                    null
                }
            }

            if (fallbackGetResponse != null && fallbackGetResponse.isSuccessful && fallbackGetResponse.body() != null) {
                val responseBody = PlaybackUrlBuilder.playbackInfoUrls(
                    serverUrl = serverUrl,
                    playbackInfo = fallbackGetResponse.body()!!
                )
                Result.success(responseBody)
            } else {
                Result.failure(
                    Exception(
                        "Failed to fetch playback info: POST ${postResponse.code()} / GET ${fallbackGetResponse?.code() ?: "skipped"}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build authenticated direct-download request data for DownloadManager.
     */
    suspend fun getItemDownloadRequest(itemId: String): Result<ItemDownloadRequest> {
        return try {
            val config = getSessionConfig() ?: return Result.failure(Exception("Session not available"))
            val serverUrl = config.serverUrl
            val accessToken = config.accessToken
            if (accessToken.isNullOrBlank()) {
                return Result.failure(Exception("Access token not available"))
            }

            val item = getItemById(itemId).getOrNull()
            val itemDisplayName = item?.name?.takeIf { it.isNotBlank() }
            val itemExtension = when {
                !item?.container.isNullOrBlank() -> item?.container
                else -> item?.path
                    ?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.takeIf { it.isNotBlank() }
            }?.trimStart('.')
                ?.lowercase()

            val needsPlaybackInfo = itemDisplayName.isNullOrBlank() || itemExtension.isNullOrBlank()
            val mediaSource = if (needsPlaybackInfo) {
                getPlaybackInfo(itemId).getOrNull()?.mediaSources?.firstOrNull()
            } else {
                null
            }

            val displayName = itemDisplayName
                ?: mediaSource?.name?.takeIf { it.isNotBlank() }
                ?: "grmemby_$itemId"

            val extension = itemExtension
                ?: mediaSource?.container
                    ?.trimStart('.')
                    ?.lowercase()

            val downloadUrl = buildServerUrl(
                baseUrl = serverUrl,
                encodedPath = "Items/$itemId/Download",
                queryParams = listOf("name" to displayName)
            )

            Result.success(
                ItemDownloadRequest(
                    itemId = itemId,
                    displayName = displayName,
                    downloadUrl = downloadUrl,
                    authToken = accessToken,
                    fileExtension = extension
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPlaybackRequest(
        itemId: String,
        maxStreamingBitrate: Int? = null,
        maxStreamingHeight: Int? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
        playbackInfo: com.grmemby.data.model.PlaybackInfoResponse? = null,
        includeAccessToken: Boolean = false
    ): Result<PlaybackRequest> {
        val config = getSessionConfig() ?: return Result.failure(Exception("Session not available"))
        val authContext = createPlaybackAuthContext(config)
        val activePlaybackInfo = playbackInfo ?: run {
            val playbackInfoResult = getPlaybackInfo(
                itemId = itemId,
                maxStreamingBitrate = maxStreamingBitrate,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                audioTranscodeMode = audioTranscodeMode
            )
            if (playbackInfoResult.isFailure) {
                return Result.failure(
                    playbackInfoResult.exceptionOrNull() ?: Exception("Failed to get playback info")
                )
            }
            playbackInfoResult.getOrNull()
        } ?: return Result.failure(Exception("Playback info not available"))

        return PlaybackUrlBuilder.createLocalPlaybackRequest(
            authContext = authContext,
            itemId = itemId,
            playbackInfo = activePlaybackInfo,
            options = PlaybackStreamOptions(
                maxStreamingBitrate = maxStreamingBitrate,
                maxStreamingHeight = maxStreamingHeight,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                audioTranscodeMode = audioTranscodeMode,
                includeAccessToken = includeAccessToken
            )
        )
    }

    suspend fun getCastStreamingUrl(
        itemId: String,
        maxStreamingBitrate: Int? = null,
        maxStreamingHeight: Int? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
        playbackInfo: com.grmemby.data.model.PlaybackInfoResponse? = null
    ): Result<String> {
        val config = getSessionConfig() ?: return Result.failure(Exception("Session not available"))
        val authContext = createPlaybackAuthContext(config)
        val activePlaybackInfo = playbackInfo ?: run {
            val playbackInfoResult = getPlaybackInfo(
                itemId = itemId,
                maxStreamingBitrate = maxStreamingBitrate,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                audioTranscodeMode = audioTranscodeMode
            )
            if (playbackInfoResult.isFailure) {
                return Result.failure(
                    playbackInfoResult.exceptionOrNull() ?: Exception("Failed to get playback info")
                )
            }
            playbackInfoResult.getOrNull()
        } ?: return Result.failure(Exception("Playback info not available"))

        return PlaybackUrlBuilder.createCastStreamingUrl(
            authContext = authContext,
            itemId = itemId,
            playbackInfo = activePlaybackInfo,
            options = PlaybackStreamOptions(
                maxStreamingBitrate = maxStreamingBitrate,
                maxStreamingHeight = maxStreamingHeight,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                audioTranscodeMode = audioTranscodeMode
            )
        )
    }

    /**
     * Get available audio tracks for a media item
     */
    suspend fun getAudioTracks(itemId: String): Result<List<com.grmemby.data.model.MediaStream>> {
        return try {
            val playbackInfoResult = getPlaybackInfo(itemId)
            if (playbackInfoResult.isFailure) {
                return Result.failure(playbackInfoResult.exceptionOrNull() ?: Exception("Failed to get playback info"))
            }

            val playbackInfo = playbackInfoResult.getOrNull()
            val mediaSource = playbackInfo?.mediaSources?.firstOrNull()
            val audioStreams = mediaSource?.mediaStreams?.filter { it.type == "Audio" } ?: emptyList()

            Result.success(audioStreams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get available video tracks for a media item
     */
    suspend fun getVideoTracks(itemId: String): Result<List<com.grmemby.data.model.MediaStream>> {
        return try {
            val playbackInfoResult = getPlaybackInfo(itemId)
            if (playbackInfoResult.isFailure) {
                return Result.failure(playbackInfoResult.exceptionOrNull() ?: Exception("Failed to get playback info"))
            }

            val playbackInfo = playbackInfoResult.getOrNull()
            val mediaSource = playbackInfo?.mediaSources?.firstOrNull()
            val videoStreams = mediaSource?.mediaStreams?.filter { it.type == "Video" } ?: emptyList()

            Result.success(videoStreams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get available subtitle tracks for a media item
     */
    suspend fun getSubtitleTracks(itemId: String): Result<List<com.grmemby.data.model.MediaStream>> {
        return try {
            val playbackInfoResult = getPlaybackInfo(itemId)
            if (playbackInfoResult.isFailure) {
                return Result.failure(playbackInfoResult.exceptionOrNull() ?: Exception("Failed to get playback info"))
            }

            val playbackInfo = playbackInfoResult.getOrNull()
            val mediaSource = playbackInfo?.mediaSources?.firstOrNull()
            val subtitleStreams = mediaSource?.mediaStreams?.filter { it.type == "Subtitle" } ?: emptyList()

            Result.success(subtitleStreams)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for items using the active Jellyfin/Emby server.
     */
    suspend fun searchItems(
        searchTerm: String,
        includeItemTypes: String? = "Movie,Series",
        limit: Int? = 50
    ): Result<List<BaseItemDto>> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))
            val userId = getUserId() ?: return Result.failure(Exception("User ID not available"))
            searchItemsWithApi(
                api = api,
                userId = userId,
                searchTerm = searchTerm,
                includeItemTypes = includeItemTypes,
                limit = limit
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search a saved server without changing the app's active server/session.
     */
    suspend fun searchItemsForSavedServer(
        savedServer: AuthRepository.SavedServer,
        searchTerm: String,
        includeItemTypes: String? = "Movie,Series",
        limit: Int? = 50
    ): Result<List<BaseItemDto>> {
        return try {
            val accessToken = secureSessionStore.getToken(savedServer.id)
                ?: return Result.failure(Exception("Saved session expired"))
            val serverType = runCatching { ServerType.valueOf(savedServer.serverTypeRaw) }
                .getOrDefault(ServerType.UNKNOWN)
            val api = NetworkModule.createMediaServerApi(
                baseUrl = savedServer.effectiveServerUrl,
                accessToken = accessToken,
                serverType = serverType,
                storageDir = context.filesDir,
                timeoutConfig = networkPreferences.getTimeoutConfig(),
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )
            searchItemsWithApi(
                api = api,
                userId = savedServer.userId,
                searchTerm = searchTerm,
                includeItemTypes = includeItemTypes,
                limit = limit
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load safe aggregate information for a saved server without switching the app's active
     * session. This is used by the Settings server-management cards, so lastPlayedAt is taken
     * from server-side UserData and is intentionally unrelated to SavedServer.lastUsedAt.
     */
    suspend fun getSavedServerOverview(
        savedServer: AuthRepository.SavedServer,
        includeLastPlayed: Boolean = true,
        fastMode: Boolean = false
    ): Result<SavedServerOverview> = withContext(Dispatchers.IO) {
        try {
            val accessToken = secureSessionStore.getToken(savedServer.id)
                ?: return@withContext Result.success(SavedServerOverview(isConnected = false))
            val serverType = runCatching { ServerType.valueOf(savedServer.serverTypeRaw) }
                .getOrDefault(ServerType.UNKNOWN)
            val overviewTimeoutConfig = networkPreferences.getTimeoutConfig().let { config ->
                if (fastMode) {
                    NetworkTimeoutConfig(
                        requestTimeoutMs = config.requestTimeoutMs.coerceAtMost(2_500),
                        connectionTimeoutMs = config.connectionTimeoutMs.coerceAtMost(1_000),
                        socketTimeoutMs = config.socketTimeoutMs.coerceAtMost(1_500)
                    )
                } else {
                    NetworkTimeoutConfig(
                        requestTimeoutMs = config.requestTimeoutMs.coerceAtMost(8_000),
                        connectionTimeoutMs = config.connectionTimeoutMs.coerceAtMost(2_500),
                        socketTimeoutMs = config.socketTimeoutMs.coerceAtMost(4_000)
                    )
                }
            }
            val api = NetworkModule.createMediaServerApi(
                baseUrl = savedServer.effectiveServerUrl,
                accessToken = accessToken,
                serverType = serverType,
                storageDir = context.filesDir,
                timeoutConfig = overviewTimeoutConfig,
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )

            val publicInfoStartedAt = SystemClock.elapsedRealtime()
            val fastOverviewTimeoutMs = 1_250L
            val publicInfoDeferred = async {
                if (fastMode) {
                    withTimeoutOrNull(fastOverviewTimeoutMs) {
                        runCatching { api.getPublicSystemInfo() }.getOrNull()
                    }
                } else {
                    runCatching { api.getPublicSystemInfo() }.getOrNull()
                }
            }
            val countsDeferred = async {
                if (fastMode) {
                    withTimeoutOrNull(fastOverviewTimeoutMs) {
                        runCatching { api.getItemsCounts(userId = savedServer.userId) }.getOrNull()
                    }
                } else {
                    runCatching { api.getItemsCounts(userId = savedServer.userId) }.getOrNull()
                }
            }
            val playedDeferred = if (includeLastPlayed) {
                async {
                    if (fastMode) {
                        withTimeoutOrNull(fastOverviewTimeoutMs) {
                            loadSavedServerLastPlaybackAt(api, savedServer.userId)
                        }
                    } else {
                        loadSavedServerLastPlaybackAt(api, savedServer.userId)
                    }
                }
            } else {
                null
            }

            val publicInfoResponse = publicInfoDeferred.await()
            val countsResponse = countsDeferred.await()
            val lastPlayedAt = playedDeferred?.await()
            val counts = countsResponse?.takeIf { it.isSuccessful }?.body()
            val fallbackCounts = if (!fastMode && countsResponse != null && (counts?.movieCount == null || counts.seriesCount == null)) {
                loadSavedServerOverviewCountsByItemQueries(api, savedServer.userId)
            } else {
                null
            }
            val movieCount = counts?.movieCount ?: fallbackCounts?.first
            val seriesCount = counts?.seriesCount ?: fallbackCounts?.second
            val publicInfoOk = publicInfoResponse?.isSuccessful == true
            val latencyMs = publicInfoResponse
                ?.takeIf { it.isSuccessful }
                ?.let {
                    (SystemClock.elapsedRealtime() - publicInfoStartedAt)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                }

            val connected = publicInfoOk ||
                countsResponse?.isSuccessful == true ||
                lastPlayedAt != null

            Result.success(
                SavedServerOverview(
                    movieCount = movieCount,
                    seriesCount = seriesCount,
                    lastPlayedAtEpochMs = lastPlayedAt,
                    latencyMs = latencyMs,
                    isConnected = connected
                )
            )
        } catch (_: Exception) {
            Result.success(SavedServerOverview(isConnected = false))
        }
    }

    suspend fun getSavedServerLastPlayedAt(
        savedServer: AuthRepository.SavedServer
    ): Result<Long?> = withContext(Dispatchers.IO) {
        try {
            val accessToken = secureSessionStore.getToken(savedServer.id)
                ?: return@withContext Result.success(null)
            val serverType = runCatching { ServerType.valueOf(savedServer.serverTypeRaw) }
                .getOrDefault(ServerType.UNKNOWN)
            val timeoutConfig = networkPreferences.getTimeoutConfig().let { config ->
                NetworkTimeoutConfig(
                    requestTimeoutMs = config.requestTimeoutMs.coerceAtMost(5_000),
                    connectionTimeoutMs = config.connectionTimeoutMs.coerceAtMost(1_500),
                    socketTimeoutMs = config.socketTimeoutMs.coerceAtMost(3_000)
                )
            }
            val api = NetworkModule.createMediaServerApi(
                baseUrl = savedServer.effectiveServerUrl,
                accessToken = accessToken,
                serverType = serverType,
                storageDir = context.filesDir,
                timeoutConfig = timeoutConfig,
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )
            Result.success(loadSavedServerLastPlaybackAt(api, savedServer.userId))
        } catch (_: Exception) {
            Result.success(null)
        }
    }

    private suspend fun loadSavedServerLastPlaybackAt(
        api: MediaServerApi,
        userId: String
    ): Long? = coroutineScope {
        val playedDeferred = async {
            runCatching {
                api.getUserItems(
                    userId = userId,
                    includeItemTypes = "Movie,Episode,Video",
                    recursive = true,
                    sortBy = "DatePlayed",
                    sortOrder = "Descending",
                    limit = 1,
                    filters = "IsPlayed",
                    fields = "UserData",
                    enableImages = false
                )
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()
        }
        val resumableDeferred = async {
            runCatching {
                api.getResumeItems(
                    userId = userId,
                    includeItemTypes = "Movie,Episode,Video",
                    limit = 1,
                    recursive = true,
                    sortBy = "DatePlayed",
                    sortOrder = "Descending",
                    fields = "UserData"
                )
            }.getOrNull()?.takeIf { it.isSuccessful }?.body()
        }
        listOf(
            extractLastPlaybackEpochMs(playedDeferred.await()),
            extractLastPlaybackEpochMs(resumableDeferred.await())
        ).filterNotNull().maxOrNull()
    }

    private fun extractLastPlaybackEpochMs(result: QueryResult<BaseItemDto>?): Long? {
        return result
            ?.items
            ?.asSequence()
            ?.mapNotNull { item -> item.userData?.lastPlayedDate?.let(::parseServerDateEpochMs) }
            ?.maxOrNull()
    }

    private suspend fun loadSavedServerOverviewCountsByItemQueries(
        api: MediaServerApi,
        userId: String
    ): Pair<Int?, Int?> = coroutineScope {
        val movieDeferred = async {
            runCatching {
                api.getUserItems(
                    userId = userId,
                    includeItemTypes = "Movie",
                    recursive = true,
                    limit = 1,
                    fields = "Id",
                    enableImages = false
                )
            }.getOrNull()
        }
        val seriesDeferred = async {
            runCatching {
                api.getUserItems(
                    userId = userId,
                    includeItemTypes = "Series",
                    recursive = true,
                    limit = 1,
                    fields = "Id",
                    enableImages = false
                )
            }.getOrNull()
        }
        val movieResponse = movieDeferred.await()
        val seriesResponse = seriesDeferred.await()
        movieResponse?.takeIf { it.isSuccessful }?.body()?.totalRecordCount to
            seriesResponse?.takeIf { it.isSuccessful }?.body()?.totalRecordCount
    }

    private fun parseServerDateEpochMs(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
            .getOrNull()
    }

    private suspend fun searchItemsWithApi(
        api: MediaServerApi,
        userId: String,
        searchTerm: String,
        includeItemTypes: String?,
        limit: Int?
    ): Result<List<BaseItemDto>> {
        // Try searchTerm parameter first
        var response = api.searchItems(
            userId = userId,
            searchTerm = searchTerm,
            includeItemTypes = includeItemTypes,
            recursive = true,
            limit = limit,
            fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,ProductionYear,Overview,ImageTags,PrimaryImageAspectRatio,BackdropImageTags,ParentPrimaryImageItemId,ParentPrimaryImageTag,SeriesPrimaryImageTag,Tags,OfficialRating"
        )

        // If searchTerm doesn't work, try nameStartsWith
        if (!response.isSuccessful || response.body()?.items?.isEmpty() == true) {
            response = api.searchItemsByName(
                userId = userId,
                nameStartsWith = searchTerm,
                includeItemTypes = includeItemTypes,
                recursive = true,
                limit = limit,
                fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,ProductionYear,Overview,ImageTags,PrimaryImageAspectRatio,BackdropImageTags,ParentPrimaryImageItemId,ParentPrimaryImageTag,SeriesPrimaryImageTag,Tags,OfficialRating"
            )
        }

        return if (response.isSuccessful && response.body() != null) {
            val queryResult = response.body()!!
            val items = queryResult.items ?: emptyList()
            Result.success(items)
        } else {
            Result.failure(Exception("Failed to search items: ${response.code()} - ${response.message()}"))
        }
    }

    /**
     * Perform a real one-minute keepalive playback on a saved server without switching the
     * app's active session. Success requires a real media GET against the resolved playback URL;
     * playback-reporting endpoints are still sent, but compatible/EMOS servers that reject those
     * reports no longer prevent the real stream request from being attempted.
     */
    suspend fun simulateSavedServerPlayback(
        savedServer: AuthRepository.SavedServer,
        durationSeconds: Long = 60L
    ): Result<String> {
        val accessToken = secureSessionStore.getToken(savedServer.id)
            ?: return Result.failure(Exception("Saved session expired"))
        val serverType = runCatching { ServerType.valueOf(savedServer.serverTypeRaw) }
            .getOrDefault(ServerType.UNKNOWN)
        val timeoutConfig = networkPreferences.getTimeoutConfig()
        val serverHash = savedServer.id.hashCode()
        val candidateBaseUrls = savedServer.keepAliveBaseUrlCandidates()
        var lastFailure: Exception? = null

        candidateBaseUrls.forEachIndexed { baseIndex, baseUrl ->
            val probeServer = savedServer.copy(
                serverUrl = baseUrl,
                serverLines = emptyList(),
                activeLineId = null
            )
            val result = simulateSavedServerPlaybackOnBaseUrl(
                savedServer = probeServer,
                accessToken = accessToken,
                serverType = serverType,
                timeoutConfig = timeoutConfig,
                durationSeconds = durationSeconds
            )
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            val exception = error as? Exception
                ?: Exception(error?.message ?: error?.javaClass?.simpleName ?: "unknown")
            lastFailure = exception
            Log.w(
                "KeepAlive",
                "Server keepalive base failed serverHash=$serverHash baseIndex=$baseIndex reason=${exception.message ?: exception::class.java.simpleName}"
            )
        }

        return Result.failure(lastFailure ?: Exception("No keepalive server URL succeeded"))
    }

    private suspend fun simulateSavedServerPlaybackOnBaseUrl(
        savedServer: AuthRepository.SavedServer,
        accessToken: String,
        serverType: ServerType,
        timeoutConfig: NetworkTimeoutConfig,
        durationSeconds: Long
    ): Result<String> {
        return try {
            val api = NetworkModule.createMediaServerApi(
                baseUrl = savedServer.effectiveServerUrl,
                accessToken = accessToken,
                serverType = serverType,
                storageDir = context.filesDir,
                timeoutConfig = timeoutConfig,
                blockIpv6Connections = networkPreferences::isBlockIpv6ConnectionsEnabled
            )
            val serverHash = savedServer.id.hashCode()
            val requiresEmosDirectStream = savedServer.requiresEmosDirectKeepAlive()

            val fetchedCandidateItems = fetchKeepAliveCandidateItems(
                api = api,
                userId = savedServer.userId,
                preferConcreteMediaSource = requiresEmosDirectStream
            )
                .distinctBy { item -> item.id.orEmpty() }
                .filter { item -> item.isPotentialKeepAliveCandidate() }
            val candidateItems = if (requiresEmosDirectStream) {
                // EMOS discovery is provider-hint sensitive: keep the source priority
                // from fetchKeepAliveCandidateItems instead of shuffling it away.
                fetchedCandidateItems.take(32)
            } else {
                fetchedCandidateItems
                    .shuffled(Random(System.currentTimeMillis()))
                    .take(16)
            }

            if (candidateItems.isEmpty()) {
                Log.w("KeepAlive", "No keepalive candidates serverHash=$serverHash")
                return Result.failure(Exception("No playable keepalive candidates"))
            }

            var lastFailure: Exception? = null
            candidateItems.forEachIndexed { index, item ->
                val itemId = item.id.orEmpty()
                val itemHash = itemId.hashCode()
                val candidateResult = runCatching {
                    performKeepAlivePlaybackCandidate(
                        api = api,
                        savedServer = savedServer,
                        serverType = serverType,
                        accessToken = accessToken,
                        timeoutConfig = timeoutConfig,
                        item = item,
                        serverHash = serverHash,
                        durationSeconds = durationSeconds
                    )
                }
                if (candidateResult.isSuccess) {
                    return Result.success(savedServer.serverName)
                }
                val error = candidateResult.exceptionOrNull()
                val exception = error as? Exception ?: Exception(error?.message ?: error?.javaClass?.simpleName ?: "unknown")
                lastFailure = exception
                Log.w(
                    "KeepAlive",
                    "Candidate failed serverHash=$serverHash itemHash=$itemHash index=$index reason=${exception.message ?: exception::class.java.simpleName}"
                )
                if (exception is KeepAliveCleanupException) {
                    return Result.failure(exception)
                }
            }

            Result.failure(lastFailure ?: Exception("No playable keepalive candidate succeeded"))
        } catch (e: Exception) {
            Log.w(
                "KeepAlive",
                "Server keepalive exception serverHash=${savedServer.id.hashCode()} reason=${e.message ?: e::class.java.simpleName}"
            )
            Result.failure(e)
        }
    }

    private suspend fun performKeepAlivePlaybackCandidate(
        api: MediaServerApi,
        savedServer: AuthRepository.SavedServer,
        serverType: ServerType,
        accessToken: String,
        timeoutConfig: NetworkTimeoutConfig,
        item: BaseItemDto,
        serverHash: Int,
        durationSeconds: Long
    ) {
        val itemId = item.id?.trim().orEmpty()
        if (!isUsableKeepAliveId(itemId)) throw Exception("candidate invalid item id")
        val itemHash = itemId.hashCode()
        val originalPlayed = item.userData?.played == true
        val requiresEmosDirectStream = savedServer.requiresEmosDirectKeepAlive()
        val deviceProfile = PlaybackDeviceProfileFactory.create(
            maxStreamingBitrate = null,
            audioTranscodeMode = AudioTranscodeMode.AUTO
        )

        val playbackInfoPostResponse = api.getPlaybackInfoPost(
            itemId = itemId,
            request = PlaybackInfoRequest(
                userId = savedServer.userId,
                isPlayback = true,
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                deviceProfile = deviceProfile
            )
        )
        val playbackInfoGetResponse = if (
            playbackInfoPostResponse.isSuccessful &&
            playbackInfoPostResponse.body()?.mediaSources.orEmpty().isNotEmpty()
        ) {
            null
        } else {
            api.getPlaybackInfoGet(
                itemId = itemId,
                userId = savedServer.userId,
                maxStreamingBitrate = null,
                audioStreamIndex = null,
                subtitleStreamIndex = null,
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true
            )
        }
        val rawPlaybackInfo = when {
            playbackInfoPostResponse.isSuccessful && playbackInfoPostResponse.body()?.mediaSources.orEmpty().isNotEmpty() -> {
                playbackInfoPostResponse.body()
            }
            playbackInfoGetResponse?.isSuccessful == true && playbackInfoGetResponse.body()?.mediaSources.orEmpty().isNotEmpty() -> {
                playbackInfoGetResponse.body()
            }
            playbackInfoPostResponse.isSuccessful || playbackInfoGetResponse?.isSuccessful == true -> {
                val playbackSessionId = playbackInfoPostResponse.body()?.playSessionId
                    ?: playbackInfoGetResponse?.body()?.playSessionId
                val detailItem = if (item.mediaSources.orEmpty().isEmpty()) {
                    val detailResponse = api.getItemById(
                        userId = savedServer.userId,
                        itemId = itemId,
                        fields = "RunTimeTicks,Path,ProviderIds,ExternalUrls,MediaSources,MediaStreams,UserData,Type,IsFolder,IsPlaceHolder"
                    )
                    if (detailResponse.isSuccessful) {
                        detailResponse.body() ?: item
                    } else {
                        Log.w(
                            "KeepAlive",
                            "PlaybackInfo empty and item detail fallback failed serverHash=$serverHash itemHash=$itemHash detail=${detailResponse.code()}"
                        )
                        item
                    }
                } else {
                    item
                }
                val fallbackPlaybackInfo = buildKeepAliveFallbackPlaybackInfo(
                    item = detailItem,
                    playSessionId = playbackSessionId
                )
                if (fallbackPlaybackInfo != null) {
                    Log.w(
                        "KeepAlive",
                        "PlaybackInfo empty; using catalog media source fallback serverHash=$serverHash itemHash=$itemHash post=${playbackInfoPostResponse.code()} get=${playbackInfoGetResponse?.code()} sourceCount=${fallbackPlaybackInfo.mediaSources.orEmpty().size} requiresEmos=$requiresEmosDirectStream"
                    )
                    fallbackPlaybackInfo
                } else {
                    Log.w("KeepAlive", "PlaybackInfo no media source serverHash=$serverHash itemHash=$itemHash post=${playbackInfoPostResponse.code()} get=${playbackInfoGetResponse?.code()}")
                    if (requiresEmosDirectStream) {
                        throw Exception("EMOS PlaybackInfo/catalog did not return a real playback media source")
                    }
                    throw Exception("PlaybackInfo has no media source")
                }
            }
            else -> {
                Log.w("KeepAlive", "PlaybackInfo failed serverHash=$serverHash itemHash=$itemHash post=${playbackInfoPostResponse.code()} get=${playbackInfoGetResponse?.code()}")
                throw Exception("PlaybackInfo failed: post=${playbackInfoPostResponse.code()} get=${playbackInfoGetResponse?.code()}")
            }
        } ?: throw Exception("Playback info body missing")
        val playbackInfo = PlaybackUrlBuilder.playbackInfoUrls(
            serverUrl = savedServer.effectiveServerUrl,
            playbackInfo = rawPlaybackInfo
        )
        val mediaSource = playbackInfo.mediaSources.orEmpty().firstOrNull()
            ?: throw Exception("PlaybackInfo has no media source")
        val playSessionId = playbackInfo.playSessionId
        val mediaSourceId = mediaSource.id
        val audioStreamIndex = mediaSource.defaultAudioStreamIndex
        val subtitleStreamIndex = mediaSource.defaultSubtitleStreamIndex
        val playbackRequest = PlaybackUrlBuilder.createLocalPlaybackRequest(
            authContext = PlaybackAuthContext(
                serverUrl = savedServer.effectiveServerUrl,
                serverType = serverType,
                accessToken = accessToken,
                deviceId = NetworkModule.getClientDeviceId(),
                clientVersion = DataModuleConfig.CLIENT_VERSION
            ),
            itemId = itemId,
            playbackInfo = playbackInfo,
            options = PlaybackStreamOptions(
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                audioTranscodeMode = AudioTranscodeMode.AUTO,
                includeAccessToken = true
            )
        ).getOrElse { error ->
            throw Exception("Playback stream URL unavailable: ${error.message ?: error::class.java.simpleName}")
        }
        val playbackRoute = keepAliveRouteTag(playbackRequest.url)
        val playbackRouteIsProviderDirect = playbackRoute == "emya" || isOriginalStrmKeepAliveRoute(playbackRequest.url)
        if (requiresEmosDirectStream && !playbackRouteIsProviderDirect) {
            Log.w(
                "KeepAlive",
                "EMOS keepalive did not expose provider route; skipping generic route and probing provider fallbacks serverHash=$serverHash itemHash=$itemHash route=$playbackRoute urlHash=${playbackRequest.url.hashCode()}"
            )
        }
        val streamClient = createKeepAliveStreamClient(timeoutConfig)
        val normalizedDurationSeconds = durationSeconds.coerceAtLeast(1L)
        Log.i(
            "KeepAlive",
            "Resolved playback stream serverHash=$serverHash itemHash=$itemHash route=$playbackRoute urlHash=${playbackRequest.url.hashCode()}"
        )

        val startResult = runCatching {
            api.reportPlaybackStart(
                PlaybackStartRequest(
                    itemId = itemId,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    positionTicks = 0L,
                    volumeLevel = 100,
                    playMethod = "DirectPlay"
                )
            )
        }
        startResult.onSuccess { startResponse ->
            if (!startResponse.isSuccessful) {
                Log.w("KeepAlive", "Playback start nonfatal serverHash=$serverHash itemHash=$itemHash status=${startResponse.code()}")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w("KeepAlive", "Playback start exception nonfatal serverHash=$serverHash itemHash=$itemHash reason=${error.message ?: error::class.java.simpleName}")
        }

        val initialStreamProbeResult = if (requiresEmosDirectStream && !playbackRouteIsProviderDirect) {
            Result.failure(Exception("EMOS keepalive requires provider route, got $playbackRoute"))
        } else {
            runCatching {
                performKeepAliveStreamProbe(
                    client = streamClient,
                    playbackRequest = playbackRequest,
                    serverHash = serverHash,
                    itemHash = itemHash,
                    checkpoint = "start",
                    preferFullRequest = true,
                    holdDurationSeconds = normalizedDurationSeconds
                )
            }
        }
        val streamProbeResult = if (initialStreamProbeResult.isFailure && requiresEmosDirectStream) {
            probeEmosKeepAliveFallbackStreams(
                client = streamClient,
                savedServer = savedServer,
                accessToken = accessToken,
                itemId = itemId,
                mediaSource = mediaSource,
                item = item,
                playSessionId = playSessionId,
                serverHash = serverHash,
                itemHash = itemHash,
                holdDurationSeconds = normalizedDurationSeconds,
                originalFailure = initialStreamProbeResult.exceptionOrNull()
            )
        } else {
            initialStreamProbeResult
        }
        if (streamProbeResult.isFailure) {
            val cleanupAfterProbeFailure = clearSavedServerKeepAliveResume(
                api = api,
                userId = savedServer.userId,
                itemId = itemId,
                originalPlayed = originalPlayed,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                serverHash = serverHash,
                itemHash = itemHash
            )
            if (cleanupAfterProbeFailure.isFailure) {
                Log.w(
                    "KeepAlive",
                    "Cleanup after failed stream probe also failed serverHash=$serverHash itemHash=$itemHash reason=${cleanupAfterProbeFailure.exceptionOrNull()?.message.orEmpty()}"
                )
            }
            throw streamProbeResult.exceptionOrNull() ?: Exception("Playback stream request failed")
        }

        // The real stream GET above is the success gate. Keep the play session alive for
        // one minute with heartbeat progress reports, but keep every position at zero.
        // Advancing to 15/30/45/60s makes ordinary Emby/Jellyfin servers persist a resume
        // point before cleanup, which is exactly the "保号资源进继续观看" pollution the UI
        // must avoid. EMOS/proxy dashboards already receive the real stream GET, so zero
        // progress is enough to maintain the session without creating resume history.
        val progressCheckpointsSeconds = if (normalizedDurationSeconds >= 60L) {
            listOf(15L, 30L, 45L, 60L)
        } else {
            listOf(normalizedDurationSeconds)
        }
        var lastProgressSeconds = 0L
        for (seconds in progressCheckpointsSeconds) {
            // The actual HTTP stream request above is already held open for the requested
            // keepalive duration. Send zero-position progress checkpoints after the held
            // stream completes without adding another minute of wall-clock delay.
            lastProgressSeconds = seconds
            val progressResult = runCatching {
                api.reportPlaybackProgress(
                    PlaybackProgressRequest(
                        itemId = itemId,
                        playSessionId = playSessionId,
                        mediaSourceId = mediaSourceId,
                        audioStreamIndex = audioStreamIndex,
                        subtitleStreamIndex = subtitleStreamIndex,
                        positionTicks = 0L,
                        isPaused = false,
                        isMuted = false,
                        volumeLevel = 100,
                        playMethod = "DirectPlay"
                    )
                )
            }
            progressResult.onSuccess { progressResponse ->
                if (!progressResponse.isSuccessful) {
                    Log.w("KeepAlive", "Playback zero-progress heartbeat nonfatal serverHash=$serverHash itemHash=$itemHash status=${progressResponse.code()} seconds=$seconds")
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w("KeepAlive", "Playback zero-progress heartbeat exception nonfatal serverHash=$serverHash itemHash=$itemHash seconds=$seconds reason=${error.message ?: error::class.java.simpleName}")
            }
            // If one reporting call times out after the real stream has already been held
            // for the requested duration, skip later heartbeats so a flaky reporting
            // endpoint cannot consume the whole per-server timeout before cleanup.
            if (progressResult.isFailure) break
        }

        // Stop at zero so servers that persist the Stopped position do not leave the
        // keepalive item in Continue Watching. The cleanup below verifies the account
        // history before we report success.
        val stoppedResult = runCatching {
            api.reportPlaybackStopped(
                PlaybackStoppedRequest(
                    itemId = itemId,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = 0L,
                    failed = false
                )
            )
        }
        stoppedResult.onSuccess { stoppedResponse ->
            if (!stoppedResponse.isSuccessful) {
                Log.w("KeepAlive", "Playback stop nonfatal serverHash=$serverHash itemHash=$itemHash status=${stoppedResponse.code()}")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w("KeepAlive", "Playback stop exception nonfatal serverHash=$serverHash itemHash=$itemHash reason=${error.message ?: error::class.java.simpleName}")
        }

        val cleanupResult = clearSavedServerKeepAliveResume(
            api = api,
            userId = savedServer.userId,
            itemId = itemId,
            originalPlayed = originalPlayed,
            playSessionId = playSessionId,
            mediaSourceId = mediaSourceId,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            serverHash = serverHash,
            itemHash = itemHash
        )
        if (cleanupResult.isFailure) {
            val cleanupError = cleanupResult.exceptionOrNull()
            Log.w(
                "KeepAlive",
                "Keepalive stream succeeded but resume cleanup remains pending serverHash=$serverHash itemHash=$itemHash reason=${cleanupError?.message.orEmpty()}"
            )
            throw cleanupError ?: KeepAliveCleanupException("Keepalive resume cleanup could not be verified")
        }

        homeSnapshotStore.clearPersistedHomeSnapshot()

        Log.i(
            "KeepAlive",
            "Completed serverHash=$serverHash itemHash=$itemHash realStream=ok resumeCleanup=${if (cleanupResult.isSuccess) "verified" else "pending"}"
        )
    }

    private fun buildKeepAliveFallbackPlaybackInfo(
        item: BaseItemDto,
        playSessionId: String?
    ): PlaybackInfoResponse? {
        val fallbackSources = buildList {
            item.mediaSources.orEmpty()
                .map { source -> source.toPlaybackMediaSource(item) }
                .filter { source ->
                    !source.hasUnavailableKeepAliveHint() && (
                        !source.directStreamUrl.isNullOrBlank() ||
                            !source.path.isNullOrBlank() ||
                            !source.transcodingUrl.isNullOrBlank() ||
                            !source.id.isNullOrBlank()
                        )
                }
                .forEach(::add)

            if (isEmpty()) {
                val itemPath = item.path?.takeIf { path ->
                    path.isNotBlank() && !isCatalogContainerKeepAlivePath(path)
                }
                val providerExternalUrl = item.externalUrls.orEmpty()
                    .mapNotNull { externalUrl -> externalUrl.url?.takeIf(::isLikelyKeepAliveProviderHint) }
                    .firstOrNull()
                if (!itemPath.isNullOrBlank() || !providerExternalUrl.isNullOrBlank() || item.id.orEmpty().startsWith("ve-", ignoreCase = true)) {
                    add(
                        MediaSource(
                            id = item.id,
                            path = itemPath,
                            directStreamUrl = providerExternalUrl,
                            container = item.container,
                            name = item.name,
                            runTimeTicks = item.runTimeTicks,
                            supportsDirectPlay = true,
                            supportsDirectStream = true,
                            mediaStreams = item.mediaStreams
                        )
                    )
                }
            }
        }
        if (fallbackSources.isEmpty()) return null
        return PlaybackInfoResponse(
            mediaSources = fallbackSources,
            playSessionId = playSessionId
        )
    }

    private fun isLikelyKeepAliveProviderHint(url: String): Boolean {
        val lower = url.lowercase()
        return "/emya/" in lower || "media_id=" in lower || "/videos/" in lower && lower.substringBefore('?').endsWith("/original.strm")
    }

    private fun isCatalogContainerKeepAlivePath(path: String?): Boolean {
        val normalized = path?.trim()?.trim('/')?.lowercase().orEmpty()
        return normalized == "movie" ||
            normalized == "movies" ||
            normalized == "tv" ||
            normalized == "series" ||
            normalized == "shows"
    }

    private fun isEmosVirtualListId(id: String?): Boolean {
        return id?.trim()?.startsWith("vl-", ignoreCase = true) == true
    }

    private fun MediaSourceInfo.hasConcreteKeepAliveSource(): Boolean {
        return !hasUnavailableKeepAliveHint(path, name, directStreamUrl, transcodingUrl) && (
            !directStreamUrl.isNullOrBlank() ||
                !transcodingUrl.isNullOrBlank() ||
                path?.takeIf { it.isNotBlank() && !isCatalogContainerKeepAlivePath(it) } != null
            )
    }

    private fun BaseItemDto.hasConcreteKeepAliveMediaSource(): Boolean {
        return mediaSources.orEmpty().any { source -> source.hasConcreteKeepAliveSource() } ||
            externalUrls.orEmpty().any { externalUrl -> externalUrl.url?.let(::isLikelyKeepAliveProviderHint) == true }
    }

    private fun MediaSourceInfo.toPlaybackMediaSource(item: BaseItemDto): MediaSource {
        return MediaSource(
            id = id ?: item.id,
            path = path ?: item.path,
            type = type,
            container = container ?: item.container,
            size = size,
            name = name ?: item.name,
            isRemote = isRemote,
            eTag = eTag,
            runTimeTicks = runTimeTicks ?: item.runTimeTicks,
            readAtNativeFramerate = readAtNativeFramerate,
            ignoreDts = ignoreDts,
            ignoreIndex = ignoreIndex,
            genPtsInput = genPtsInput,
            supportsTranscoding = supportsTranscoding,
            supportsDirectStream = supportsDirectStream,
            supportsDirectPlay = supportsDirectPlay,
            isInfiniteStream = isInfiniteStream,
            requiresOpening = requiresOpening,
            openToken = openToken,
            requiresClosing = requiresClosing,
            liveStreamId = liveStreamId,
            bufferMs = bufferMs,
            requiresLooping = requiresLooping,
            supportsProbing = supportsProbing,
            videoType = videoType,
            mediaStreams = mediaStreams,
            mediaAttachments = mediaAttachments,
            formats = formats,
            bitrate = bitrate,
            timestamp = timestamp,
            requiredHttpHeaders = requiredHttpHeaders,
            directStreamUrl = directStreamUrl,
            transcodingUrl = transcodingUrl,
            transcodingSubProtocol = transcodingSubProtocol,
            transcodingContainer = transcodingContainer,
            analyzeDurationMs = analyzeDurationMs,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
        )
    }

    private suspend fun probeEmosKeepAliveFallbackStreams(
        client: OkHttpClient,
        savedServer: AuthRepository.SavedServer,
        accessToken: String,
        itemId: String,
        mediaSource: MediaSource,
        item: BaseItemDto,
        playSessionId: String?,
        serverHash: Int,
        itemHash: Int,
        holdDurationSeconds: Long,
        originalFailure: Throwable?
    ): Result<Int> {
        val fallbackRequests = buildEmosKeepAliveFallbackPlaybackRequests(
            baseUrl = savedServer.effectiveServerUrl,
            accessToken = accessToken,
            itemId = itemId,
            mediaSource = mediaSource,
            playSessionId = playSessionId,
            item = item
        )
        if (fallbackRequests.isEmpty()) {
            return Result.failure(
                originalFailure as? Exception
                    ?: Exception(originalFailure?.message ?: "Playback stream request failed")
            )
        }
        appendKeepAliveDebugLine(
            "EMOS_CANDIDATES serverHash=$serverHash itemHash=$itemHash " +
                "providerKeys=${item.providerIds.orEmpty().keys.sorted().joinToString(prefix = "[", postfix = "]")} " +
                "itemIdKind=${keepAliveIdKind(itemId)} sourceIdKind=${keepAliveIdKind(mediaSource.id)} " +
                "pathShape=${mediaSource.path?.let { safeKeepAliveUrlShape(it) } ?: "blank"} " +
                "directShape=${mediaSource.directStreamUrl?.let { safeKeepAliveUrlShape(it) } ?: "blank"} " +
                "transcodeShape=${mediaSource.transcodingUrl?.let { safeKeepAliveUrlShape(it) } ?: "blank"} " +
                "hasSourcePath=${!mediaSource.path.isNullOrBlank()} hasDirect=${!mediaSource.directStreamUrl.isNullOrBlank()} hasTranscode=${!mediaSource.transcodingUrl.isNullOrBlank()} " +
                "requestShapes=${fallbackRequests.take(24).joinToString(prefix = "[", postfix = "]") { safeKeepAliveUrlShape(it.url) }}"
        )

        var lastFailure: Throwable? = originalFailure
        var lastEmosRouteFailure: Throwable? = null
        fallbackRequests.forEachIndexed { index, request ->
            val result = runCatching {
                performKeepAliveStreamProbe(
                    client = client,
                    playbackRequest = request,
                    serverHash = serverHash,
                    itemHash = itemHash,
                    checkpoint = "emos-fallback-$index",
                    preferFullRequest = true,
                    holdDurationSeconds = holdDurationSeconds
                )
            }
            if (result.isSuccess) {
                Log.i(
                    "KeepAlive",
                    "EMOS fallback stream accepted serverHash=$serverHash itemHash=$itemHash index=$index route=${keepAliveRouteTag(request.url)} urlHash=${request.url.hashCode()}"
                )
                return result
            }
            lastFailure = result.exceptionOrNull() ?: lastFailure
            if (keepAliveRouteTag(request.url) == "emya") {
                lastEmosRouteFailure = result.exceptionOrNull() ?: lastEmosRouteFailure
            }
            Log.w(
                "KeepAlive",
                "EMOS fallback stream failed serverHash=$serverHash itemHash=$itemHash index=$index route=${keepAliveRouteTag(request.url)} reason=${(result.exceptionOrNull() ?: lastFailure)?.message.orEmpty()}"
            )
        }
        val preferredFailure = lastEmosRouteFailure ?: lastFailure
        return Result.failure(
            preferredFailure as? Exception ?: Exception(preferredFailure?.message ?: "Playback stream request failed")
        )
    }

    private fun buildEmosKeepAliveFallbackPlaybackRequests(
        baseUrl: String,
        accessToken: String,
        itemId: String,
        mediaSource: MediaSource,
        playSessionId: String?,
        item: BaseItemDto? = null
    ): List<PlaybackRequest> {
        return EmosKeepAliveUrlCandidateBuilder.build(
            baseUrl = baseUrl,
            accessToken = accessToken,
            itemId = itemId,
            mediaSource = mediaSource,
            playSessionId = playSessionId,
            item = item
        )
    }

    private fun buildEmosProviderLineCandidates(mediaSource: MediaSource): List<String> {
        return listOfNotNull(
            extractEmosProviderLine(mediaSource.path),
            extractEmosProviderLine(mediaSource.directStreamUrl),
            extractEmosProviderLine(mediaSource.transcodingUrl),
            mediaSource.liveStreamId?.takeIf { it.isNotBlank() }
        )
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractEmosProviderLine(rawUrlOrPath: String?): String? {
        val raw = rawUrlOrPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val explicitLine = uri?.getQueryParameter("line")?.takeIf { it.isNotBlank() }
        if (!explicitLine.isNullOrBlank()) return explicitLine

        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val path = raw.substringBefore('?').trim('/').takeIf { it.isNotBlank() } ?: return null
        val lowerPath = path.lowercase()
        if (lowerPath == "emya/video" || lowerPath.endsWith("/emya/video")) return null
        if (lowerPath.startsWith("videos/") || lowerPath.startsWith("items/")) return null
        return path
    }

    private fun resolveEmosKeepAliveRawUrl(baseUrl: String, rawUrl: String?): String? {
        val raw = rawUrl?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val path = raw.substringBefore('?').lowercase()
        return if ((path == "/emya/video" || path.endsWith("/emya/video")) && raw.startsWith('/')) {
            "${trimTrailingSlash(baseUrl)}/${raw.trimStart('/')}"
        } else {
            getServerUrl(baseUrl = baseUrl, url = raw)
        }
    }

    private fun buildOriginRootUrl(
        baseUrl: String,
        encodedPath: String,
        queryParams: List<Pair<String, String?>>
    ): String? {
        val uri = runCatching { URI.create(baseUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
        return buildServerUrl(
            baseUrl = "$scheme://$authority",
            encodedPath = encodedPath,
            queryParams = queryParams
        )
    }

    private fun appendApiKeyIfMissing(url: String, accessToken: String): String {
        if (accessToken.isBlank()) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val hasApiKey = uri.queryParameterNames.any { name -> name.equals("api_key", ignoreCase = true) }
        if (hasApiKey) return url
        return uri.buildUpon()
            .appendQueryParameter("api_key", accessToken)
            .build()
            .toString()
    }

    private suspend fun clearSavedServerKeepAliveResume(
        api: MediaServerApi,
        userId: String,
        itemId: String,
        originalPlayed: Boolean,
        playSessionId: String?,
        mediaSourceId: String?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        serverHash: Int,
        itemHash: Int
    ): Result<Unit> {
        return try {
            val zeroProgressResponse = api.reportPlaybackProgress(
                PlaybackProgressRequest(
                    itemId = itemId,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    positionTicks = 0L,
                    isPaused = true,
                    isMuted = false,
                    volumeLevel = 100,
                    playMethod = "DirectPlay"
                )
            )
            if (!zeroProgressResponse.isSuccessful) {
                Log.w("KeepAlive", "Zero-progress cleanup nonfatal serverHash=$serverHash itemHash=$itemHash status=${zeroProgressResponse.code()}")
            }

            val zeroStoppedResponse = api.reportPlaybackStopped(
                PlaybackStoppedRequest(
                    itemId = itemId,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = 0L,
                    failed = false
                )
            )
            if (!zeroStoppedResponse.isSuccessful) {
                Log.w("KeepAlive", "Zero-stop cleanup nonfatal serverHash=$serverHash itemHash=$itemHash status=${zeroStoppedResponse.code()}")
            }

            val userDataResponse = api.updateUserItemData(
                userId = userId,
                itemId = itemId,
                request = com.grmemby.data.model.UpdateUserItemDataRequest(
                    playedPercentage = if (originalPlayed) 100.0 else 0.0,
                    playbackPositionTicks = 0L,
                    played = originalPlayed,
                    itemId = itemId
                )
            )
            if (!userDataResponse.isSuccessful) {
                Log.w("KeepAlive", "UserData cleanup nonfatal serverHash=$serverHash itemHash=$itemHash status=${userDataResponse.code()}")
            }

            val playedStateResponse = if (originalPlayed) {
                api.markAsPlayed(userId = userId, itemId = itemId)
            } else {
                api.unmarkAsPlayed(userId = userId, itemId = itemId)
            }
            if (!playedStateResponse.isSuccessful) {
                Log.w("KeepAlive", "Played-state cleanup nonfatal serverHash=$serverHash itemHash=$itemHash status=${playedStateResponse.code()} originalPlayed=$originalPlayed")
            }

            val acceptedByServer = zeroProgressResponse.isSuccessful || zeroStoppedResponse.isSuccessful ||
                userDataResponse.isSuccessful || playedStateResponse.isSuccessful
            if (!acceptedByServer) {
                Log.w(
                    "KeepAlive",
                    "Cleanup requests were not accepted after keepalive playback serverHash=$serverHash itemHash=$itemHash"
                )
            }

            val verificationResult = verifySavedServerKeepAliveResumeCleared(
                api = api,
                userId = userId,
                itemId = itemId,
                serverHash = serverHash,
                itemHash = itemHash
            )
            if (verificationResult.isFailure) {
                Log.w(
                    "KeepAlive",
                    "Cleanup verification still pending after accepted cleanup serverHash=$serverHash itemHash=$itemHash reason=${verificationResult.exceptionOrNull()?.message.orEmpty()}"
                )
                return Result.failure(
                    KeepAliveCleanupException(
                        verificationResult.exceptionOrNull()?.message
                            ?: "Keepalive item still appears in Continue Watching after cleanup",
                        verificationResult.exceptionOrNull()
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun verifySavedServerKeepAliveResumeCleared(
        api: MediaServerApi,
        userId: String,
        itemId: String,
        serverHash: Int,
        itemHash: Int
    ): Result<Unit> {
        var stableClearCount = 0
        repeat(12) { attempt ->
            if (attempt > 0) delay(1_000L)
            val itemResponse = api.getItemById(
                userId = userId,
                itemId = itemId,
                fields = "UserData"
            )
            if (!itemResponse.isSuccessful) {
                Log.w("KeepAlive", "Cleanup item verification failed serverHash=$serverHash itemHash=$itemHash status=${itemResponse.code()}")
                return Result.failure(Exception("Failed to verify keepalive cleanup item state: ${itemResponse.code()}"))
            }
            val remainingResumeTicks = itemResponse.body()?.userData?.playbackPositionTicks ?: 0L

            val resumeResponse = api.getResumeItems(
                userId = userId,
                includeItemTypes = "Movie,Episode,Video",
                limit = 200,
                recursive = true,
                fields = "UserData"
            )
            if (!resumeResponse.isSuccessful) {
                Log.w("KeepAlive", "Cleanup resume verification failed serverHash=$serverHash itemHash=$itemHash status=${resumeResponse.code()}")
                return Result.failure(Exception("Failed to verify keepalive cleanup resume list: ${resumeResponse.code()}"))
            }
            val stillInResume = resumeResponse.body()?.items.orEmpty().any { resumeItem -> resumeItem.id == itemId }
            if (remainingResumeTicks <= 0L && !stillInResume) {
                stableClearCount += 1
                if (stableClearCount >= 2) {
                    return Result.success(Unit)
                }
            } else {
                stableClearCount = 0
            }
            Log.w(
                "KeepAlive",
                "Cleanup verification retry serverHash=$serverHash itemHash=$itemHash attempt=$attempt resumeTicks=$remainingResumeTicks stillInResume=$stillInResume stableClearCount=$stableClearCount"
            )
        }
        return Result.failure(Exception("Keepalive item still appears in Continue Watching after cleanup"))
    }

    private data class KeepAliveStreamProbeResult(
        val statusCode: Int,
        val bytesRead: Int,
        val contentType: String?,
        val contentLength: Long?,
        val firstBytes: ByteArray,
        val usedRange: Boolean,
        val heldMillis: Long
    )

    private class KeepAliveCleanupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun createKeepAliveStreamClient(timeoutConfig: NetworkTimeoutConfig): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(timeoutConfig.requestTimeoutMs.toLong().coerceAtLeast(90_000L), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutConfig.connectionTimeoutMs.toLong().coerceAtLeast(6_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutConfig.socketTimeoutMs.toLong().coerceAtLeast(15_000L), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutConfig.socketTimeoutMs.toLong().coerceAtLeast(15_000L), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private suspend fun performKeepAliveStreamProbe(
        client: OkHttpClient,
        playbackRequest: PlaybackRequest,
        serverHash: Int,
        itemHash: Int,
        checkpoint: String,
        preferFullRequest: Boolean = keepAliveRouteTag(playbackRequest.url) == "emya",
        holdDurationSeconds: Long = 0L
    ): Int {
        val route = keepAliveRouteTag(playbackRequest.url)
        // EMOS/provider dashboards often only count an actual stream GET, not a byte-range probe.
        // For provider/direct routes, open the URL without Range first; fall back to Range only for
        // generic Jellyfin/Emby stream endpoints where ranged requests are better supported.
        val firstResult = executeKeepAliveStreamRequest(
            client = client,
            playbackRequest = playbackRequest,
            useRange = !preferFullRequest,
            holdDurationMillis = holdDurationSeconds.coerceAtLeast(0L) * 1_000L
        )
        val result = if (
            firstResult.statusCode == 400 ||
            firstResult.statusCode == 405 ||
            firstResult.statusCode == 416 ||
            (firstResult.statusCode in 200..299 && firstResult.bytesRead <= 0)
        ) {
            executeKeepAliveStreamRequest(
                client = client,
                playbackRequest = playbackRequest,
                useRange = preferFullRequest,
                holdDurationMillis = holdDurationSeconds.coerceAtLeast(0L) * 1_000L
            )
        } else {
            firstResult
        }

        Log.i(
            "KeepAlive",
            "Stream probe serverHash=$serverHash itemHash=$itemHash checkpoint=$checkpoint route=$route urlHash=${playbackRequest.url.hashCode()} ranged=${result.usedRange} status=${result.statusCode} bytes=${result.bytesRead} heldMs=${result.heldMillis} contentType=${result.contentType.orEmpty()} contentLength=${result.contentLength ?: -1L} errorPreview=${if (result.statusCode in 200..299) "" else sanitizeKeepAliveProbePreview(result.firstBytes)}"
        )
        if (result.statusCode !in 200..299) {
            val preview = sanitizeKeepAliveProbePreview(result.firstBytes).takeIf { it.isNotBlank() }
            throw Exception(
                "Playback stream request failed: ${result.statusCode} ${safeKeepAliveUrlShape(playbackRequest.url)}" +
                    (preview?.let { " preview=$it" } ?: "")
            )
        }
        if (result.bytesRead <= 0) {
            throw Exception("Playback stream request returned no media bytes ${safeKeepAliveUrlShape(playbackRequest.url)}")
        }
        if (!isLikelyRealKeepAliveMediaResponse(playbackRequest.url, result.contentType, result.firstBytes)) {
            Log.w(
                "KeepAlive",
                "Stream probe rejected non-media response serverHash=$serverHash itemHash=$itemHash route=${keepAliveRouteTag(playbackRequest.url)} status=${result.statusCode} contentType=${result.contentType.orEmpty()} bytes=${result.bytesRead}"
            )
            throw Exception("Playback stream request returned a non-media response ${safeKeepAliveUrlShape(playbackRequest.url)}")
        }
        if (isKeepAliveHlsManifestResponse(playbackRequest.url, result.contentType, result.firstBytes)) {
            probeKeepAliveHlsMediaSegment(
                client = client,
                playlistRequest = playbackRequest,
                manifestBytes = result.firstBytes,
                serverHash = serverHash,
                itemHash = itemHash,
                holdDurationSeconds = holdDurationSeconds
            )
        }
        return result.statusCode
    }

    private suspend fun probeKeepAliveHlsMediaSegment(
        client: OkHttpClient,
        playlistRequest: PlaybackRequest,
        manifestBytes: ByteArray,
        serverHash: Int,
        itemHash: Int,
        holdDurationSeconds: Long = 0L
    ) {
        val manifest = manifestBytes.toString(Charsets.UTF_8)
        val firstMediaUrl = resolveFirstKeepAliveHlsMediaUrl(
            manifestUrl = playlistRequest.url,
            manifest = manifest
        ) ?: throw Exception("Playback HLS manifest has no media segment")
        val authorizedUrl = playlistRequest.authorizeRelatedUrl(firstMediaUrl)
        val segmentRequest = PlaybackRequest(
            url = authorizedUrl,
            requestHeaders = playlistRequest.requestHeaders
        )
        val segmentResult = executeKeepAliveStreamRequest(
            client = client,
            playbackRequest = segmentRequest,
            useRange = false,
            holdDurationMillis = holdDurationSeconds.coerceAtLeast(0L) * 1_000L
        )
        Log.i(
            "KeepAlive",
            "HLS segment probe serverHash=$serverHash itemHash=$itemHash urlHash=${authorizedUrl.hashCode()} status=${segmentResult.statusCode} bytes=${segmentResult.bytesRead} contentType=${segmentResult.contentType.orEmpty()}"
        )
        if (segmentResult.statusCode !in 200..299) {
            throw Exception("Playback HLS segment request failed: ${segmentResult.statusCode}")
        }
        if (segmentResult.bytesRead <= 0) {
            throw Exception("Playback HLS segment request returned no media bytes")
        }
        if (isKeepAliveHlsManifestResponse(segmentRequest.url, segmentResult.contentType, segmentResult.firstBytes)) {
            probeKeepAliveHlsMediaSegment(
                client = client,
                playlistRequest = segmentRequest,
                manifestBytes = segmentResult.firstBytes,
                serverHash = serverHash,
                itemHash = itemHash,
                holdDurationSeconds = holdDurationSeconds
            )
            return
        }
        if (!isLikelyRealKeepAliveMediaResponse(segmentRequest.url, segmentResult.contentType, segmentResult.firstBytes)) {
            throw Exception("Playback HLS segment request returned a non-media response")
        }
    }

    private fun isKeepAliveHlsManifestResponse(
        url: String,
        contentType: String?,
        firstBytes: ByteArray
    ): Boolean {
        val normalizedContentType = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val prefix = firstBytes
            .take(1024)
            .toByteArray()
            .toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .lowercase()
        return normalizedContentType.contains("mpegurl") || prefix.startsWith("#extm3u")
    }

    private fun resolveFirstKeepAliveHlsMediaUrl(manifestUrl: String, manifest: String): String? {
        return manifest
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("#") }
            .firstOrNull()
            ?.let { ref -> runCatching { URI(manifestUrl).resolve(ref).toString() }.getOrNull() }
    }

    private suspend fun executeKeepAliveStreamRequest(
        client: OkHttpClient,
        playbackRequest: PlaybackRequest,
        useRange: Boolean,
        holdDurationMillis: Long = 0L
    ): KeepAliveStreamProbeResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(playbackRequest.url)
            .get()
            .header("User-Agent", "Grmemby/${DataModuleConfig.CLIENT_VERSION} Android")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        if (useRange) {
            requestBuilder.header("Range", "bytes=0-65535")
        }
        playbackRequest.requestHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }

        val startMillis = SystemClock.elapsedRealtime()
        client.newCall(requestBuilder.build()).execute().use { response ->
            val firstBytesBuffer = ByteArray(64 * 1024)
            val discardBuffer = ByteArray(64 * 1024)
            val source = response.body?.source()
            var firstBytesRead = 0
            var totalRead = 0
            val deadlineMillis = startMillis + holdDurationMillis.coerceAtLeast(0L)
            if (source != null) {
                while (true) {
                    val captureFirstBytes = firstBytesRead < firstBytesBuffer.size
                    val targetBuffer = if (captureFirstBytes) firstBytesBuffer else discardBuffer
                    val targetOffset = if (captureFirstBytes) firstBytesRead else 0
                    val targetCapacity = if (captureFirstBytes) {
                        (firstBytesBuffer.size - firstBytesRead).coerceAtMost(discardBuffer.size)
                    } else {
                        discardBuffer.size
                    }
                    val read = source.read(targetBuffer, targetOffset, targetCapacity)
                    if (read <= 0) break
                    if (captureFirstBytes) {
                        firstBytesRead += read
                    }
                    totalRead += read

                    if (holdDurationMillis <= 0L) {
                        if (firstBytesRead >= 8 * 1024 && response.body?.contentLength()?.let { it > firstBytesRead } == true) {
                            break
                        }
                        if (firstBytesRead >= firstBytesBuffer.size) break
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now >= deadlineMillis && firstBytesRead > 0) break
                        Thread.sleep(1_000L)
                    }
                }
            }
            KeepAliveStreamProbeResult(
                statusCode = response.code,
                bytesRead = totalRead,
                contentType = response.body?.contentType()?.toString(),
                contentLength = response.body?.contentLength(),
                firstBytes = if (firstBytesRead > 0) firstBytesBuffer.copyOf(firstBytesRead) else ByteArray(0),
                usedRange = useRange,
                heldMillis = SystemClock.elapsedRealtime() - startMillis
            )
        }
    }

    private fun isLikelyRealKeepAliveMediaResponse(
        url: String,
        contentType: String?,
        firstBytes: ByteArray
    ): Boolean {
        if (firstBytes.isEmpty()) return false
        val normalizedContentType = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val route = keepAliveRouteTag(url)
        val asciiPrefix = firstBytes
            .take(1024)
            .map { byte -> byte.toInt().and(0xff).toChar() }
            .joinToString(separator = "")
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val lowercasePrefix = asciiPrefix.lowercase()
        val knownMediaSignature = hasKnownKeepAliveMediaSignature(firstBytes, lowercasePrefix)
        val textLikeErrorResponse = looksLikeKeepAliveTextOrErrorResponse(lowercasePrefix)

        if (normalizedContentType.contains("mpegurl") || lowercasePrefix.startsWith("#extm3u")) {
            return true
        }
        if (textLikeErrorResponse) return false
        if (normalizedContentType.startsWith("text/") ||
            normalizedContentType.contains("json") ||
            normalizedContentType.contains("xml") ||
            normalizedContentType.contains("html")
        ) {
            return false
        }
        if (normalizedContentType.startsWith("video/") || normalizedContentType.startsWith("audio/")) {
            return true
        }
        if (knownMediaSignature) return true
        if (normalizedContentType == "application/mp4" ||
            normalizedContentType == "application/x-matroska" ||
            normalizedContentType == "application/vnd.apple.mpegurl" ||
            normalizedContentType == "application/x-mpegurl"
        ) {
            return true
        }
        if (normalizedContentType == "application/octet-stream") {
            // Some Emby-compatible/proxy servers answer the initial range probe with a
            // short binary chunk (often < 2 KiB) even though the request is a valid media
            // read. Text/JSON/HTML errors have already been rejected above, so accept
            // compact binary payloads instead of reporting every keepalive candidate as a
            // fake "non-media" failure.
            return binaryByteRatio(firstBytes) > 0.08f && firstBytes.size >= 512
        }
        if (route == "hls") {
            return false
        }
        return binaryByteRatio(firstBytes) > 0.12f && firstBytes.size >= 512
    }

    private fun hasKnownKeepAliveMediaSignature(bytes: ByteArray, lowercasePrefix: String): Boolean {
        fun u(index: Int): Int = bytes.getOrNull(index)?.toInt()?.and(0xff) ?: -1
        return lowercasePrefix.startsWith("#extm3u") ||
            (u(0) == 0x00 && u(1) == 0x00 && u(2) == 0x00 && lowercasePrefix.drop(4).startsWith("ftyp")) ||
            lowercasePrefix.startsWith("ftyp") ||
            lowercasePrefix.startsWith("riff") ||
            lowercasePrefix.startsWith("ogg") ||
            lowercasePrefix.startsWith("flv") ||
            (u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) ||
            (u(0) == 0x47 && (bytes.size < 188 || u(188) == 0x47))
    }

    private fun looksLikeKeepAliveTextOrErrorResponse(lowercasePrefix: String): Boolean {
        if (lowercasePrefix.startsWith("{") ||
            lowercasePrefix.startsWith("[") ||
            lowercasePrefix.startsWith("<") ||
            lowercasePrefix.startsWith("<!doctype") ||
            lowercasePrefix.startsWith("error") ||
            lowercasePrefix.contains("<html") ||
            lowercasePrefix.contains("application/json") ||
            lowercasePrefix.contains("invalid token") ||
            lowercasePrefix.contains("unauthorized") ||
            lowercasePrefix.contains("forbidden") ||
            lowercasePrefix.contains("not found")
        ) {
            return true
        }
        val printable = lowercasePrefix.take(256).count { char -> char == '\n' || char == '\r' || char == '\t' || char in ' '..'~' }
        val sampleLength = lowercasePrefix.take(256).length.coerceAtLeast(1)
        return lowercasePrefix.length >= 32 && printable.toFloat() >= sampleLength.toFloat() * 0.94f &&
            !lowercasePrefix.startsWith("#extm3u")
    }

    private fun sanitizeKeepAliveProbePreview(firstBytes: ByteArray): String {
        if (firstBytes.isEmpty()) return ""
        val raw = firstBytes
            .take(1024)
            .map { byte ->
                val value = byte.toInt().and(0xff)
                if (value == 10 || value == 13 || value == 9 || value in 32..126) value.toChar() else ' '
            }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
        if (raw.isBlank()) return "<binary>"
        return raw
            .replace(Regex("(?i)(api[_-]?key|access[_-]?token|token|password|passwd|pwd|credential|secret)(\\s*[=:]\\s*)[^&\\s,;\"'<>}]+"), "$1$2[REDACTED]")
            .replace(Regex("https?://[^\\s\"'<>]+"), "[REDACTED_URL]")
            .replace(Regex("(?i)(/users/)[^/\\s\"'<>]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(/videos/)[^/\\s\"'<>]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(/items/)[^/\\s\"'<>]+"), "$1[REDACTED]")
            .take(300)
    }

    private fun binaryByteRatio(bytes: ByteArray): Float {
        if (bytes.isEmpty()) return 0f
        val sample = bytes.take(4096)
        val binaryCount = sample.count { byte ->
            val value = byte.toInt().and(0xff)
            value == 0 || value < 0x09 || (value in 0x0E..0x1F) || value >= 0x80
        }
        return binaryCount.toFloat() / sample.size.toFloat()
    }

    private fun keepAliveRouteTag(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/emya/" in path -> "emya"
            "/items/" in path && "/download" in path -> "download"
            "/videos/" in path -> "videos"
            "master.m3u8" in path || ".m3u8" in path -> "hls"
            "transcode" in path || "transcoding" in path -> "transcode"
            else -> "stream"
        }
    }

    private fun safeKeepAliveUrlShape(url: String): String {
        val uri = runCatching { URI.create(url) }.getOrNull()
        val path = uri?.rawPath.orEmpty().ifBlank { url.substringBefore('?') }
        val queryNames = uri?.rawQuery
            ?.split('&')
            ?.map { part -> part.substringBefore('=') }
            ?.filter { name -> name.isNotBlank() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        return "route=${keepAliveRouteTag(url)} path=$path queryNames=${queryNames.joinToString(prefix = "[", postfix = "]")} urlHash=${url.hashCode()}"
    }

    private fun keepAliveIdKind(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return "blank"
        val kind = when {
            raw.startsWith("vl-", ignoreCase = true) -> "vl"
            raw.all { it.isDigit() } -> "numeric"
            raw.length >= 24 && raw.all { it.isLetterOrDigit() } -> "opaque"
            else -> "other"
        }
        return "$kind:${raw.length}:${raw.hashCode()}"
    }

    private fun appendKeepAliveDebugLine(line: String) {
        runCatching {
            context.openFileOutput("keepalive-debug-result.txt", Context.MODE_APPEND).bufferedWriter().use { writer ->
                writer.appendLine(line.take(3500))
            }
        }
    }

    private fun isOriginalStrmKeepAliveRoute(url: String): Boolean {
        val path = runCatching { URI.create(url).rawPath.orEmpty().lowercase() }
            .getOrElse { url.substringBefore('?').lowercase() }
        return path.startsWith("/videos/") && path.endsWith("/original.strm")
    }

    private fun AuthRepository.SavedServer.requiresEmosDirectKeepAlive(): Boolean {
        val tokens = buildList {
            add(serverName)
            add(serverRemark.orEmpty())
            add(serverUrl)
            add(effectiveServerUrl)
            add(lineLabel())
            serverLines.forEach { line ->
                add(line.name)
                add(line.url)
            }
        }
        return tokens.any { token -> token.contains("emos", ignoreCase = true) }
    }

    private fun AuthRepository.SavedServer.keepAliveBaseUrlCandidates(): List<String> {
        val candidates = buildList {
            add(effectiveServerUrl)
            add(serverUrl)
            serverLines.forEach { line -> add(line.effectiveUrl(serverUrl)) }
        }
            .map { url -> url.trim() }
            .filter { url -> url.isNotBlank() }
            .distinctBy { url -> canonicalKeepAliveBaseUrl(url) }
        val serverLikeCandidates = candidates.filterNot(::looksLikeNonServerAssetUrl)
        return (serverLikeCandidates.ifEmpty { candidates }).ifEmpty { listOf(serverUrl) }
    }

    private fun canonicalKeepAliveBaseUrl(url: String): String {
        val uri = runCatching { URI.create(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val host = uri?.host?.lowercase().orEmpty()
        val port = uri?.port ?: -1
        val path = uri?.rawPath?.trimEnd('/').orEmpty()
        return "$scheme://$host:$port$path"
    }

    private fun looksLikeNonServerAssetUrl(url: String): Boolean {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.rawPath.orEmpty().lowercase()
        val assetHost = listOf("imagestatic", "image-static", "imgstatic", "static", "assets", "asset", "cdn")
            .any { token -> token in host }
        val assetPath = path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".svg") ||
            "/image" in path ||
            "/images" in path ||
            "/logo" in path ||
            "/logos" in path
        return assetHost || assetPath
    }

    private fun isUsableKeepAliveId(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized.isNotBlank() &&
            normalized != "none" &&
            normalized != "null" &&
            normalized != "undefined" &&
            normalized != "0"
    }

    private fun hasUnavailableKeepAliveHint(vararg values: String?): Boolean {
        return values.any { value ->
            val text = value?.trim()?.lowercase().orEmpty()
            text.isNotBlank() && listOf(
                "暂无资源",
                "隨緣更新",
                "随缘更新",
                "无资源",
                "無資源",
                "待更新",
                "资源不存在",
                "資源不存在",
                "not available",
                "no resource"
            ).any { marker -> marker in text }
        }
    }

    private fun MediaSource.hasUnavailableKeepAliveHint(): Boolean {
        return hasUnavailableKeepAliveHint(path, name, directStreamUrl, transcodingUrl) ||
            mediaStreams.orEmpty().any { stream -> hasUnavailableKeepAliveHint(stream.path, stream.deliveryUrl) }
    }

    private fun BaseItemDto.hasUnavailableKeepAliveHint(): Boolean {
        return hasUnavailableKeepAliveHint(name, originalTitle, overview, path) ||
            mediaSources.orEmpty().any { source ->
                hasUnavailableKeepAliveHint(source.path, source.name, source.directStreamUrl, source.transcodingUrl) ||
                    source.mediaStreams.orEmpty().any { stream -> hasUnavailableKeepAliveHint(stream.path, stream.deliveryUrl) }
            } ||
            mediaStreams.orEmpty().any { stream -> hasUnavailableKeepAliveHint(stream.path, stream.deliveryUrl) } ||
            externalUrls.orEmpty().any { externalUrl -> hasUnavailableKeepAliveHint(externalUrl.url) }
    }

    private fun BaseItemDto.isPotentialKeepAliveCandidate(): Boolean {
        val itemId = id?.trim()?.takeIf(::isUsableKeepAliveId) ?: return false
        // EMOS-compatible servers can expose playable library items with virtual
        // "vl-" ids. PlaybackInfo accepts those ids, so do not reject them during
        // keepalive candidate selection; folders/placeholders are filtered below.
        if (isFolder == true || isPlaceHolder == true || hasUnavailableKeepAliveHint()) return false
        val itemType = type.orEmpty()
        if (itemType.isNotBlank() && !itemType.equals("Movie", ignoreCase = true) &&
            !itemType.equals("Episode", ignoreCase = true) &&
            !itemType.equals("Video", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun BaseItemDto.hasNoResumeKeepAliveCandidate(): Boolean {
        val resumeTicks = userData?.playbackPositionTicks ?: 0L
        return isPotentialKeepAliveCandidate() && resumeTicks <= 0L
    }

    private fun BaseItemDto.isCleanKeepAliveCandidate(): Boolean {
        // Prefer untouched items, but allow fallback candidates. Played state is restored
        // after keepalive so account refresh does not pollute Continue Watching.
        return hasNoResumeKeepAliveCandidate() && userData?.played != true
    }

    private fun chooseKeepAliveCandidates(
        items: List<BaseItemDto>?,
        preferConcreteMediaSource: Boolean
    ): List<BaseItemDto>? {
        if (items.isNullOrEmpty()) return null
        val playable = items.filter { item -> item.isPotentialKeepAliveCandidate() }
        if (playable.isEmpty()) return null
        val sourceBacked = playable.filter { item -> item.hasConcreteKeepAliveMediaSource() }
        // EMOS list endpoints may omit MediaSources for real playable entries, but
        // library/collection placeholders with "vl-" ids have repeatedly returned
        // PlaybackInfo 200 with no media source. Skip all-placeholder batches so the
        // fallback fetches (Resume/Latest/View) can find a real "ve-"/numeric item; the
        // final success gate remains the real provider stream probe.
        val nonVirtualLibrary = playable.filterNot { item -> isEmosVirtualListId(item.id) }
        val preferred = when {
            sourceBacked.isNotEmpty() -> sourceBacked
            preferConcreteMediaSource && nonVirtualLibrary.isNotEmpty() -> nonVirtualLibrary
            preferConcreteMediaSource -> return null
            else -> playable
        }
        val clean = preferred.filter { item -> item.isCleanKeepAliveCandidate() }
        if (clean.isNotEmpty()) return clean
        val noResume = preferred.filter { item -> item.hasNoResumeKeepAliveCandidate() }
        if (noResume.isNotEmpty()) return noResume
        return preferred.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchKeepAliveCandidateItems(
        api: MediaServerApi,
        userId: String,
        preferConcreteMediaSource: Boolean
    ): List<BaseItemDto> {
        val fields = "RunTimeTicks,Path,ProviderIds,ExternalUrls,MediaSources,MediaStreams,UserData,Type,IsFolder,IsPlaceHolder"
        val discoveryItemTypes = if (preferConcreteMediaSource) "Movie,Series,Episode,Video" else "Movie,Episode,Video"
        val collected = mutableListOf<BaseItemDto>()

        fun maybeReturnOrCollect(label: String, status: Int, items: List<BaseItemDto>?): List<BaseItemDto>? {
            val candidates = chooseKeepAliveCandidates(items, preferConcreteMediaSource)
                ?.shuffled(Random(System.currentTimeMillis()))
            if (candidates.isNullOrEmpty()) {
                val sample = items.orEmpty().take(5).joinToString(prefix = "[", postfix = "]") { item ->
                    "${item.type.orEmpty()}:${keepAliveIdKind(item.id.orEmpty())}:folder=${item.isFolder == true}:placeholder=${item.isPlaceHolder == true}"
                }
                Log.w("KeepAlive", "$label candidates empty status=$status itemCount=${items.orEmpty().size} sample=$sample")
                return null
            }
            if (!preferConcreteMediaSource) return candidates
            val bounded = candidates.take(16)
            collected += bounded
            val hasConcreteHints = bounded.any { item -> item.hasConcreteKeepAliveMediaSource() }
            if (!hasConcreteHints) {
                Log.w(
                    "KeepAlive",
                    "$label candidates lack provider media hints status=$status itemCount=${items.orEmpty().size}; continuing search"
                )
                return null
            }
            // EMOS/provider keepalive has to start its real stream close to the rest of
            // the five-server batch. Once a list endpoint yields concrete provider media
            // hints, return those candidates immediately instead of spending the whole
            // per-server budget expanding catalog containers before the stream can start.
            Log.i(
                "KeepAlive",
                "$label candidates include provider media hints status=$status count=${bounded.size}; starting stream probes"
            )
            return collected
                .distinctBy { item -> item.id.orEmpty() }
                .take(32)
        }

        suspend fun maybeExpandContainers(label: String, items: List<BaseItemDto>?): List<BaseItemDto>? {
            val containers = items.orEmpty()
                .filter { item ->
                    val id = item.id?.trim()
                    !id.isNullOrBlank() &&
                        isUsableKeepAliveId(id) &&
                        !item.hasUnavailableKeepAliveHint() &&
                        (item.type.equals("Series", ignoreCase = true) ||
                            item.isFolder == true ||
                            item.type.equals("Folder", ignoreCase = true) ||
                            item.type.equals("Season", ignoreCase = true) ||
                            isEmosVirtualListId(id))
                }
                .distinctBy { item -> item.id.orEmpty() }
                .shuffled(Random(System.currentTimeMillis()))
                .take(6)
            for (container in containers) {
                val containerId = container.id ?: continue
                if (container.type.equals("Series", ignoreCase = true) || container.type.equals("Season", ignoreCase = true) || isEmosVirtualListId(containerId)) {
                    val seriesId = container.seriesId?.takeIf { it.isNotBlank() } ?: containerId
                    val nextUp = runCatching {
                        api.getNextUp(
                            userId = userId,
                            seriesId = seriesId,
                            limit = 8,
                            fields = fields,
                            enableUserData = true,
                            enableImages = false
                        )
                    }.getOrNull()
                    maybeReturnOrCollect("$label.NextUp", nextUp?.code() ?: -1, nextUp?.body()?.items)?.let { return it }

                    val episodes = runCatching {
                        api.getEpisodes(
                            seriesId = seriesId,
                            userId = userId,
                            fields = fields,
                            limit = 8,
                            startIndex = 0,
                            enableImages = false
                        )
                    }.getOrNull()
                    maybeReturnOrCollect("$label.Episodes", episodes?.code() ?: -1, episodes?.body()?.items)?.let { return it }
                }

                val childItems = runCatching {
                    api.getUserItems(
                        userId = userId,
                        parentId = containerId,
                        personIds = null,
                        genres = null,
                        genreIds = null,
                        includeItemTypes = discoveryItemTypes,
                        recursive = true,
                        sortBy = "Random",
                        sortOrder = null,
                        limit = 20,
                        startIndex = null,
                        filters = null,
                        fields = fields,
                        enableImages = false,
                        imageTypeLimit = null,
                        enableImageTypes = null
                    )
                }.getOrNull()
                maybeReturnOrCollect("$label.Children", childItems?.code() ?: -1, childItems?.body()?.items)?.let { return it }
            }
            return null
        }

        val randomResponse = api.getUserItems(
            userId = userId,
            parentId = null,
            personIds = null,
            genres = null,
            genreIds = null,
            includeItemTypes = discoveryItemTypes,
            recursive = true,
            sortBy = "Random",
            sortOrder = null,
            limit = 40,
            startIndex = null,
            filters = null,
            fields = fields,
            enableImages = false,
            imageTypeLimit = null,
            enableImageTypes = null
        )
        val randomItems = randomResponse.body()?.items
        maybeReturnOrCollect("Random", randomResponse.code(), randomItems)?.let { return it }
        maybeExpandContainers("Random", randomItems)?.let { return it }

        val plainResponse = api.getUserItems(
            userId = userId,
            parentId = null,
            personIds = null,
            genres = null,
            genreIds = null,
            includeItemTypes = discoveryItemTypes,
            recursive = true,
            sortBy = null,
            sortOrder = null,
            limit = 40,
            startIndex = null,
            filters = null,
            fields = fields,
            enableImages = false,
            imageTypeLimit = null,
            enableImageTypes = null
        )
        val plainItems = plainResponse.body()?.items
        maybeReturnOrCollect("Plain", plainResponse.code(), plainItems)?.let { return it }
        maybeExpandContainers("Plain", plainItems)?.let { return it }

        val resumeResponse = api.getResumeItems(
            userId = userId,
            includeItemTypes = discoveryItemTypes,
            limit = 40,
            recursive = true,
            fields = fields
        )
        val resumeItems = resumeResponse.body()?.items
        maybeReturnOrCollect("Resume", resumeResponse.code(), resumeItems)?.let { return it }
        maybeExpandContainers("Resume", resumeItems)?.let { return it }

        val latestResponse = api.getLatestItems(
            userId = userId,
            parentId = null,
            includeItemTypes = discoveryItemTypes,
            limit = 40,
            fields = fields
        )
        val latestItems = latestResponse.body()
        maybeReturnOrCollect("Latest", latestResponse.code(), latestItems)?.let { return it }
        maybeExpandContainers("Latest", latestItems)?.let { return it }

        val viewsResponse = api.getUserViews(userId = userId)
        val viewIds = viewsResponse.body()?.items
            .orEmpty()
            .mapNotNull { view -> view.id?.takeIf { it.isNotBlank() } }
            .shuffled(Random(System.currentTimeMillis()))
            .take(8)
        for (viewId in viewIds) {
            val viewItemsResponse = api.getUserItems(
                userId = userId,
                parentId = viewId,
                personIds = null,
                genres = null,
                genreIds = null,
                includeItemTypes = discoveryItemTypes,
                recursive = true,
                sortBy = "Random",
                sortOrder = null,
                limit = 30,
                startIndex = null,
                filters = null,
                fields = fields,
                enableImages = false,
                imageTypeLimit = null,
                enableImageTypes = null
            )
            val viewItems = viewItemsResponse.body()?.items
            maybeReturnOrCollect("View", viewItemsResponse.code(), viewItems)?.let { return it }
            maybeExpandContainers("View", viewItems)?.let { return it }

            val viewLatestResponse = api.getLatestItems(
                userId = userId,
                parentId = viewId,
                includeItemTypes = discoveryItemTypes,
                limit = 30,
                fields = fields
            )
            val viewLatestItems = viewLatestResponse.body()
            maybeReturnOrCollect("ViewLatest", viewLatestResponse.code(), viewLatestItems)?.let { return it }
            maybeExpandContainers("ViewLatest", viewLatestItems)?.let { return it }
        }

        if (preferConcreteMediaSource && collected.isNotEmpty()) {
            fun hasEmyaDirectUrl(item: BaseItemDto): Boolean {
                fun isEmya(raw: String?): Boolean {
                    val lower = raw?.lowercase().orEmpty()
                    return "/emya/video" in lower && "media_id=" in lower && "token=" in lower
                }
                return item.mediaSources.orEmpty().any { source ->
                    isEmya(source.directStreamUrl) || isEmya(source.path) || isEmya(source.transcodingUrl) ||
                        source.mediaStreams.orEmpty().any { stream -> isEmya(stream.deliveryUrl) || isEmya(stream.path) }
                } || item.externalUrls.orEmpty().any { externalUrl -> isEmya(externalUrl.url) } ||
                    item.mediaStreams.orEmpty().any { stream -> isEmya(stream.deliveryUrl) || isEmya(stream.path) }
            }
            fun sourcePriority(item: BaseItemDto): Int {
                val id = item.id.orEmpty()
                val type = item.type.orEmpty()
                var score = 0
                if (hasEmyaDirectUrl(item)) score += 100
                if (item.hasConcreteKeepAliveMediaSource()) score += 40
                if (type.equals("Episode", ignoreCase = true) || type.equals("Video", ignoreCase = true)) score += 20
                if (type.equals("Movie", ignoreCase = true)) score += 12
                when {
                    id.startsWith("ve-", ignoreCase = true) -> score += 10
                    id.startsWith("vm-", ignoreCase = true) -> score += 10
                    isEmosVirtualListId(id) -> score -= 60
                }
                if ((item.userData?.playbackPositionTicks ?: 0L) <= 0L) score += 4
                if (item.userData?.played != true) score += 2
                return score
            }
            val merged = collected
                .distinctBy { item -> item.id.orEmpty() }
                .sortedWith(
                    compareByDescending<BaseItemDto> { item -> sourcePriority(item) }
                        .thenBy { item -> item.id.orEmpty() }
                )
            Log.i("KeepAlive", "Collected EMOS keepalive candidates count=${merged.size} viewCount=${viewIds.size} topPriority=${merged.firstOrNull()?.let(::sourcePriority) ?: 0}")
            return merged
        }
        Log.w("KeepAlive", "View candidates empty status=${viewsResponse.code()} viewCount=${viewIds.size}")

        return emptyList()
    }

    // Session reporting methods for playback progress tracking

    /**
     * Report playback start to Jellyfin server
     */
    suspend fun reportPlaybackStart(
        itemId: String,
        playSessionId: String? = null,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        positionTicks: Long? = null,
        playMethod: String? = null
    ): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))

            val request = com.grmemby.data.model.PlaybackStartRequest(
                itemId = itemId,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                positionTicks = positionTicks,
                playMethod = playMethod
            )

            val response = api.reportPlaybackStart(request)

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to report playback start: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Report playback progress to Jellyfin server
     */
    suspend fun reportPlaybackProgress(
        itemId: String,
        positionTicks: Long,
        playSessionId: String? = null,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        isPaused: Boolean = false,
        isMuted: Boolean = false,
        volumeLevel: Int? = null,
        playMethod: String? = null
    ): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))

            val request = com.grmemby.data.model.PlaybackProgressRequest(
                itemId = itemId,
                positionTicks = positionTicks,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                isPaused = isPaused,
                isMuted = isMuted,
                volumeLevel = volumeLevel,
                playMethod = playMethod
            )

            val response = api.reportPlaybackProgress(request)

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to report playback progress: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Report playback stopped to Jellyfin server
     */
    suspend fun reportPlaybackStopped(
        itemId: String,
        positionTicks: Long? = null,
        playSessionId: String? = null,
        mediaSourceId: String? = null,
        failed: Boolean = false
    ): Result<Unit> {
        return try {
            val api = getApi() ?: return Result.failure(Exception("API not available"))

            val request = com.grmemby.data.model.PlaybackStoppedRequest(
                itemId = itemId,
                positionTicks = positionTicks,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                failed = failed
            )

            val response = api.reportPlaybackStopped(request)

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to report playback stopped: ${response.code()} - ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Extension functions for BaseItemDto
/**
 * Extension function to get image URL for BaseItemDto
 * Returns the item ID which will be used by the image loader to construct the full URL
 */
fun BaseItemDto.getImageUrl(imageType: String = "Primary"): String {
    // Return the item ID - the LazyImageLoader will handle constructing the full URL
    return this.id ?: ""
}

/**
 * Get formatted runtime string from ticks
 */
fun BaseItemDto.getFormattedRuntime(): String {
    return runTimeTicks?.let { ticks ->
        val minutes = (ticks / 600000000).toInt()
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        if (hours > 0) "${hours}h ${remainingMinutes}m" else "${minutes}m"
    } ?: ""
}

/**
 * Get formatted year and genre string
 */
fun BaseItemDto.getYearAndGenre(): String {
    val year = productionYear?.toString() ?: "Unknown"
    val genre = genres?.firstOrNull() ?: "Unknown"
    return "$year | $genre"
}

/**
 * Get formatted rating string
 */
fun BaseItemDto.getFormattedRating(): String? {
    return communityRating?.let { rating ->
        String.format("%.1f", rating)
    }
}

/**
 * Get resume position in ticks from user data
 */
fun BaseItemDto.getResumePositionTicks(): Long? {
    return userData?.playbackPositionTicks
}

/**
 * Check if item is resumable (has a saved position and is not finished)
 */
fun BaseItemDto.isResumable(): Boolean {
    val positionTicks = userData?.playbackPositionTicks ?: return false
    val totalTicks = runTimeTicks ?: return false

    // Consider item resumable if position is > 0 and < 95% of total runtime
    return positionTicks > 0 && positionTicks < (totalTicks * 0.95)
}

/**
 * Get resume position as percentage (0.0 to 1.0)
 */
fun BaseItemDto.getResumePercentage(): Double {
    val positionTicks = userData?.playbackPositionTicks ?: return 0.0
    val totalTicks = runTimeTicks ?: return 0.0

    return if (totalTicks > 0) {
        (positionTicks.toDouble() / totalTicks.toDouble()).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
}

/**
 * Get formatted resume position as time string (e.g., "15:30")
 */
fun BaseItemDto.getFormattedResumePosition(): String? {
    val positionTicks = userData?.playbackPositionTicks ?: return null
    val seconds = (positionTicks / 10_000_000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format("%d:%02d", minutes, seconds % 60)
    }
}
