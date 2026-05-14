package com.grmemby.app.ui.components.danmaku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

internal data class DanmakuComment(
    val timeMs: Long,
    val text: String,
    val mode: Int = 1,
    val color: Int = 0xFFFFFF
)

internal data class DanmakuLoadResult(
    val comments: List<DanmakuComment>,
    val sourceApiUrl: String? = null,
    val commentId: String? = null,
    val displayTitle: String? = null,
    val error: String? = null
)

internal data class DanmakuSearchRequest(
    /** Series/movie title used for manual search. For episodes this should be the series name, not the episode title. */
    val title: String,
    val fileName: String,
    val mediaId: String,
    val hash: String?,
    val matchMode: String,
    val seasonEpisodeLabel: String = ""
)

internal data class DanmakuSearchCandidate(
    val title: String,
    val apiUrl: String,
    val sourceLabel: String,
    val provider: String,
    val commentId: String?,
    val animeId: String?,
    val imageUrl: String?,
    val episodeCount: Int?,
    val type: String,
    val selectedEpisodeTitle: String?
)

internal object DanmakuEngine {
    private const val LOG_TAG = "GrmembyDanmaku"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(5000, TimeUnit.MILLISECONDS)
        .build()
    // Some self-hosted danmaku providers need 5s+ for the first anime/episode search.
    // Keep connect/write short, but do not kill the real playback auto-match before it can respond.
    private val quickClient = client.newBuilder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(10000, TimeUnit.MILLISECONDS)
        .writeTimeout(3000, TimeUnit.MILLISECONDS)
        .callTimeout(12000, TimeUnit.MILLISECONDS)
        .build()
    private val commentClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(6000, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(8000, TimeUnit.MILLISECONDS)
        .build()

    suspend fun load(
        apiUrls: List<String>,
        request: DanmakuSearchRequest,
        filterWords: String,
        mergeDuplicates: Boolean
    ): DanmakuLoadResult = withContext(Dispatchers.IO) {
        val normalizedUrls = apiUrls.map(::normalizeBaseUrl).filter { it.isNotBlank() }.distinct()
        if (normalizedUrls.isEmpty()) {
            return@withContext DanmakuLoadResult(emptyList(), error = "未配置弹幕 API")
        }

        val keywords = buildMatchKeywords(request)
        val errors = mutableListOf<String>()
        for (baseUrl in normalizedUrls) {
            runCatching {
                val matchBody = matchEpisode(baseUrl, request, keywords)
                val commentId = findCommentId(matchBody)
                val rawComments = when {
                    commentId != null -> getComments(baseUrl, commentId)
                    else -> parseComments(matchBody)
                }
                val comments = applyFilters(rawComments, filterWords, mergeDuplicates)
                if (comments.isNotEmpty()) {
                    return@withContext DanmakuLoadResult(
                        comments = comments,
                        sourceApiUrl = baseUrl,
                        commentId = commentId,
                        displayTitle = findDisplayTitle(matchBody)
                    )
                }
                errors += "未匹配到弹幕"
            }.onFailure { error ->
                errors += error.message ?: error::class.java.simpleName
            }
        }
        DanmakuLoadResult(emptyList(), error = errors.joinToString("；").ifBlank { "未加载到弹幕" })
    }

    suspend fun searchCandidates(
        apiUrls: List<String>,
        request: DanmakuSearchRequest
    ): List<DanmakuSearchCandidate> = withContext(Dispatchers.IO) {
        val normalizedUrls = apiUrls.map(::normalizeBaseUrl).filter { it.isNotBlank() }.distinct()
        if (normalizedUrls.isEmpty()) return@withContext emptyList()

        val keywords = buildSearchKeywords(request)
        val results = linkedMapOf<String, DanmakuSearchCandidate>()
        for (baseUrl in normalizedUrls) {
            val sourceLabel = hostLabel(baseUrl)
            for (keyword in keywords) {
                val animeRaw = firstQuickResponse(
                    buildSearchUrls(baseUrl, "anime", keyword)
                ).orEmpty()
                parseAnimeCandidates(animeRaw, baseUrl, sourceLabel, emptyMap()).forEach { candidate ->
                    val key = listOf(baseUrl, candidate.animeId, candidate.title).joinToString("|")
                    results.putIfAbsent(key, candidate)
                }
                if (results.isNotEmpty()) break
            }
        }
        results.values.toList()
    }

    suspend fun loadCandidate(
        candidate: DanmakuSearchCandidate,
        request: DanmakuSearchRequest,
        filterWords: String,
        mergeDuplicates: Boolean
    ): DanmakuLoadResult = withContext(Dispatchers.IO) {
        val preferredEpisodeNumber = inferEpisodeNumber(request)
        var commentId = candidate.commentId?.takeIf { it.isNotBlank() }
        if (commentId == null && !candidate.animeId.isNullOrBlank()) {
            val bangumi = executeOrNull(
                Request.Builder()
                    .url("${candidate.apiUrl}/api/v2/bangumi/${candidate.animeId}")
                    .header("Accept", "application/json")
                    .build()
            ).orEmpty()
            commentId = findEpisodeChoice(bangumi, preferredEpisodeNumber)?.commentId
                ?: findCommentId(bangumi)
        }
        if (commentId.isNullOrBlank()) {
            return@withContext DanmakuLoadResult(
                comments = emptyList(),
                sourceApiUrl = candidate.apiUrl,
                error = "未找到当前集的弹幕 ID"
            )
        }
        val comments = applyFilters(getComments(candidate.apiUrl, commentId), filterWords, mergeDuplicates)
        DanmakuLoadResult(
            comments = comments,
            sourceApiUrl = candidate.apiUrl,
            commentId = commentId,
            displayTitle = buildCandidateDisplayTitle(candidate),
            error = if (comments.isEmpty()) "未加载到弹幕" else null
        )
    }

    private fun buildCandidateDisplayTitle(candidate: DanmakuSearchCandidate): String {
        return listOf(candidate.title, candidate.selectedEpisodeTitle)
            .filter { !it.isNullOrBlank() }
            .joinToString(" - ") { it.orEmpty() }
            .ifBlank { candidate.title }
    }

    private fun findDisplayTitle(raw: String): String? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return null
        val animeTitle = findFirstString(root, listOf("animeTitle", "bangumiTitle", "seriesTitle"))
        val episodeTitle = findFirstString(root, listOf("episodeTitle", "episodeName"))
        return listOf(animeTitle, episodeTitle)
            .filter { !it.isNullOrBlank() }
            .joinToString(" - ") { it.orEmpty() }
            .ifBlank { null }
    }

