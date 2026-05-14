package com.grmemby.app.ui.screens.dashboard.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grmemby.app.ui.components.common.filterSeerTitles
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.SeerrRecommendationTitle
import com.grmemby.data.repository.AuthRepository
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.MediaRepository
import com.grmemby.data.repository.SeerrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val authRepository = AuthRepositoryProvider.getInstance(context)
    private val seerrRepository = SeerrRepository(context)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadsuggestions()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()

        if (query.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                error = null
            )
            searchJob = viewModelScope.launch {
                delay(300)
                searchAndCategorize(query)
            }
        } else {
            _uiState.value = _uiState.value.copy(
                movieResults = emptyList(),
                showResults = emptyList(),
                episodeResults = emptyList(),
                serverResultSections = emptyList(),
                seerrMovieResults = emptyList(),
                seerrShowResults = emptyList(),
                isSearching = false,
                error = null
            )
        }
    }

    fun executeSearch() {
        val query = _searchQuery.value
        if (query.isNotEmpty()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                searchAndCategorize(query)
            }
        }
    }

    fun openSearchResult(
        result: SearchServerResultItem,
        onOpened: (BaseItemDto) -> Unit
    ) {
        viewModelScope.launch {
            val activeServerId = _uiState.value.activeServerId
            if (result.serverId == activeServerId || result.serverId.isBlank()) {
                onOpened(result.item)
                return@launch
            }

            authRepository.switchServer(result.serverId)
                .onSuccess { switchedServer ->
                    _uiState.value = _uiState.value.copy(activeServerId = switchedServer.id)
                    onOpened(result.item)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(error = "切换服务器失败")
                }
        }
    }

    private suspend fun searchAndCategorize(query: String) {
        _uiState.value = _uiState.value.copy(
            isSearching = true,
            error = null
        )

        val snapshot = authRepository.getActiveSessionSnapshot()
        val serverSections = loadAggregateServerSearchSections(
            query = query,
            activeServerId = snapshot.activeServerId,
            savedServers = snapshot.savedServers
        )

        val allItems = serverSections.flatMap { section -> section.results.map { it.item } }
        val buckets = categorizeSearchResults(allItems)
        val activeServerId = snapshot.activeServerId
        val isSeerrConnected = seerrRepository.getSavedConnectionInfo(activeServerId)?.isVerified == true
        val seerrTitles = fetchSeerrTitles(
            query = query,
            activeServerId = activeServerId,
            isSeerrConnected = isSeerrConnected
        )

        if (serverSections.isEmpty() && seerrTitles.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                error = "Search failed",
                serverResultSections = emptyList(),
                movieResults = emptyList(),
                showResults = emptyList(),
                episodeResults = emptyList(),
                seerrMovieResults = emptyList(),
                seerrShowResults = emptyList(),
                activeServerId = activeServerId
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            serverResultSections = serverSections,
            movieResults = buckets.movies,
            showResults = buckets.shows,
            episodeResults = emptyList(),
            seerrMovieResults = buildSeerrResults(
                seerrTitles = seerrTitles,
                mediaType = "movie",
                query = query,
                localItems = buckets.movies
            ),
            seerrShowResults = buildSeerrResults(
                seerrTitles = seerrTitles,
                mediaType = "tv",
                query = query,
                localItems = buckets.shows
            ),
            isSearching = false,
            error = null,
            activeServerId = activeServerId
        )
    }

    private suspend fun loadAggregateServerSearchSections(
        query: String,
        activeServerId: String?,
        savedServers: List<AuthRepository.SavedServer>
    ): List<SearchServerResultSection> = coroutineScope {
        val orderedServers = savedServers
            .filter { server -> server.id.isNotBlank() && server.serverUrl.isNotBlank() && server.userId.isNotBlank() }
            .distinctBy { server -> server.id }
            .sortedWith(
                compareByDescending<AuthRepository.SavedServer> { server -> server.id == activeServerId }
                    .thenByDescending { server -> server.lastUsedAt }
            )

        orderedServers.map { server ->
            async {
                val items = mediaRepository.searchItemsForSavedServer(
                    savedServer = server,
                    searchTerm = query,
                    includeItemTypes = SEARCH_RESULT_ITEM_TYPES,
                    limit = 80
                ).getOrNull()
                    ?.let { items -> filterSearchItems(items, query) }
                    .orEmpty()
                    .filterNot { item -> item.type.equals("Episode", ignoreCase = true) }
                    .take(30)

                SearchServerResultSection(
                    serverId = server.id,
                    serverName = server.serverName.takeIf { it.isNotBlank() } ?: "服务器",
                    results = items.map { item ->
                        SearchServerResultItem(
                            item = item,
                            serverId = server.id,
                            serverName = server.serverName.takeIf { it.isNotBlank() } ?: "服务器",
                            imageUrl = buildSearchImageUrl(server = server, item = item)
                        )
                    }
                )
            }
        }.awaitAll().filter { section -> section.results.isNotEmpty() }
    }

    private fun buildSearchImageUrl(
        server: AuthRepository.SavedServer,
        item: BaseItemDto
    ): String? {
        val itemId = item.posterImageItemId() ?: return null
        return mediaRepository.getImageUrlStringForServer(
            serverUrl = server.serverUrl,
            itemId = itemId,
            imageType = "Primary",
            imageTag = item.posterImageTag(),
            width = 300,
            height = 450,
            quality = 80,
            enableImageEnhancers = !server.serverTypeRaw.equals("EMBY", ignoreCase = true)
        )
    }

    private suspend fun loadServerSearchItems(query: String): List<BaseItemDto>? {
        return try {
            mediaRepository.searchItems(
                searchTerm = query,
                includeItemTypes = SEARCH_RESULT_ITEM_TYPES,
                limit = 80
            ).getOrNull()?.let { items ->
                filterSearchItems(items, query)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadsuggestions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(SuggestionsLoading = true)

            val recommendedItems = mediaRepository.getUserItems(
                includeItemTypes = "Movie,Series",
                recursive = true,
                sortBy = "Random",
                limit = 28,
                fields = RECOMMENDED_SEARCH_TAG_FIELDS
            ).map { result ->
                result.items.orEmpty().validRecommendedItems()
            }.recoverCatching {
                mediaRepository.getLatestItems(
                    includeItemTypes = "Movie,Series",
                    limit = 28,
                    fields = RECOMMENDED_SEARCH_TAG_FIELDS
                ).getOrThrow().validRecommendedItems()
            }.getOrElse { emptyList() }

            _uiState.value = _uiState.value.copy(
                suggestions = recommendedItems,
                suggestionTags = buildRecommendedSearchTags(
                    recommendedItems = recommendedItems
                ),
                SuggestionsLoading = false,
                error = null
            )
        }
    }

    private fun filterSearchItems(items: List<BaseItemDto>, query: String): List<BaseItemDto> {
        return items
            .mapNotNull { item ->
                bestSearchMatch(query, item.name, item.originalTitle)?.let { match -> item to match }
            }
            .sortedBy { (_, match) -> match.priority }
            .map { (item, _) -> item }
    }

    private fun applySearchResults(
        localItems: List<BaseItemDto>,
        seerrTitles: List<SeerrRecommendationTitle>,
        query: String
    ) {
        val buckets = categorizeSearchResults(localItems)

        _uiState.value = _uiState.value.copy(
            movieResults = buckets.movies,
            showResults = buckets.shows,
            episodeResults = buckets.episodes,
            seerrMovieResults = buildSeerrResults(
                seerrTitles = seerrTitles,
                mediaType = "movie",
                query = query,
                localItems = buckets.movies
            ),
            seerrShowResults = buildSeerrResults(
                seerrTitles = seerrTitles,
                mediaType = "tv",
                query = query,
                localItems = buckets.shows
            ),
            isSearching = false,
            error = null
        )
    }

    private fun buildSeerrResults(
        seerrTitles: List<SeerrRecommendationTitle>,
        mediaType: String,
        query: String,
        localItems: List<BaseItemDto>
    ): List<SeerrRecommendationTitle> {
        if (seerrTitles.isEmpty()) return emptyList()

        val matchedTitles = seerrTitles
            .filter { title -> title.mediaType.equals(mediaType, ignoreCase = true) }
            .mapNotNull { title ->
                bestSearchMatch(query, title.title)?.let { match -> title to match }
            }
            .sortedBy { (_, match) -> match.priority }
            .map { (title, _) -> title }
            .distinctBy { title -> title.tmdbId }

        return filterSeerTitles(
            seerrTitles = matchedTitles,
            localItems = localItems
        ).take(12)
    }

    private fun bestSearchMatch(query: String, vararg texts: String?): SearchMatch? {
        return texts
            .filterNotNull()
            .mapNotNull { text -> textSearchMatch(text, query) }
            .minByOrNull { match -> match.priority }
    }

    private fun textSearchMatch(
        text: String,
        query: String
    ): SearchMatch? {
        val trimmedQuery = query.trim()
        if (text.isBlank() || trimmedQuery.isBlank()) return null

        val lowerQuery = trimmedQuery.lowercase(Locale.US)
        val normalizedQuery = trimmedQuery.normalizedSearchKey()
        val lowerText = text.lowercase(Locale.US)
        val normalizedText = text.normalizedSearchKey()
        val queryWords = lowerQuery
            .split(Regex("[^a-z0-9]+"))
            .filter { word -> word.isNotBlank() }
        val tokens = lowerText.split(Regex("[^a-z0-9]+")).filter { token -> token.isNotBlank() }
        val allWordsMatch = queryWords.all { word ->
            tokens.any { token ->
                token == word || token.startsWith(word) || token.contains(word)
            }
        }

        return when {
            normalizedText == normalizedQuery -> SearchMatch.EXACT
            lowerText == lowerQuery -> SearchMatch.EXACT_TEXT
            lowerText.contains(lowerQuery) -> SearchMatch.PHRASE
            normalizedText.contains(normalizedQuery) -> SearchMatch.NORMALIZED_PHRASE
            allWordsMatch -> SearchMatch.ALL_WORDS
            queryWords.size == 1 && tokens.any { token ->
                token.startsWith(queryWords.first()) || token.contains(queryWords.first())
            } -> SearchMatch.SINGLE_WORD
            else -> null
        }
    }

    private fun String.normalizedSearchKey(): String {
        return lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
    }

    private suspend fun fetchSeerrTitles(
        query: String,
        activeServerId: String?,
        isSeerrConnected: Boolean
    ): List<SeerrRecommendationTitle> {
        val scopeId = activeServerId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!isSeerrConnected) return emptyList()

        return seerrRepository.searchTitles(
            scopeId = scopeId,
            query = query,
            limit = 20
        ).getOrElse { emptyList() }
    }

    private enum class SearchMatch(val priority: Int) {
        EXACT(0),
        EXACT_TEXT(1),
        PHRASE(2),
        NORMALIZED_PHRASE(3),
        ALL_WORDS(4),
        SINGLE_WORD(5)
    }
}

private fun List<BaseItemDto>.validRecommendedItems(): List<BaseItemDto> {
    return filter { item -> !item.id.isNullOrBlank() && !item.name.isNullOrBlank() }
        .distinctBy { item -> item.id }
}

data class SearchUiState(
    val suggestions: List<BaseItemDto> = emptyList(),
    val suggestionTags: List<String> = DEFAULT_RECOMMENDED_SEARCH_TAGS,
    val movieResults: List<BaseItemDto> = emptyList(),
    val showResults: List<BaseItemDto> = emptyList(),
    val episodeResults: List<BaseItemDto> = emptyList(),
    val serverResultSections: List<SearchServerResultSection> = emptyList(),
    val activeServerId: String? = null,
    val seerrMovieResults: List<SeerrRecommendationTitle> = emptyList(),
    val seerrShowResults: List<SeerrRecommendationTitle> = emptyList(),
    val isSearching: Boolean = false,
    val SuggestionsLoading: Boolean = false,
    val error: String? = null
)

data class SearchServerResultSection(
    val serverId: String,
    val serverName: String,
    val results: List<SearchServerResultItem>
)

data class SearchServerResultItem(
    val item: BaseItemDto,
    val serverId: String,
    val serverName: String,
    val imageUrl: String? = null
)
