package com.grmemby.app.ui.screens.dashboard.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grmemby.data.repository.AuthRepository
import com.grmemby.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

internal data class ServerManagementLoadTarget(
    val key: String,
    val server: AuthRepository.SavedServer
)

internal data class ServerCardOverview(
    val movieCount: Int? = null,
    val seriesCount: Int? = null,
    val lastPlayedAtEpochMs: Long? = null,
    val latencyMs: Int? = null,
    val isConnected: Boolean = false,
    val logoAccentArgb: Int,
    val logoUrl: String? = null,
    val isLoading: Boolean = true
)

internal data class ServerManagementOverviewUiState(
    val overviews: Map<String, ServerCardOverview> = emptyMap()
)

internal class ServerManagementViewModel(
    context: Context
) : ViewModel() {
    private val appContext = context.applicationContext
    private val mediaRepository = MediaRepository(appContext)
    private val authRepository = AuthRepository(appContext)
    private val overviewCachePrefs = appContext.getSharedPreferences(OVERVIEW_CACHE_PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(ServerManagementOverviewUiState(cachedOverviews))
    val uiState: StateFlow<ServerManagementOverviewUiState> = _uiState.asStateFlow()

    private var activeTargetSignatures: Map<String, String> = emptyMap()
    private var loadGeneration: Long = 0L

    companion object {
        private const val SERVER_ICON_PACK_URL = "https://raw.githubusercontent.com/baiitang/Sakura/main/Fileball/Yuan/tubiao.json"
        private const val SERVER_ICON_PACK_ASSET = "server_icon_pack_tubiao.json"
        private const val OVERVIEW_CACHE_PREFS = "server_management_overview_cache_v1"
        private const val OVERVIEW_CACHE_JSON_KEY = "overviews_json"
        private const val OVERVIEW_CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L
        private const val ICON_PACK_CACHE_JSON_KEY = "server_icon_pack_json"
        private const val ICON_PACK_CACHE_UPDATED_KEY = "server_icon_pack_updated_at"
        private const val ICON_PACK_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val ICON_PACK_CONNECT_TIMEOUT_MS = 450
        private const val ICON_PACK_READ_TIMEOUT_MS = 450
        private val ICON_TOKEN_STOP_WORDS = setOf(
            "emby", "jellyfin", "media", "server", "png", "jpg", "jpeg", "webp", "svg", "raw",
            "github", "githubusercontent", "softlyx", "fileball", "main", "yuan", "sakura", "baiitang",
            "com", "net", "org", "cn", "top", "xyz", "vip", "www", "app", "图标", "服务器", "公益服", "影视服", "媒体服", "服"
        )
        private var cachedOverviews: Map<String, ServerCardOverview> = emptyMap()
        private var cachedIconPack: List<RemoteIconPackEntry>? = null
    }

    private data class RemoteIconPackEntry(
        val name: String,
        val url: String
    )

    private data class ScoredIconPackEntry(
        val entry: RemoteIconPackEntry,
        val score: Int
    )

    fun load(targets: List<ServerManagementLoadTarget>) {
        loadInternal(targets = targets, forceRefresh = false)
    }

    fun refresh(targets: List<ServerManagementLoadTarget>) {
        loadInternal(targets = targets, forceRefresh = true)
    }

    fun markLastPlayedNow(targets: List<ServerManagementLoadTarget>) {
        val normalizedTargets = targets.distinctBy { it.key }
        if (normalizedTargets.isEmpty()) return
        val now = System.currentTimeMillis()
        val targetByKey = normalizedTargets.associateBy { it.key }
        _uiState.update { current ->
            val updatedEntries = targetByKey.mapValues { (key, target) ->
                val existing = current.overviews[key] ?: cachedOverviews[key]
                (existing ?: ServerCardOverview(
                    logoAccentArgb = fallbackAccentArgb(target.server),
                    logoUrl = target.server.serverLogoUrl?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) },
                    isLoading = false
                )).copy(
                    lastPlayedAtEpochMs = now,
                    isLoading = false
                )
            }
            current.copy(overviews = current.overviews + updatedEntries)
        }
        cachedOverviews = _uiState.value.overviews
        persistOverviewCache(cachedOverviews)
    }

    private fun loadInternal(
        targets: List<ServerManagementLoadTarget>,
        forceRefresh: Boolean
    ) {
        val normalizedTargets = targets.distinctBy { it.key }
        val keys = normalizedTargets.map { it.key }.toSet()
        val targetSignatures = normalizedTargets.associate { target ->
            target.key to target.overviewSignature()
        }

        val persistedOverviews = loadPersistedOverviewCache(keys)
        if (persistedOverviews.isNotEmpty()) {
            cachedOverviews = cachedOverviews + persistedOverviews
        }

        val targetsToLoad = if (forceRefresh) {
            normalizedTargets
        } else {
            val currentOverviews = _uiState.value.overviews
            normalizedTargets.filter { target ->
                val previousSignature = activeTargetSignatures[target.key]
                val hasAnyOverview = currentOverviews[target.key] != null ||
                    cachedOverviews[target.key] != null ||
                    persistedOverviews[target.key] != null
                previousSignature != targetSignatures[target.key] || !hasAnyOverview
            }
        }

        activeTargetSignatures = if (forceRefresh) {
            activeTargetSignatures + targetSignatures.filterKeys { key -> targetsToLoad.any { it.key == key } }
        } else {
            targetSignatures
        }

        if (!forceRefresh && targetsToLoad.isEmpty()) {
            _uiState.update { current ->
                current.copy(overviews = current.overviews.filterKeys { it in keys })
            }
            return
        }

        val generation = ++loadGeneration

        _uiState.update { current ->
            val scopedOverviews = if (forceRefresh) {
                current.overviews
            } else {
                current.overviews.filterKeys { it in keys }
            }
            val loadingEntries = targetsToLoad.associate { target ->
                val cached = cachedOverviews[target.key]
                val existing = current.overviews[target.key]
                val existingLoaded = existing?.takeIf { !it.isLoading }
                val fallback = ServerCardOverview(
                    logoAccentArgb = fallbackAccentArgb(target.server),
                    logoUrl = target.server.serverLogoUrl?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) },
                    isLoading = true
                )
                val base = cached ?: existingLoaded ?: persistedOverviews[target.key] ?: fallback
                target.key to base.copy(isLoading = true)
            }
            current.copy(overviews = scopedOverviews + loadingEntries)
        }

        if (targetsToLoad.isEmpty()) return

        viewModelScope.launch {
            targetsToLoad.forEach { target ->
                launch {
                    val previous = _uiState.value.overviews[target.key]
                        ?: cachedOverviews[target.key]
                        ?: persistedOverviews[target.key]
                    val fallbackAccent = fallbackAccentArgb(target.server)
                    val cachedLogoUrl = previous?.logoUrl
                        ?: target.server.serverLogoUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) }
                    val resolveLogoOnThisPass = cachedLogoUrl.isNullOrBlank()

                    val overview = mediaRepository.getSavedServerOverview(
                        savedServer = target.server,
                        includeLastPlayed = true,
                        fastMode = true
                    ).getOrNull()
                    if (generation != loadGeneration) return@launch

                    val fastCard = ServerCardOverview(
                        movieCount = overview?.movieCount ?: previous?.movieCount,
                        seriesCount = overview?.seriesCount ?: previous?.seriesCount,
                        lastPlayedAtEpochMs = overview?.lastPlayedAtEpochMs ?: previous?.lastPlayedAtEpochMs,
                        latencyMs = overview?.latencyMs ?: previous?.latencyMs,
                        isConnected = overview?.isConnected == true || previous?.isConnected == true,
                        logoAccentArgb = previous?.logoAccentArgb ?: fallbackAccent,
                        logoUrl = cachedLogoUrl,
                        isLoading = false
                    )
                    updateLoadedOverview(target.key, fastCard)

                    if (!resolveLogoOnThisPass) return@launch
                    val resolvedLogoUrl = resolveServerLogoUrl(target.server)
                        ?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) }
                    if (generation != loadGeneration || resolvedLogoUrl.isNullOrBlank()) return@launch
                    if (resolvedLogoUrl != target.server.serverLogoUrl) {
                        authRepository.updateSavedServerLogo(target.server.id, resolvedLogoUrl)
                    }
                    val logoCard = (_uiState.value.overviews[target.key] ?: fastCard).copy(
                        logoUrl = resolvedLogoUrl,
                        isLoading = false
                    )
                    updateLoadedOverview(target.key, logoCard)

                    val accent = extractDominantLogoAccentArgb(
                        imageUrl = resolvedLogoUrl,
                        fallback = fallbackAccent)
                    if (generation != loadGeneration) return@launch
                    val currentCard = _uiState.value.overviews[target.key] ?: logoCard
                    updateLoadedOverview(
                        target.key,
                        currentCard.copy(
                            logoAccentArgb = accent,
                            logoUrl = resolvedLogoUrl,
                            isLoading = false)
                    )

                }
            }
        }
    }

    private fun ServerManagementLoadTarget.overviewSignature(): String {
        return "${key}:${server.effectiveServerUrl}:${server.serverTypeRaw}:${server.serverLogoUrl.orEmpty()}:${server.activeLineId.orEmpty()}:${server.serverLines.joinToString { line -> "${line.id}:${line.url}:${line.isReverseProxy}" }}"
    }

    private fun updateLoadedOverview(key: String, overview: ServerCardOverview) {
        _uiState.update { current ->
            current.copy(overviews = current.overviews + (key to overview))
        }
        val latest = _uiState.value.overviews
        cachedOverviews = latest
        persistOverviewCache(latest)
    }

    private fun loadPersistedOverviewCache(keys: Set<String>): Map<String, ServerCardOverview> {
        if (keys.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        return runCatching {
            val root = JSONObject(overviewCachePrefs.getString(OVERVIEW_CACHE_JSON_KEY, null) ?: return emptyMap())
            buildMap {
                keys.forEach { key ->
                    val json = root.optJSONObject(key) ?: return@forEach
                    val updatedAt = json.optLong("updatedAt", 0L)
                    if (updatedAt <= 0L || now - updatedAt > OVERVIEW_CACHE_MAX_AGE_MS) return@forEach
                    put(
                        key,
                        ServerCardOverview(
                            movieCount = json.optNullableInt("movieCount"),
                            seriesCount = json.optNullableInt("seriesCount"),
                            lastPlayedAtEpochMs = json.optNullableLong("lastPlayedAtEpochMs"),
                            latencyMs = json.optNullableInt("latencyMs"),
                            isConnected = json.optBoolean("isConnected", false),
                            logoAccentArgb = json.optInt("logoAccentArgb", AndroidColor.rgb(104, 195, 173)),
                            logoUrl = json.optString("logoUrl").takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) },
                            isLoading = false
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistOverviewCache(overviews: Map<String, ServerCardOverview>) {
        val now = System.currentTimeMillis()
        runCatching {
            val root = JSONObject()
            overviews.forEach { (key, overview) ->
                if (key.isBlank() || overview.isLoading) return@forEach
                root.put(
                    key,
                    JSONObject().apply {
                        putNullable("movieCount", overview.movieCount)
                        putNullable("seriesCount", overview.seriesCount)
                        putNullable("lastPlayedAtEpochMs", overview.lastPlayedAtEpochMs)
                        putNullable("latencyMs", overview.latencyMs)
                        put("isConnected", overview.isConnected)
                        put("logoAccentArgb", overview.logoAccentArgb)
                        putNullable(
                            "logoUrl",
                            overview.logoUrl?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) }
                        )
                        put("updatedAt", now)
                    }
                )
            }
            overviewCachePrefs.edit().putString(OVERVIEW_CACHE_JSON_KEY, root.toString()).apply()
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }

    private fun JSONObject.putNullable(name: String, value: Any?) {
        if (value == null) {
            put(name, JSONObject.NULL)
        } else {
            put(name, value)
        }
    }

    private fun fallbackAccentArgb(server: AuthRepository.SavedServer): Int {
        val palette = when {
            server.serverTypeRaw.equals("EMBY", ignoreCase = true) -> listOf(
                AndroidColor.rgb(104, 78, 214),
                AndroidColor.rgb(76, 172, 117),
                AndroidColor.rgb(109, 133, 222)
            )
            else -> listOf(
                AndroidColor.rgb(91, 173, 220),
                AndroidColor.rgb(104, 195, 173),
                AndroidColor.rgb(138, 124, 214),
                AndroidColor.rgb(236, 165, 92),
                AndroidColor.rgb(228, 130, 157)
            )
        }
        val seed = "${server.serverName}|${server.username}|${server.id}".hashCode()
        val index = (seed and Int.MAX_VALUE) % palette.size
        return palette[index]
    }

    private suspend fun resolveServerLogoUrl(server: AuthRepository.SavedServer): String? = withContext(Dispatchers.IO) {
        val storedLogo = server.serverLogoUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeBundledDefaultPath(it) }
        val storedIconPackLogo = storedLogo?.takeIf(::isIconPackLogoUrl)
        val storedCustomLogo = storedLogo?.takeIf { !isIconPackLogoUrl(it) }
        val iconPackCandidates = iconPackLogoCandidateUrls(server)
        val prioritizedStoredIconPackLogo = storedIconPackLogo?.takeIf {
            iconPackCandidates.isEmpty() || it in iconPackCandidates
        }
        // The icon pack is already a curated JSON filename index. Return the matched URL immediately;
        // Coil will validate/cache the image itself, and the card can display within the 1s target.
        listOfNotNull(prioritizedStoredIconPackLogo).firstOrNull()?.let { return@withContext it }
        iconPackCandidates.firstOrNull()?.let { return@withContext it }
        storedCustomLogo?.let { return@withContext it }

        val directCandidates = (
            brandingLogoCandidateUrls(server.effectiveServerUrl) +
                brandingLogoCandidateUrls(server.serverUrl) +
                logoCandidateUrls(server.effectiveServerUrl) +
                logoCandidateUrls(server.serverUrl)
            )
            .distinct()
        directCandidates.firstOrNull { candidate -> isUsableLogoUrl(candidate) }?.let { return@withContext it }

        val discoveredCandidates = (
            discoverLogoCandidateUrls(server.effectiveServerUrl) +
                discoverLogoCandidateUrls(server.serverUrl)
            )
            .distinct()
        discoveredCandidates.firstOrNull { candidate -> isUsableLogoUrl(candidate) }
    }

    private fun isIconPackLogoUrl(url: String): Boolean {
        val normalized = url.lowercase(Locale.ROOT)
        return "quanx-icon-rule" in normalized ||
            "raw.githubusercontent.com/baiitang/sakura" in normalized ||
            "fileball/yuan" in normalized ||
            ("lige47" in normalized && ("githubusercontent" in normalized || "github.com" in normalized))
    }

    private fun iconPackLogoCandidateUrls(server: AuthRepository.SavedServer): List<String> {
        val iconPack = remoteIconPackEntries()
        if (iconPack.isEmpty()) return emptyList()
        val matchTokens = serverIconMatchTokens(server)
        if (matchTokens.isEmpty()) return emptyList()
        return iconPack
            .mapNotNull { entry ->
                val score = iconPackMatchScore(matchTokens, iconPackEntryTokens(entry))
                if (score > 0) ScoredIconPackEntry(entry = entry, score = score) else null
            }
            .sortedWith(
                compareByDescending<ScoredIconPackEntry> { it.score }
                    .thenBy { it.entry.name.length }
                    .thenBy { it.entry.name }
            )
            .distinctBy { it.entry.url }
            .map { it.entry.url }
            .take(4)
    }

    private fun iconPackMatchScore(matchTokens: Set<String>, iconTokens: Set<String>): Int {
        var best = 0
        matchTokens.forEach { serverToken ->
            iconTokens.forEach { iconToken ->
                val score = when {
                    serverToken == iconToken -> 1_000 + iconToken.length.coerceAtMost(40)
                    iconToken.length >= 3 && serverToken.contains(iconToken) -> 760 + iconToken.length.coerceAtMost(40)
                    serverToken.length >= 3 && iconToken.contains(serverToken) -> 720 + serverToken.length.coerceAtMost(40)
                    isNearIconToken(serverToken, iconToken) -> 560 + minOf(serverToken.length, iconToken.length).coerceAtMost(40)
                    else -> 0
                }
                if (score > best) best = score
            }
        }
        return best
    }

    private fun iconPackEntryTokens(entry: RemoteIconPackEntry): Set<String> {
        val normalizedName = normalizeIconToken(entry.name)
        val fileName = runCatching {
            URI(entry.url).path.orEmpty().substringAfterLast('/').substringBeforeLast('.')
        }.getOrDefault(entry.url.substringAfterLast('/').substringBeforeLast('.'))
        val normalizedFileName = normalizeIconToken(fileName)
        val urlParts = runCatching {
            val uri = URI(entry.url)
            (listOfNotNull(uri.host) + uri.path.orEmpty().split('/', '-', '_', '.', ':'))
        }.getOrDefault(entry.url.split('/', '-', '_', '.', ':'))
        val splitTokens = (
            entry.name.split(' ', '-', '_', '.', '/', ':', '·', '｜', '|', '(', ')') +
                fileName.split(' ', '-', '_', '.', '/', ':', '·', '｜', '|', '(', ')') +
                urlParts
            ).map(::normalizeIconToken)
        val decoratedTokens = (listOf(normalizedName, normalizedFileName) + splitTokens).flatMap { token ->
            listOf(
                token,
                stripServerNameDecorators(token),
                token.removeSuffix("emby"),
                token.removeSuffix("jellyfin"),
                token.removePrefix("emby"),
                token.removePrefix("jellyfin")
            )
        }
        return decoratedTokens
            .map(::normalizeIconToken)
            .filter { it.length >= 2 && it !in ICON_TOKEN_STOP_WORDS }
            .toSet()
    }

    private fun isNearIconToken(left: String, right: String): Boolean {
        if (left.length < 4 || right.length < 4) return false
        val longer = maxOf(left.length, right.length)
        if (kotlin.math.abs(left.length - right.length) > 2 || longer > 16) return false
        val distance = levenshteinDistanceAtMost(left, right, maxDistance = 2)
        return distance <= if (longer <= 8) 1 else 2
    }

    private fun levenshteinDistanceAtMost(left: String, right: String, maxDistance: Int): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    @Synchronized
    private fun remoteIconPackEntries(): List<RemoteIconPackEntry> {
        cachedIconPack?.let { return it }
        val now = System.currentTimeMillis()
        val persisted = overviewCachePrefs.getString(ICON_PACK_CACHE_JSON_KEY, null)
            ?.takeIf { overviewCachePrefs.getLong(ICON_PACK_CACHE_UPDATED_KEY, 0L).let { updated -> updated > 0L && now - updated <= ICON_PACK_CACHE_MAX_AGE_MS } }
            ?.let(::parseIconPackEntries)
            .orEmpty()
        if (persisted.isNotEmpty()) {
            cachedIconPack = persisted
            return persisted
        }

        val bundled = runCatching {
            appContext.assets.open(SERVER_ICON_PACK_ASSET).bufferedReader().use { it.readText() }
        }.map(::parseIconPackEntries).getOrDefault(emptyList())
        if (bundled.isNotEmpty()) {
            cachedIconPack = bundled
            return bundled
        }

        val networkText = fetchRemoteIconPackText()
        val loaded = networkText?.let(::parseIconPackEntries).orEmpty()
        if (loaded.isNotEmpty() && networkText != null) {
            overviewCachePrefs.edit()
                .putString(ICON_PACK_CACHE_JSON_KEY, networkText)
                .putLong(ICON_PACK_CACHE_UPDATED_KEY, now)
                .apply()
        }
        cachedIconPack = loaded
        return loaded
    }

    private fun fetchRemoteIconPackText(): String? {
        return runCatching {
            val connection = URL(SERVER_ICON_PACK_URL).openConnection().apply {
                connectTimeout = ICON_PACK_CONNECT_TIMEOUT_MS
                readTimeout = ICON_PACK_READ_TIMEOUT_MS
                useCaches = true
                setRequestProperty("User-Agent", "Grmemby/1.5 ServerIconPack")
                setRequestProperty("Accept", "application/json,text/plain,*/*;q=0.7")
            }
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) return@runCatching null
            }
            connection.getInputStream().bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (out.length < 160_000) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        }.getOrNull()
    }

    private fun parseIconPackEntries(text: String): List<RemoteIconPackEntry> {
        return runCatching {
            val icons = JSONObject(text).optJSONArray("icons") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until icons.length()) {
                    val item = icons.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                        add(RemoteIconPackEntry(name = name, url = url))
                    }
                }
            }
        }.getOrElse {
            Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .mapNotNull { match ->
                    val name = decodeJsonString(match.groupValues[1]).trim()
                    val url = decodeJsonString(match.groupValues[2]).trim()
                    if (name.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                        RemoteIconPackEntry(name = name, url = url)
                    } else {
                        null
                    }
                }
                .toList()
        }
    }

    private fun serverIconMatchTokens(server: AuthRepository.SavedServer): Set<String> {
        val hostTokens = listOf(server.serverUrl, server.effectiveServerUrl)
            .flatMap { serverUrl ->
                runCatching {
                    URI(serverUrl).host.orEmpty()
                        .split('.', '-', '_')
                        .filter { it.length >= 2 && it !in setOf("com", "net", "org", "cn", "top", "xyz", "vip", "www") }
                }.getOrDefault(emptyList())
            }
        val rawTokens = listOf(
            server.serverName,
            server.serverRemark.orEmpty(),
            server.username,
            server.lineLabel()
        ) + hostTokens
        return rawTokens
            .flatMap { token ->
                val baseParts = token.split(' ', '-', '_', '.', '/', ':', '·', '｜', '|') + token
                baseParts.flatMap { part ->
                    val fixed = part.replace("nyamiedia", "nyamedia", ignoreCase = true)
                    val normalized = normalizeIconToken(fixed)
                    val stripped = stripServerNameDecorators(normalized)
                    listOf(part, fixed, normalized, stripped)
                }
            }
            .map(::normalizeIconToken)
            .filter { it.length >= 2 && it !in ICON_TOKEN_STOP_WORDS }
            .toSet()
    }

    private fun stripServerNameDecorators(value: String): String {
        return value
            .removeSuffix("服务器")
            .removeSuffix("公益服")
            .removeSuffix("影视服")
            .removeSuffix("媒体服")
            .removeSuffix("服")
            .removeSuffix("影视")
            .removeSuffix("影院")
            .removeSuffix("电影")
            .removeSuffix("media")
            .removeSuffix("tv")
            .removePrefix("emby")
            .removePrefix("jellyfin")
    }

    private fun normalizeIconToken(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
    }

    private fun decodeJsonString(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun isUsableLogoUrl(candidate: String): Boolean {
        return runCatching {
            val connection = URL(candidate).openConnection().apply {
                connectTimeout = 1_600
                readTimeout = 2_200
                useCaches = true
                setRequestProperty("User-Agent", "Grmemby/1.5 ServerLogoResolver")
                setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/svg+xml,image/*,*/*;q=0.8")
            }
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) return@runCatching false
            }
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType.isNotBlank() && !contentType.startsWith("image/") && !contentType.contains("octet-stream")) {
                return@runCatching false
            }
            if (candidate.substringBefore('?').endsWith(".svg", ignoreCase = true) || contentType.contains("svg")) {
                connection.getInputStream().bufferedReader().use { reader ->
                    val svg = reader.readText().take(64_000)
                    return@runCatching svg.isNotBlank() && !looksLikeBundledDefaultSvg(candidate, svg)
                }
            }
            connection.getInputStream().use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return@runCatching false
                val valid = bitmap.width > 0 && bitmap.height > 0 && !looksLikeBundledDefaultLogo(candidate, bitmap)
                bitmap.recycle()
                valid
            }
        }.getOrDefault(false)
    }

    private fun discoverLogoCandidateUrls(serverUrl: String): List<String> {
        val bases = logoBaseUrls(serverUrl)
        val pagePaths = listOf("", "web/", "web/index.html", "Branding/Css", "Branding/Configuration", "logoswap/status", "logoswap/script", "web/manifest.json", "manifest.json")
        val found = mutableListOf<String>()
        bases.forEach { base ->
            pagePaths.forEach { path ->
                val pageUrl = if (path.isBlank()) "$base/" else "$base/$path"
                val text = readSmallTextUrl(pageUrl) ?: return@forEach
                extractLogoReferences(text).forEach { ref ->
                    resolveRelativeUrl(pageUrl, ref)?.let(found::add)
                }
            }
        }
        return found.distinct()
    }

    private fun readSmallTextUrl(url: String): String? {
        return runCatching {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 1_200
                readTimeout = 1_600
                useCaches = true
                setRequestProperty("User-Agent", "Grmemby/1.5 ServerLogoResolver")
                setRequestProperty("Accept", "text/html,text/css,application/json,*/*;q=0.7")
            }
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) return@runCatching null
            }
            val type = connection.contentType.orEmpty().lowercase()
            if (type.isNotBlank() && listOf("text/", "json", "javascript", "css").none { type.contains(it) }) {
                return@runCatching null
            }
            connection.getInputStream().bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (out.length < 96_000) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        }.getOrNull()
    }

    private fun extractLogoReferences(text: String): List<String> {
        val refs = mutableListOf<String>()
        Regex("""<link[^>]+(?:rel=[\"'][^\"']*(?:icon|apple-touch-icon|mask-icon|manifest)[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"']|href=[\"']([^\"']+)[\"'][^>]*rel=[\"'][^\"']*(?:icon|apple-touch-icon|mask-icon|manifest)[^\"']*[\"'])""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNullTo(refs) { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) ?: it.groupValues.getOrNull(2)?.takeIf(String::isNotBlank) }
        Regex("""[\"'](?:src|url|LogoUrl|logo|icon)[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNullTo(refs) { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) }
        Regex("""url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNullTo(refs) { match ->
                match.groupValues.getOrNull(1)
                    ?.trim(' ', '\'', '"')
                    ?.takeIf { ref -> ref.contains("logo", ignoreCase = true) || ref.contains("icon", ignoreCase = true) || ref.contains("brand", ignoreCase = true) }
            }
        return refs
            .filterNot { it.startsWith("data:", ignoreCase = true) }
            .distinct()
    }

    private fun resolveRelativeUrl(pageUrl: String, ref: String): String? {
        return runCatching { URI(pageUrl).resolve(ref.trim()).toString() }.getOrNull()
    }

    private fun brandingLogoCandidateUrls(serverUrl: String): List<String> {
        val paths = listOf(
            "Branding/Splashscreen?format=png",
            "Branding/Splashscreen",
            "Branding/Logo?format=png",
            "Branding/Logo",
            "logoswap/image",
            "customlogo/image",
            "customlogo.png",
            "web/customlogo.png",
            "configuration/customlogo.png"
        )
        return logoBaseUrls(serverUrl).flatMap { base -> paths.map { path -> "$base/$path" } }.distinct()
    }

    private fun logoCandidateUrls(serverUrl: String): List<String> {
        val paths = listOf(
            "Branding/Splashscreen?format=png",
            "Branding/Splashscreen",
            "Branding/Logo?format=png",
            "Branding/Logo",
            "logoswap/image",
            "customlogo/image",
            "customlogo.png",
            "web/customlogo.png",
            "configuration/customlogo.png",
            "web/assets/img/banner-light.png",
            "web/assets/img/banner-dark.png",
            "web/assets/img/logo.png",
            "web/assets/img/splashlogo.png",
            "web/images/logo.png",
            "web/css/images/logo.png",
            "web/css/images/mblogo.png",
            "web/assets/img/icon-transparent.png",
            "web/assets/img/icon.png",
            "web/assets/img/touchicon.png",
            "web/assets/img/jellyfin-logo.svg",
            "web/assets/img/icon-transparent.svg",
            "web/favicon.png",
            "web/favicon.ico",
            "web/favicon.svg",
            "favicon.png",
            "favicon.ico",
            "favicon.svg"
        )
        return logoBaseUrls(serverUrl).flatMap { base -> paths.map { path -> "$base/$path" } }.distinct()
    }

    private fun logoBaseUrls(serverUrl: String): List<String> {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return emptyList()
        val origin = runCatching {
            val uri = URI(trimmed)
            URI(uri.scheme, uri.authority, null, null, null).toString().trimEnd('/')
        }.getOrDefault(trimmed.removeSuffix("/emby"))
        return listOf(
            trimmed,
            origin,
            trimmed.removeSuffix("/emby"),
            trimmed.removeSuffix("/jellyfin")
        )
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun looksLikeBundledDefaultPath(candidate: String): Boolean {
        val path = runCatching { URI(candidate).path.orEmpty().lowercase() }
            .getOrDefault(candidate.substringBefore('?').lowercase())
        return path.contains("/web/assets/img/icon") ||
            path.contains("/web/assets/img/touchicon") ||
            path.contains("/web/assets/img/banner") ||
            path.contains("/web/assets/img/logo") ||
            path.contains("/web/assets/img/splashlogo") ||
            path.contains("/web/css/images/mblogo") ||
            path.contains("/web/css/images/logo") ||
            path.contains("/web/images/logo") ||
            path.contains("jellyfin-logo") ||
            path.endsWith("/favicon.png") ||
            path.endsWith("/favicon.ico") ||
            path.endsWith("/favicon.svg")
    }

    private fun looksLikeBundledDefaultSvg(candidate: String, svg: String): Boolean {
        if (!looksLikeBundledDefaultPath(candidate)) return false
        val normalized = svg.lowercase()
        val stockMarkers = listOf(
            "jellyfin-logo",
            "emby",
            "#00a4dc",
            "#52b54b",
            "#aa5cc3",
            "viewbox=\"0 0 512 512\"",
            "viewbox='0 0 512 512'"
        )
        return stockMarkers.any { marker -> normalized.contains(marker) }
    }

    private fun looksLikeBundledDefaultLogo(candidate: String, bitmap: Bitmap): Boolean {
        if (!looksLikeBundledDefaultPath(candidate)) return false
        var greenOrTeal = 0
        var blueOrPurple = 0
        var colorful = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                AndroidColor.colorToHSV(pixel, hsv)
                if (hsv[1] > 0.32f && hsv[2] > 0.22f) {
                    colorful++
                    val hue = hsv[0]
                    if (hue in 82f..178f) greenOrTeal++
                    if (hue in 178f..284f) blueOrPurple++
                }
                x += stepX
            }
            y += stepY
        }
        if (colorful < 8) return false
        val greenRatio = greenOrTeal.toFloat() / colorful.toFloat()
        val blueRatio = blueOrPurple.toFloat() / colorful.toFloat()
        return greenRatio > 0.72f || blueRatio > 0.82f
    }

    private suspend fun extractDominantLogoAccentArgb(
        imageUrl: String?,
        fallback: Int
    ): Int = withContext(Dispatchers.IO) {
        if (imageUrl.isNullOrBlank()) return@withContext fallback
        runCatching {
            val connection = URL(imageUrl).openConnection().apply {
                connectTimeout = 2_500
                readTimeout = 3_500
                useCaches = true
            }
            connection.getInputStream().use { stream ->
                val bitmap = BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                ) ?: return@withContext fallback

                var redSum = 0L
                var greenSum = 0L
                var blueSum = 0L
                var weightSum = 0L
                val stepX = (bitmap.width / 28).coerceAtLeast(1)
                val stepY = (bitmap.height / 28).coerceAtLeast(1)
                var y = 0
                while (y < bitmap.height) {
                    var x = 0
                    while (x < bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = AndroidColor.red(pixel)
                        val g = AndroidColor.green(pixel)
                        val b = AndroidColor.blue(pixel)
                        val maxChannel = maxOf(r, g, b)
                        val minChannel = minOf(r, g, b)
                        val brightness = (r + g + b) / 3
                        val saturation = maxChannel - minChannel
                        if (brightness in 32..236 && saturation > 14) {
                            val weight = (saturation + 24).coerceAtMost(180)
                            redSum += r.toLong() * weight
                            greenSum += g.toLong() * weight
                            blueSum += b.toLong() * weight
                            weightSum += weight.toLong()
                        }
                        x += stepX
                    }
                    y += stepY
                }
                bitmap.recycle()

                if (weightSum <= 0L) fallback else AndroidColor.rgb(
                    (redSum / weightSum).toInt().coerceIn(0, 255),
                    (greenSum / weightSum).toInt().coerceIn(0, 255),
                    (blueSum / weightSum).toInt().coerceIn(0, 255)
                )
            }
        }.getOrDefault(fallback)
    }
}