    private fun buildMatchKeywords(request: DanmakuSearchRequest): List<String> {
        val names = listOf(
            request.fileName,
            listOf(request.title, request.seasonEpisodeLabel).filter { it.isNotBlank() }.joinToString(" "),
            request.title,
            request.mediaId
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return names.ifEmpty { listOf(request.mediaId) }
    }

    private fun buildSearchKeywords(request: DanmakuSearchRequest): List<String> {
        val seriesTitle = stripEpisodeDecorations(request.title)
        val fallbackFromFile = stripEpisodeDecorations(request.fileName)
        val names = listOf(seriesTitle, fallbackFromFile, request.mediaId)
            .flatMap { expandDanmakuSearchAliases(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return names.ifEmpty { listOf(request.mediaId) }
    }

    private fun stripEpisodeDecorations(raw: String): String {
        var value = raw.trim()
        if (value.isBlank()) return ""
        value = value.replace(Regex("""\s*[·・]\s*S\d{1,2}\s*[:：]\s*E\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
        value = value.replace(Regex("""\s+S\d{1,2}\s*[:：]\s*E\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
        value = value.replace(Regex("""\s*-\s*第?\d{1,3}[集话話].*$"""), "")
        return value.trim()
    }

    private fun expandDanmakuSearchAliases(raw: String): List<String> {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        val aliases = linkedSetOf(value)
        val compact = value.lowercase().replace(Regex("""[\s._\-()（）]+"""), "")
        if (compact.contains("shameless")) {
            val isUk = compact.contains("uk") || compact.contains("british") || value.contains("英版")
            aliases += if (isUk) "无耻之徒(英版)" else "无耻之徒(美版)"
            aliases += if (isUk) "无耻之徒 英版" else "无耻之徒 美版"
            aliases += "无耻之徒"
        }
        return aliases.toList()
    }

    private fun matchEpisode(baseUrl: String, request: DanmakuSearchRequest, keywords: List<String>): String {
        var firstNonBlankResponse = ""
        val searchKeywords = (buildSearchKeywords(request) + keywords)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Hills uses the search endpoints first. On the current API, /api/v2/match can return
        // a season-mismatched episode for filenames like S01E07, so prefer explicit episode search.
        for (keyword in searchKeywords) {
            val episodeSearch = firstQuickResponse(
                buildSearchUrls(baseUrl, "episodes", keyword)
            ).orEmpty()
            if (episodeSearch.isNotBlank() && firstNonBlankResponse.isBlank()) firstNonBlankResponse = episodeSearch
            val episodeChoice = findBestEpisodeChoice(episodeSearch, request)
            episodeChoice?.commentId?.let { commentId ->
                return JSONObject()
                    .put("commentId", commentId)
                    .put("animeTitle", episodeChoice.animeTitle.orEmpty())
                    .put("episodeTitle", episodeChoice.title.orEmpty())
                    .toString()
            }

            val anime = firstQuickResponse(
                buildSearchUrls(baseUrl, "anime", keyword)
            ).orEmpty()
            if (anime.isNotBlank() && firstNonBlankResponse.isBlank()) firstNonBlankResponse = anime

            val nestedAnimeChoice = findBestEpisodeChoice(anime, request)
            nestedAnimeChoice?.commentId?.let { commentId ->
                return JSONObject()
                    .put("commentId", commentId)
                    .put("animeTitle", nestedAnimeChoice.animeTitle.orEmpty())
                    .put("episodeTitle", nestedAnimeChoice.title.orEmpty())
                    .toString()
            }

            val bangumiChoice = findBestBangumiEpisodeChoice(baseUrl, anime, request)
            bangumiChoice?.commentId?.let { commentId ->
                return JSONObject()
                    .put("commentId", commentId)
                    .put("animeTitle", bangumiChoice.animeTitle.orEmpty())
                    .put("episodeTitle", bangumiChoice.title.orEmpty())
                    .toString()
            }
        }

        // Last fallback only: legacy Dandan/LogVar APIs may support hash/filename match, but this
        // should not override the Hills-compatible explicit search result above.
        for (keyword in keywords) {
            val fileName = keyword.ifBlank { request.title.ifBlank { request.mediaId } }
            val body = JSONObject().apply {
                put("fileName", fileName)
                put("filename", fileName)
                put("title", request.title.ifBlank { fileName })
                put("matchMode", request.matchMode)
                if (request.hash?.isNotBlank() == true) {
                    put("fileHash", request.hash)
                    put("hash", request.hash)
                }
            }.toString().toRequestBody(jsonMediaType)
            val post = Request.Builder()
                .url("$baseUrl/api/v2/match")
                .post(body)
                .header("Accept", "application/json")
                .build()
            val response = executeQuickOrNull(post).orEmpty()
            if (response.isNotBlank()) {
                if (firstNonBlankResponse.isBlank()) firstNonBlankResponse = response
                if (hasUsableMatchPayload(response)) return response
            }
        }
        return firstNonBlankResponse
    }

    private fun hasUsableMatchPayload(raw: String): Boolean {
        if (raw.isBlank()) return false
        return findCommentId(raw) != null || parseComments(raw).isNotEmpty()
    }

    private fun findBestBangumiEpisodeChoice(
        baseUrl: String,
        animeRaw: String,
        request: DanmakuSearchRequest
    ): EpisodeChoice? {
        if (animeRaw.isBlank()) return null
        val root = runCatching { JSONTokenerCompat.parse(animeRaw) }.getOrNull() ?: return null
        val animeArray = findFirstArray(root, listOf("animes", "anime", "bangumi", "results", "data"))
            ?: (root as? JSONArray)
            ?: return null
        val preferredSeason = inferSeasonNumber(request)
        val ranked = mutableListOf<Pair<JSONObject, Int>>()
        for (index in 0 until animeArray.length()) {
            val anime = animeArray.opt(index) as? JSONObject ?: continue
            val animeId = readString(anime, listOf("animeId", "bangumiId", "id"))
            if (animeId.isBlank()) continue
            val animeTitle = readString(anime, listOf("animeTitle", "title", "name", "bangumiTitle"))
            ranked += anime to (scoreAnimeTitleForRequest(animeTitle, request, preferredSeason) - index)
        }
        var best: EpisodeChoice? = null
        ranked.sortedByDescending { it.second }.take(8).forEach { (anime, titleScore) ->
            val animeId = readString(anime, listOf("animeId", "bangumiId", "id"))
            val animeTitle = readString(anime, listOf("animeTitle", "title", "name", "bangumiTitle"))
            val bangumi = executeQuickOrNull(
                Request.Builder()
                    .url("$baseUrl/api/v2/bangumi/$animeId")
                    .header("Accept", "application/json")
                    .build()
            ).orEmpty()
            val choice = findBestEpisodeChoice(bangumi, request) ?: return@forEach
            val scored = choice.copy(
                animeTitle = choice.animeTitle ?: animeTitle.ifBlank { null },
                score = choice.score + titleScore
            )
            if (best == null || scored.score > best.score) best = scored
        }
        return best
    }

    private fun scoreAnimeTitleForRequest(
        animeTitle: String?,
        request: DanmakuSearchRequest,
        preferredSeason: Int?
    ): Int {
        val normalizedAnimeTitle = normalizeTitleForMatch(animeTitle)
        val normalizedRequestTitle = normalizeTitleForMatch(stripEpisodeDecorations(request.title))
        val normalizedFileTitle = normalizeTitleForMatch(stripEpisodeDecorations(request.fileName))
        var score = 0
        listOf(normalizedRequestTitle, normalizedFileTitle)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { token ->
                if (normalizedAnimeTitle == token) score += 120
                else if (normalizedAnimeTitle.contains(token) || token.contains(normalizedAnimeTitle)) score += 70
            }
        val animeSeason = inferSeasonNumber(animeTitle)
        if (preferredSeason != null) {
            score += when (animeSeason) {
                preferredSeason -> 60
                null -> 0
                else -> -45
            }
        }
        if (request.title.contains("美版") && animeTitle.orEmpty().contains("美版")) score += 20
        if (request.title.contains("英版") && animeTitle.orEmpty().contains("英版")) score += 20
        return score
    }

    private data class EpisodeChoice(
        val commentId: String?,
        val title: String?,
        val animeTitle: String? = null,
        val score: Int = 0
    )

    private fun parseAnimeCandidates(
        raw: String,
        baseUrl: String,
        sourceLabel: String,
        episodeLookup: Map<String, EpisodeChoice>
    ): List<DanmakuSearchCandidate> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return emptyList()
        val animeArray = findFirstArray(root, listOf("animes", "anime", "bangumi", "results", "data"))
            ?: (root as? JSONArray)
            ?: return emptyList()
        val candidates = mutableListOf<DanmakuSearchCandidate>()
        for (index in 0 until animeArray.length()) {
            val obj = animeArray.opt(index) as? JSONObject ?: continue
            val animeId = readString(obj, listOf("animeId", "bangumiId", "id"))
            val title = readString(obj, listOf("animeTitle", "title", "name", "bangumiTitle")).trim()
            if (title.isBlank()) continue
            val provider = readString(obj, listOf("source", "provider", "from")).ifBlank { "弹幕源" }
            val choice = episodeLookup[animeId]
            candidates += DanmakuSearchCandidate(
                title = title,
                apiUrl = baseUrl,
                sourceLabel = sourceLabel,
                provider = provider,
                commentId = choice?.commentId,
                animeId = animeId.ifBlank { null },
                imageUrl = readString(obj, listOf("imageUrl", "poster", "posterUrl", "cover", "coverUrl")).ifBlank { null },
                episodeCount = readInt(obj, listOf("episodeCount", "episodesCount", "episode_count")),
                type = readString(obj, listOf("typeDescription", "type", "category")).ifBlank { "电视剧" },
                selectedEpisodeTitle = choice?.title
            )
        }
        return candidates
    }

    private fun parseEpisodeLookup(raw: String, preferredEpisodeNumber: Int?): Map<String, EpisodeChoice> {
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return emptyMap()
        val animeArray = findFirstArray(root, listOf("animes", "anime", "bangumi", "results", "data"))
            ?: (root as? JSONArray)
            ?: return emptyMap()
        val result = mutableMapOf<String, EpisodeChoice>()
        for (index in 0 until animeArray.length()) {
            val anime = animeArray.opt(index) as? JSONObject ?: continue
            val animeId = readString(anime, listOf("animeId", "bangumiId", "id"))
            if (animeId.isBlank()) continue
            result[animeId] = findEpisodeChoice(anime.toString(), preferredEpisodeNumber) ?: continue
        }
        return result
    }

    private fun findBestEpisodeChoice(raw: String, request: DanmakuSearchRequest): EpisodeChoice? {
        if (raw.isBlank()) return null
        val preferredEpisodeNumber = inferEpisodeNumber(request)
        val preferredSeasonNumber = inferSeasonNumber(request)
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return null
        val animeArray = findFirstArray(root, listOf("animes", "anime", "bangumi", "results", "data"))
        if (animeArray == null) {
            return findEpisodeChoice(raw, preferredEpisodeNumber)
        }

        var best: EpisodeChoice? = null
        for (index in 0 until animeArray.length()) {
            val anime = animeArray.opt(index) as? JSONObject ?: continue
            val animeTitle = readString(anime, listOf("animeTitle", "title", "name", "bangumiTitle"))
            val episodeChoice = findEpisodeChoice(anime.toString(), preferredEpisodeNumber) ?: continue
            val animeSeason = inferSeasonNumber(animeTitle)
            var score = episodeChoice.score
            if (preferredEpisodeNumber != null && inferEpisodeNumber(episodeChoice.title) == preferredEpisodeNumber) {
                score += 40
            }
            if (preferredSeasonNumber != null) {
                score += when (animeSeason) {
                    preferredSeasonNumber -> 100
                    null -> 0
                    else -> -60
                }
            }
            val normalizedRequestTitle = normalizeTitleForMatch(request.title)
            val normalizedAnimeTitle = normalizeTitleForMatch(animeTitle)
            if (normalizedRequestTitle.isNotBlank() && normalizedAnimeTitle.contains(normalizedRequestTitle)) {
                score += 20
            }
            if (request.title.contains("美版") && animeTitle.contains("美版")) score += 15
            if (request.title.contains("英版") && animeTitle.contains("英版")) score += 15
            val candidate = episodeChoice.copy(animeTitle = animeTitle.ifBlank { null }, score = score)
            if (best == null || candidate.score > best.score) best = candidate
        }
        return best
    }

    private fun findEpisodeChoice(raw: String, preferredEpisodeNumber: Int?): EpisodeChoice? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return null
        var fallback: EpisodeChoice? = null
        var matched: EpisodeChoice? = null
        fun visit(node: Any?) {
            if (matched != null) return
            when (node) {
                is JSONObject -> {
                    val id = readString(node, listOf("episodeId", "commentId", "cid", "danmakuId", "danmuId"))
                    if (id.isNotBlank()) {
                        val title = readString(node, listOf("episodeTitle", "title", "name"))
                        val explicitNo = readInt(node, listOf("episodeNumber", "episodeNo", "episode", "index", "sort"))
                        val inferredNo = explicitNo ?: inferEpisodeNumber(title)
                        val choice = EpisodeChoice(id, title.ifBlank { null })
                        if (fallback == null) fallback = choice
                        if (preferredEpisodeNumber != null && inferredNo == preferredEpisodeNumber) {
                            matched = choice
                            return
                        }
                    }
                    node.keys().forEach { key -> visit(node.opt(key)) }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
            }
        }
        visit(root)
        return matched ?: fallback
    }

    private fun findFirstArray(node: Any?, keys: List<String>): JSONArray? {
        when (node) {
            is JSONObject -> {
                keys.forEach { key ->
                    val value = node.opt(key)
                    if (value is JSONArray) return value
                }
                node.keys().forEach { key -> findFirstArray(node.opt(key), keys)?.let { return it } }
            }
            is JSONArray -> {
                if (node.length() > 0) return node
                for (index in 0 until node.length()) {
                    findFirstArray(node.opt(index), keys)?.let { return it }
                }
            }
        }
        return null
    }

    private fun inferEpisodeNumber(request: DanmakuSearchRequest): Int? {
        listOf(request.seasonEpisodeLabel, request.fileName, request.title).forEach { raw ->
            inferEpisodeNumber(raw)?.let { return it }
        }
        return null
    }

    private fun inferSeasonNumber(request: DanmakuSearchRequest): Int? {
        listOf(request.seasonEpisodeLabel, request.fileName, request.title).forEach { raw ->
            inferSeasonNumber(raw)?.let { return it }
        }
        return null
    }

    private fun inferSeasonNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val patterns = listOf(
            Regex("(?i)s(\\d{1,2})"),
            Regex("(?i)season\\s*(\\d{1,2})"),
            Regex("第\\s*([一二三四五六七八九十百两俩0-9]{1,4})\\s*季")
        )
        patterns.forEach { regex ->
            regex.find(raw)?.groupValues?.getOrNull(1)?.let { token ->
                parseSeasonOrdinal(token)?.let { season ->
                    if (season in 1..50) return season
                }
            }
        }
        return null
    }

    private fun parseSeasonOrdinal(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0,
            '〇' to 0,
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '俩' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9
        )
        if (token == "十") return 10
        if (token.startsWith("十")) {
            val ones = token.getOrNull(1)?.let { digits[it] } ?: 0
            return 10 + ones
        }
        val tenIndex = token.indexOf('十')
        if (tenIndex >= 0) {
            val tens = if (tenIndex == 0) 1 else digits[token[tenIndex - 1]] ?: return null
            val ones = token.getOrNull(tenIndex + 1)?.let { digits[it] } ?: 0
            return tens * 10 + ones
        }
        val single = token.singleOrNull() ?: return null
        return digits[single]
    }

    private fun normalizeTitleForMatch(raw: String?): String = raw
        .orEmpty()
        .replace(Regex("""\s*[·・]\s*S\d{1,2}\s*[:：]\s*E\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+S\d{1,2}\s*[:：]\s*E\d{1,3}.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""第\s*[一二三四五六七八九十百两俩0-9]{1,4}\s*季.*$"""), "")
        .replace(Regex("""[\s\p{Punct}【】（）()]+"""), "")
        .lowercase()

    private fun inferEpisodeNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val patterns = listOf(
            Regex("(?i)(?:s\\d{1,2}\\s*)?e(\\d{1,3})"),
            Regex("第\\s*(\\d{1,3})\\s*[集话話]"),
            Regex("[._\\-\\s](\\d{1,3})(?:[._\\-\\s]|$)")
        )
        patterns.forEach { regex ->
            regex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { episode ->
                if (episode in 1..200) return episode
            }
        }
        Regex("第\\s*([一二三四五六七八九十百两俩0-9]{1,4})\\s*[集话話]")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { token -> parseSeasonOrdinal(token)?.takeIf { it in 1..200 } }
            ?.let { return it }
        return null
    }

    private fun hostLabel(baseUrl: String): String {
        return runCatching { URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl
    }

    private fun getComments(baseUrl: String, commentId: String): List<DanmakuComment> {
        val urls = listOf(
            "$baseUrl/api/v2/comment/$commentId?format=xml",
            "$baseUrl/api/v2/comment/$commentId?format=json&segmentflag=false",
            "$baseUrl/api/v2/comment/$commentId?format=json",
            "$baseUrl/api/v2/comment/$commentId"
        ).distinct()
        for (url in urls) {
            val text = executeCommentOrNull(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/xml, application/xml, */*")
                    .build()
            ).orEmpty()
            val comments = parseComments(text)
            if (comments.isNotEmpty()) return comments
        }
        return emptyList()
    }

    private fun buildSearchUrls(baseUrl: String, endpoint: String, keyword: String): List<String> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val parameters = when (endpoint) {
            "episodes" -> listOf("anime")
            else -> listOf("keyword")
        }
        return parameters.map { param -> "$baseUrl/api/v2/search/$endpoint?$param=$encoded" }.distinct()
    }

    private fun firstQuickResponse(urls: List<String>): String? {
        for (url in urls) {
            val response = executeQuickOrNull(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
            )
            if (!response.isNullOrBlank()) return response
        }
        return null
    }

    private fun execute(request: Request): String? {
        val startedAt = System.nanoTime()
        return client.newCall(request).execute().use { response ->
            logNetworkResult(request, response.code, startedAt)
            if (!response.isSuccessful) return null
            response.body.string()
        }
    }

    private fun executeOrNull(request: Request): String? {
        return runCatching { execute(request) }
            .onFailure { logNetworkFailure(request, it) }
            .getOrNull()
    }

    private fun executeQuickOrNull(request: Request): String? {
        val startedAt = System.nanoTime()
        return runCatching {
            quickClient.newCall(request).execute().use { response ->
                logNetworkResult(request, response.code, startedAt)
                if (response.isSuccessful) response.body.string() else null
            }
        }.onFailure { logNetworkFailure(request, it) }.getOrNull()
    }

    private fun executeCommentOrNull(request: Request): String? {
        val startedAt = System.nanoTime()
        return runCatching {
            commentClient.newCall(request).execute().use { response ->
                logNetworkResult(request, response.code, startedAt)
                if (response.isSuccessful) response.body.string() else null
            }
        }.onFailure { logNetworkFailure(request, it) }.getOrNull()
    }

    private fun logNetworkResult(request: Request, code: Int, startedAt: Long) {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        Log.d(LOG_TAG, "${request.method} ${request.url.encodedPath} -> $code in ${elapsedMs}ms")
    }

    private fun logNetworkFailure(request: Request, error: Throwable) {
        Log.w(LOG_TAG, "${request.method} ${request.url.encodedPath} failed: ${error.message ?: error::class.java.simpleName}")
    }

    private fun findCommentId(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching { JSONTokenerCompat.parse(raw) }.getOrNull()?.let(::findBestCommentId)
    }

    private fun findAnimeId(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching { JSONTokenerCompat.parse(raw) }.getOrNull()?.let { root ->
            findFirstString(root, listOf("animeId", "anime_id", "id"))
        }
    }

    private fun findBestCommentId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                listOf("commentId", "episodeId", "cid", "danmakuId", "danmuId").forEach { key ->
                    if (node.has(key)) {
                        val value = node.opt(key)
                        val stringValue = value?.toString().orEmpty()
                        if (stringValue.isNotBlank() && stringValue != "0") return stringValue
                    }
                }
                node.keys().forEach { key ->
                    findBestCommentId(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                findBestCommentId(node.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findFirstString(node: Any?, keys: List<String>): String? {
        when (node) {
            is JSONObject -> {
                keys.forEach { key ->
                    val value = node.opt(key)?.toString().orEmpty()
                    if (value.isNotBlank() && value != "0") return value
                }
                node.keys().forEach { key -> findFirstString(node.opt(key), keys)?.let { return it } }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                findFirstString(node.opt(index), keys)?.let { return it }
            }
        }
        return null
    }

    private fun parseComments(raw: String): List<DanmakuComment> {
        if (raw.isBlank()) return emptyList()
        if (raw.trimStart().startsWith("<")) return parseXmlComments(raw)
        val root = runCatching { JSONTokenerCompat.parse(raw) }.getOrNull() ?: return emptyList()
        val array = findBestCommentArray(root) ?: return emptyList()
        val comments = ArrayList<DanmakuComment>(array.length())
        for (index in 0 until array.length()) {
            parseJsonComment(array.opt(index), index)?.let(comments::add)
        }
        return comments.sortedBy { it.timeMs }
    }

    private fun findBestCommentArray(node: Any?): JSONArray? {
        var best: JSONArray? = null
        var bestScore = 0
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> {
                    val score = scoreCommentArray(value)
                    if (score > bestScore) {
                        bestScore = score
                        best = value
                    }
                    for (i in 0 until value.length()) visit(value.opt(i))
                }
                is JSONObject -> value.keys().forEach { visit(value.opt(it)) }
            }
        }
        visit(node)
        return best?.takeIf { bestScore > 0 }
    }

    private fun scoreCommentArray(array: JSONArray): Int {
        var score = 0
        val limit = minOf(array.length(), 12)
        for (index in 0 until limit) {
            val item = array.opt(index)
            when (item) {
                is JSONObject -> {
                    if (readString(item, textKeys).isNotBlank()) score += 4
                    if (readDouble(item, timeKeys) != null || item.optString("p").isNotBlank()) score += 3
                }
                is JSONArray -> {
                    if (item.length() >= 2 && item.opt(0) is Number) score += 3
                    if ((0 until item.length()).any { idx -> item.opt(idx) is String && item.optString(idx).isNotBlank() }) score += 3
                }
            }
        }
        return score
    }

    private fun parseJsonComment(node: Any?, fallbackId: Int): DanmakuComment? {
        if (node is JSONArray) {
            val seconds = node.optDouble(0, 0.0)
            val mode = node.optInt(1, 1)
            val color = node.optInt(2, 0xFFFFFF)
            val text = (0 until node.length())
                .mapNotNull { index -> node.opt(index)?.toString() }
                .lastOrNull { it.isNotBlank() && it != "null" }
                ?.trim()
                .orEmpty()
            if (text.isBlank()) return null
            return DanmakuComment(
                timeMs = (seconds * 1000.0).roundToLong().coerceAtLeast(0L),
                text = text,
                mode = mode,
                color = color
            )
        }
        val obj = node as? JSONObject ?: return null
        val pParts = obj.optString("p").split(',').map { it.trim() }.filter { it.isNotBlank() }
        val text = readString(obj, textKeys).ifBlank { obj.optString("m") }.trim()
        if (text.isBlank()) return null
        val seconds = readDouble(obj, timeKeys) ?: pParts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val mode = readInt(obj, modeKeys) ?: pParts.getOrNull(1)?.toIntOrNull() ?: 1
        val color = readColor(obj, pParts) ?: 0xFFFFFF
        return DanmakuComment(
            timeMs = (seconds * 1000.0).roundToLong().coerceAtLeast(0L),
            text = text,
            mode = mode,
            color = color
        )
    }

    private fun parseXmlComments(raw: String): List<DanmakuComment> {
        val regex = Regex("<d[^>]*?p=\\\"([^\\\"]*)\\\"[^>]*>(.*?)</d>", RegexOption.IGNORE_CASE)
        return regex.findAll(raw).mapNotNull { match ->
            val parts = match.groupValues[1].split(',')
            val text = match.groupValues[2]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim()
            if (text.isBlank()) return@mapNotNull null
            DanmakuComment(
                timeMs = ((parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L),
                text = text,
                mode = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                color = parts.getOrNull(3)?.toIntOrNull() ?: 0xFFFFFF
            )
        }.sortedBy { it.timeMs }.toList()
    }

    private fun applyFilters(
        comments: List<DanmakuComment>,
        filterWords: String,
        mergeDuplicates: Boolean
    ): List<DanmakuComment> {
        val filters = filterWords.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val seen = HashSet<String>()
        return comments.filter { item ->
            filters.none { item.text.contains(it, ignoreCase = true) }
        }.filter { item ->
            if (!mergeDuplicates) true else seen.add("${item.timeMs / 1000}:${item.text}")
        }.take(8000)
    }

    private fun readString(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            val str = value.toString()
            if (str.isNotBlank() && str != "null") return str
        }
        return ""
    }

    private fun readDouble(obj: JSONObject, keys: List<String>): Double? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = obj.opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun readInt(obj: JSONObject, keys: List<String>): Int? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = obj.opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun readColor(obj: JSONObject, pParts: List<String>): Int? {
        readInt(obj, colorKeys)?.let { return it }
        return pParts.getOrNull(2)?.toIntOrNull() ?: pParts.getOrNull(3)?.toIntOrNull()
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    private val textKeys = listOf("m", "text", "content", "body", "message", "msg", "danmu")
    private val timeKeys = listOf("time", "progress", "seconds", "position", "playTime", "timeline", "offset")
    private val modeKeys = listOf("mode", "type", "ct", "positionType")
    private val colorKeys = listOf("color", "colorValue", "rgb")
}

private object JSONTokenerCompat {
    fun parse(raw: String): Any {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed)
        }
    }
}
