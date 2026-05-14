package com.grmemby.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import com.grmemby.data.network.ServerType
import com.grmemby.data.network.canonicalServerUrlWithDefaultPort
import com.grmemby.data.network.trimTrailingSlash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

class ServerTransferRepository(private val context: Context) {
    enum class ExternalAppSource(
        val label: String,
        val packageName: String
    ) {
        YAMBY("Yamby", "com.hush.yamby"),
        CAPY_PLAYER("CapyPlayer", "com.feifeiduck.capyplayer"),
        HILLS("Hills", "com.mountains.hills")
    }

    data class TransferOptions(
        val playbackSettings: Boolean = true,
        val servers: Boolean = true,
        val remarks: Boolean = true,
        val credentials: Boolean = false
    )

    data class TransferResult(
        val importedServers: Int = 0,
        val importedSettingsGroups: Int = 0,
        val exportedServers: Int = 0,
        val message: String,
        val sCode: String? = null
    )

    data class TransferContentAvailability(
        val playbackSettings: Boolean = false,
        val servers: Boolean = false,
        val remarks: Boolean = false,
        val credentials: Boolean = false
    ) {
        fun defaultImportOptions(): TransferOptions = TransferOptions(
            playbackSettings = playbackSettings,
            servers = servers,
            remarks = servers && remarks,
            credentials = servers && credentials
        )

        val hasAny: Boolean
            get() = playbackSettings || servers
    }

    private val appContext = context.applicationContext
    private val authRepository = AuthRepositoryProvider.getInstance(appContext)

    private data class IconPackEntry(
        val name: String,
        val url: String
    )


    suspend fun importFromExternalApp(source: ExternalAppSource): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = when (source) {
                ExternalAppSource.CAPY_PLAYER -> readCapyPlayerServers()
                ExternalAppSource.HILLS -> readHillsServers()
                ExternalAppSource.YAMBY -> readYambyServers()
            }
            if (candidates.isEmpty()) {
                error("未从 ${source.label} 读取到可导入服务器。Android 无 root 时不能直接读取其他应用私有数据，请在 ${source.label} 导出/分享备份文件后用文件选择导入。")
            }
            importCandidates(source.label, candidates)
        }
    }

    suspend fun importFromExternalUri(source: ExternalAppSource, uri: Uri): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = readExternalUriServers(source, uri)
            if (candidates.isEmpty()) {
                error("未从所选 ${source.label} 文件读取到服务器。请确认选择的是 ${source.label} 导出的备份/数据库/配置文件，且文件中包含服务器地址、用户名或备注。")
            }
            importCandidates(source.label, candidates)
        }
    }

    private suspend fun importCandidates(
        sourceLabel: String,
        candidates: List<AuthRepository.ImportedServerCandidate>
    ): TransferResult {
        val count = authRepository.importSavedServerCandidates(withFastMatchedServerLogos(candidates))
        return TransferResult(
            importedServers = count,
            message = "已从 $sourceLabel 导入/合并 $count 个服务器；不会导入其他应用密码或 token，点击卡片后重新输入密码即可验权登录。"
        )
    }

    suspend fun exportToUri(uri: Uri, options: TransferOptions): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildTransferPayload(options)
            val json = encodeTransferPayload(payload)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开导出文件")
            TransferResult(
                exportedServers = payload.servers.size,
                importedSettingsGroups = payload.playbackSettings.size,
                message = "导出完成：服务器 ${payload.servers.size} 个，设置组 ${payload.playbackSettings.size} 个。"
            )
        }
    }

    suspend fun exportToSCode(options: TransferOptions): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildTransferPayload(options)
            val sCode = encodeSCode(encodeTransferPayload(payload))
            TransferResult(
                exportedServers = payload.servers.size,
                importedSettingsGroups = payload.playbackSettings.size,
                message = "S码已复制到剪贴板：服务器 ${payload.servers.size} 个，设置组 ${payload.playbackSettings.size} 个。请妥善保存，勾选账号密码时 S码会包含登录凭据。",
                sCode = sCode
            )
        }
    }

    suspend fun importFromUri(uri: Uri, options: TransferOptions): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            importPayload(readPayloadFromUri(uri), options)
        }
    }

    suspend fun inspectUri(uri: Uri): Result<TransferContentAvailability> = withContext(Dispatchers.IO) {
        runCatching {
            readPayloadFromUri(uri).toContentAvailability()
        }
    }

    suspend fun importFromSCode(sCode: String, options: TransferOptions): Result<TransferResult> = withContext(Dispatchers.IO) {
        runCatching {
            importPayload(decodeTransferText(sCode), options)
        }
    }

    suspend fun inspectSCode(sCode: String): Result<TransferContentAvailability> = withContext(Dispatchers.IO) {
        runCatching {
            decodeTransferText(sCode).toContentAvailability()
        }
    }

    private fun readPayloadFromUri(uri: Uri): GrmembyTransferPayload {
        val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法读取导入文件")
        return decodeTransferText(text)
    }

    private suspend fun buildTransferPayload(options: TransferOptions): GrmembyTransferPayload {
        return GrmembyTransferPayload(
            exportedAt = System.currentTimeMillis(),
            playbackSettings = if (options.playbackSettings) collectSharedPreferences() else emptyMap(),
            servers = if (options.servers) authRepository.exportSavedServerCandidates(
                includeRemarks = options.remarks,
                includeCredentials = options.credentials
            ) else emptyList()
        )
    }

    private fun encodeTransferPayload(payload: GrmembyTransferPayload): String {
        return com.grmemby.data.network.GrmembyJson.encodeToString(GrmembyTransferPayload.serializer(), payload)
    }

    private suspend fun importPayload(
        payload: GrmembyTransferPayload,
        options: TransferOptions
    ): TransferResult {
        val settingsCount = if (options.playbackSettings) restoreSharedPreferences(payload.playbackSettings) else 0
        val servers = if (options.servers) {
            payload.servers.map { server ->
                server.copy(
                    serverRemark = if (options.remarks) server.serverRemark else null,
                    accessToken = if (options.credentials) server.accessToken else null
                )
            }
        } else {
            emptyList()
        }
        val serverCount = if (servers.isNotEmpty()) authRepository.importSavedServerCandidates(withFastMatchedServerLogos(servers)) else 0
        return TransferResult(
            importedServers = serverCount,
            importedSettingsGroups = settingsCount,
            message = "导入完成：服务器 $serverCount 个，设置组 $settingsCount 个${if (options.credentials) "，已恢复可用登录凭据" else "；未导入账号凭据的服务器需重新输入密码"}。"
        )
    }

    private fun decodeTransferText(text: String): GrmembyTransferPayload {
        val trimmed = text.trim()
        if (trimmed.isBlank()) error("导入内容为空")
        val json = if (trimmed.startsWith("{")) trimmed else decodeSCode(trimmed)
        return com.grmemby.data.network.GrmembyJson.decodeFromString(GrmembyTransferPayload.serializer(), json)
    }

    private fun GrmembyTransferPayload.toContentAvailability(): TransferContentAvailability {
        return TransferContentAvailability(
            playbackSettings = playbackSettings.isNotEmpty(),
            servers = servers.isNotEmpty(),
            remarks = servers.any { !it.serverRemark.isNullOrBlank() },
            credentials = servers.any { !it.accessToken.isNullOrBlank() }
        )
    }

    private fun encodeSCode(json: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(json.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeSCode(raw: String): String {
        val normalized = normalizeSCode(raw)
        val bytes = runCatching { Base64.decode(normalized, Base64.DEFAULT) }
            .getOrElse { error("S码解析失败，请确认复制的是完整 S码。") }
        val decoded = runCatching {
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } else {
                bytes
            }
        }.getOrElse { error("S码解压失败，请确认复制的是完整 S码。") }
        return decoded.toString(Charsets.UTF_8).trim()
    }

    private fun normalizeSCode(raw: String): String {
        val fenced = Regex("```(?:\\w+)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.getOrNull(1)
        val text = (fenced ?: raw)
            .trim()
            .replace(Regex("^[sSＳｓ]\\s*码\\s*[:：]?\\s*"), "")
        return text.replace(Regex("\\s+"), "")
    }

    private fun readExternalUriServers(
        source: ExternalAppSource,
        uri: Uri
    ): List<AuthRepository.ImportedServerCandidate> {
        val temp = File.createTempFile("external-${source.name.lowercase()}", ".import", appContext.cacheDir)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法打开所选文件")
        return try {
            readExternalFileServers(source, temp, temp.name, depth = 0)
                .distinctBy { candidate ->
                    canonicalServerUrlWithDefaultPort(candidate.serverUrl).lowercase() to
                        candidate.username.lowercase() to
                        candidate.userId.lowercase()
                }
        } finally {
            temp.delete()
        }
    }

    private fun readExternalFileServers(
        source: ExternalAppSource,
        file: File,
        displayName: String,
        depth: Int
    ): List<AuthRepository.ImportedServerCandidate> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        if (depth <= 1 && isZipFile(file)) {
            return readZipServers(source, file, depth)
        }
        if (isSqliteFile(file)) {
            parseExternalDatabase(source, file).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        val text = runCatching { bytes.toString(Charsets.UTF_8) }
            .getOrDefault(bytes.toString(Charsets.ISO_8859_1))
        val jsonCandidates = parseGenericJsonServers(source, text)
        val urlCandidates = parseLooseTextServers(source, text, displayName)
        return (jsonCandidates + urlCandidates).distinctBy { candidate ->
            canonicalServerUrlWithDefaultPort(candidate.serverUrl).lowercase() to
                candidate.username.lowercase() to
                candidate.userId.lowercase()
        }
    }

    private fun readZipServers(
        source: ExternalAppSource,
        file: File,
        depth: Int
    ): List<AuthRepository.ImportedServerCandidate> {
        val result = mutableListOf<AuthRepository.ImportedServerCandidate>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val lowerName = entry.name.lowercase()
                if (!isImportCandidateFileName(lowerName)) continue
                val bytes = zip.readBytes()
                if (bytes.isEmpty() || bytes.size > MAX_EXTERNAL_IMPORT_BYTES) continue
                val nested = File.createTempFile("external-zip", ".item", appContext.cacheDir)
                try {
                    nested.writeBytes(bytes)
                    result += readExternalFileServers(source, nested, entry.name, depth + 1)
                } finally {
                    nested.delete()
                }
            }
        }
        return result
    }


    private fun withFastMatchedServerLogos(
        candidates: List<AuthRepository.ImportedServerCandidate>
    ): List<AuthRepository.ImportedServerCandidate> {
        if (candidates.isEmpty()) return candidates
        val iconPack = bundledIconPackEntries()
        if (iconPack.isEmpty()) return candidates
        return candidates.map { candidate ->
            val logoUrl = resolveIconPackLogoUrl(candidate, iconPack)
            if (!logoUrl.isNullOrBlank() && (candidate.serverLogoUrl.isNullOrBlank() || isEmosServer(candidate))) {
                candidate.copy(serverLogoUrl = logoUrl)
            } else {
                candidate
            }
        }
    }

    private fun isEmosServer(candidate: AuthRepository.ImportedServerCandidate): Boolean {
        val tokens = serverIconMatchTokens(candidate)
        return tokens.any { it == "emos" || it == "emospg" }
    }

    private fun bundledIconPackEntries(): List<IconPackEntry> {
        val text = runCatching {
            appContext.assets.open(SERVER_ICON_PACK_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return parseIconPackEntries(text)
    }

    private fun parseIconPackEntries(text: String): List<IconPackEntry> {
        val root = runCatching { com.grmemby.data.network.GrmembyJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val icons = root["icons"]?.jsonArray ?: return emptyList()
        return icons.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                IconPackEntry(name = name, url = url)
            } else {
                null
            }
        }
    }

    private fun resolveIconPackLogoUrl(
        candidate: AuthRepository.ImportedServerCandidate,
        iconPack: List<IconPackEntry>
    ): String? {
        val matchTokens = serverIconMatchTokens(candidate)
        if (matchTokens.isEmpty()) return null
        return iconPack
            .mapNotNull { entry ->
                val score = iconPackMatchScore(matchTokens, iconPackEntryTokens(entry))
                if (score > 0) entry to score else null
            }
            .sortedWith(
                compareByDescending<Pair<IconPackEntry, Int>> { it.second }
                    .thenBy { it.first.name.length }
                    .thenBy { it.first.name }
            )
            .firstOrNull()
            ?.first
            ?.url
    }

    private fun serverIconMatchTokens(candidate: AuthRepository.ImportedServerCandidate): Set<String> {
        val hostTokens = listOf(candidate.serverUrl)
            .flatMap { serverUrl ->
                runCatching {
                    URI(serverUrl).host.orEmpty()
                        .split('.', '-', '_')
                        .filter { it.length >= 2 && it !in setOf("com", "net", "org", "cn", "top", "xyz", "vip", "www") }
                }.getOrDefault(emptyList())
            }
        val lineTokens = candidate.serverLines.flatMap { line -> listOf(line.name, line.url) }
        val rawTokens = listOf(
            candidate.serverName,
            candidate.serverRemark.orEmpty(),
            candidate.username
        ) + hostTokens + lineTokens
        return rawTokens
            .flatMap { token ->
                val baseParts = token.split(' ', '-', '_', '.', '/', ':', '·', '｜', '|') + token
                baseParts.flatMap { part ->
                    val fixed = part.replace("nyamiedia", "nyamedia", ignoreCase = true)
                    val normalized = normalizeIconToken(fixed)
                    val stripped = stripServerNameDecorators(normalized)
                    val aliases = when (stripped) {
                        "emos" -> listOf("emospg")
                        else -> emptyList()
                    }
                    listOf(part, fixed, normalized, stripped) + aliases
                }
            }
            .map(::normalizeIconToken)
            .filter { it.length >= 2 && it !in ICON_IGNORED_WORDS }
            .toSet()
    }

    private fun iconPackEntryTokens(entry: IconPackEntry): Set<String> {
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
            .filter { it.length >= 2 && it !in ICON_IGNORED_WORDS }
            .toSet()
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
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
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

    private fun parseExternalDatabase(
        source: ExternalAppSource,
        file: File
    ): List<AuthRepository.ImportedServerCandidate> {
        return runCatching {
            openDatabase(file).use { db ->
                when {
                    tableExists(db, "media_server_resources") -> parseCapyPlayerDatabase(file)
                    tableExists(db, "emby_servers") -> parseHillsDatabase(file)
                    else -> emptyList()
                }
            }
        }.getOrDefault(emptyList()).map { candidate -> candidate.copy(source = source.label) }
    }

    private fun parseGenericJsonServers(
        source: ExternalAppSource,
        text: String
    ): List<AuthRepository.ImportedServerCandidate> {
        val element = runCatching { com.grmemby.data.network.GrmembyJson.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList()
        val result = mutableListOf<AuthRepository.ImportedServerCandidate>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    candidateFromJsonObject(source, node)?.let { result += it }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(element)
        return result.distinctBy { candidate ->
            canonicalServerUrlWithDefaultPort(candidate.serverUrl).lowercase() to
                candidate.username.lowercase() to
                candidate.userId.lowercase()
        }
    }

    private fun candidateFromJsonObject(
        source: ExternalAppSource,
        obj: JsonObject
    ): AuthRepository.ImportedServerCandidate? {
        val directUrl = obj.firstString(
            "serverUrl", "server_url", "embyServerUrl", "emby_server_url", "baseUrl", "base_url",
            "BaseUrl", "BaseURL", "url", "hostUrl", "host_url"
        )
        val builtUrl = directUrl ?: obj.string("host")?.let { host ->
            buildServerUrl(host, obj.int("port"), obj.string("path"), obj.boolean("https") ?: !host.startsWith("http://"))
        }
        val normalizedUrl = builtUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { canonicalServerUrlWithDefaultPort(it) }
            ?: return null
        val username = obj.firstString("username", "userName", "UserName", "newUsername", "nameOfUser").orEmpty()
        val serverName = obj.firstString("display_name", "displayName", "server_name", "serverName", "Name", "name", "title")
            ?: normalizedUrl
        val isJellyfin = obj.boolean("is_jellyfin") == true ||
            obj.boolean("isJellyfin") == true ||
            obj.firstString("type", "serverType", "server_type")?.contains("jelly", ignoreCase = true) == true
        val primaryLines = obj.firstString("server_routing_list", "serverRoutingList")
            ?.let { parseHillsRoutes(it, normalizedUrl, null) }
            .orEmpty()
        val backupLines = obj.firstString("backup_routes", "backupRoutes")
            ?.let { parseCapyBackupRoutes(it, normalizedUrl) }
            .orEmpty()
        val lines = (primaryLines + backupLines).distinctBy { canonicalServerUrlWithDefaultPort(it.url).lowercase() }
        return AuthRepository.ImportedServerCandidate(
            serverUrl = normalizedUrl,
            serverName = serverName,
            serverTypeRaw = if (isJellyfin) ServerType.JELLYFIN.name else ServerType.EMBY.name,
            username = username,
            userId = obj.firstString("user_id", "userId", "UserId").orEmpty().ifBlank { importedUserId(username) },
            serverLogoUrl = obj.firstString("server_logo_image_url", "serverLogoUrl", "icon_url", "iconUrl"),
            serverRemark = obj.firstString("remark", "remarks", "serverRemark", "note", "comment"),
            serverLines = lines,
            activeLineId = obj.firstString("selected_server_routing_id", "selectedServerRoutingId", "activeLineId")
                ?.let { id -> lines.firstOrNull { it.id == id }?.id },
            source = source.label
        )
    }

    private fun parseLooseTextServers(
        source: ExternalAppSource,
        text: String,
        displayName: String
    ): List<AuthRepository.ImportedServerCandidate> {
        val sourceName = source.label
        val fromYambyHints = if (source == ExternalAppSource.YAMBY) {
            extractYambyServerHints(text).mapIndexed { index, hint ->
                AuthRepository.ImportedServerCandidate(
                    serverUrl = hint.serverUrl,
                    serverName = hint.serverName.ifBlank { "$sourceName 导入 ${index + 1}" },
                    serverTypeRaw = ServerType.EMBY.name,
                    username = looseTextValue(text, "UserName", "Username", "userName", "username").orEmpty(),
                    userId = hint.userId.ifBlank { importedUserId(looseTextValue(text, "UserName", "Username", "userName", "username").orEmpty()) },
                    serverRemark = looseTextValue(text, "remark", "Remark", "remarks", "note"),
                    source = sourceName
                )
            }
        } else {
            emptyList()
        }
        val genericUrls = Regex("https?://[^\\s\\\"'<>]+")
            .findAll(text)
            .mapNotNull { match ->
                val raw = match.value.trimEnd(',', ';', ')', ']', '}')
                val normalized = runCatching { canonicalServerUrlWithDefaultPort(raw) }.getOrNull()
                    ?: return@mapNotNull null
                AuthRepository.ImportedServerCandidate(
                    serverUrl = normalized,
                    serverName = looseTextValue(text, "serverName", "server_name", "Name", "name") ?: displayName.substringBeforeLast('.'),
                    serverTypeRaw = ServerType.EMBY.name,
                    username = looseTextValue(text, "UserName", "Username", "userName", "username").orEmpty(),
                    userId = importedUserId(looseTextValue(text, "UserName", "Username", "userName", "username").orEmpty()),
                    serverRemark = looseTextValue(text, "remark", "Remark", "remarks", "note"),
                    source = sourceName
                )
            }
            .toList()
        return (fromYambyHints + genericUrls).distinctBy { candidate ->
            canonicalServerUrlWithDefaultPort(candidate.serverUrl).lowercase() to candidate.username.lowercase()
        }
    }

    private fun looseTextValue(text: String, vararg keys: String): String? {
        keys.forEach { key ->
            Regex("(?i)(?:\\\"${Regex.escape(key)}\\\"|${Regex.escape(key)})\\s*[:=]\\s*\\\"?([^\\\",;\\n\\r}]+)")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !looksSecret(key, it) }
                ?.let { return it }
        }
        return null
    }

    private fun isZipFile(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun isSqliteFile(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == 16 && header.toString(Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    }.getOrDefault(false)

    private fun isImportCandidateFileName(name: String): Boolean {
        return name.endsWith(".json") || name.endsWith(".db") || name.endsWith(".sqlite") ||
            name.endsWith(".txt") || name.endsWith(".xml") || name.contains("server") ||
            name.contains("emby") || name.contains("hills") || name.contains("capy") ||
            name.contains("yamby") || name.contains("backup") || name.contains("setting")
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        return db.rawQuery("select name from sqlite_master where type='table' and name=?", arrayOf(tableName)).use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun looksSecret(key: String, value: String): Boolean {
        val haystack = "$key $value".lowercase()
        return SECRET_WORDS.any { haystack.contains(it) }
    }

    private fun readCapyPlayerServers(): List<AuthRepository.ImportedServerCandidate> {
        val dbFile = rootCopyFile(
            source = "/data/data/${ExternalAppSource.CAPY_PLAYER.packageName}/databases/capyplayer.db",
            prefix = "capyplayer",
            suffix = ".db"
        ) ?: cachedExternalImportFile("capyplayer.db") ?: return emptyList()
        return parseCapyPlayerDatabase(dbFile)
    }

    private fun parseCapyPlayerDatabase(dbFile: File): List<AuthRepository.ImportedServerCandidate> {
        return openDatabase(dbFile).use { db ->
            val cursor = db.rawQuery(
                "select id,name,type,host,port,path,username,user_id,https,backup_routes,remark,server_logo_image_url from media_server_resources where ifnull(is_hidden,0)=0",
                null
            )
            cursor.use {
                buildList {
                    while (cursor.moveToNext()) {
                        val host = cursor.string("host")?.trim().orEmpty()
                        if (host.isBlank()) continue
                        val https = cursor.int("https") == 1
                        val port = cursor.int("port")?.takeIf { it > 0 }
                        val path = cursor.string("path")
                        val primaryUrl = buildServerUrl(host, port, path, https)
                        val name = cursor.string("name")?.takeIf { it.isNotBlank() } ?: host
                        val username = cursor.string("username").orEmpty()
                        val userId = cursor.string("user_id").orEmpty()
                        val typeRaw = if (cursor.string("type")?.contains("jelly", ignoreCase = true) == true) {
                            ServerType.JELLYFIN.name
                        } else {
                            ServerType.EMBY.name
                        }
                        val lines = parseCapyBackupRoutes(cursor.string("backup_routes"), primaryUrl)
                        add(
                            AuthRepository.ImportedServerCandidate(
                                serverUrl = primaryUrl,
                                serverName = name,
                                serverTypeRaw = typeRaw,
                                username = username,
                                userId = userId.ifBlank { importedUserId(username) },
                                serverLogoUrl = cursor.string("server_logo_image_url"),
                                serverRemark = cursor.string("remark"),
                                serverLines = lines,
                                activeLineId = lines.firstOrNull { it.name.contains("当前") || it.name.contains("默认") }?.id,
                                source = ExternalAppSource.CAPY_PLAYER.label
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readHillsServers(): List<AuthRepository.ImportedServerCandidate> {
        val dbFile = rootCopyFile(
            source = "/data/data/${ExternalAppSource.HILLS.packageName}/app_flutter/app_database.sqlite",
            prefix = "hills",
            suffix = ".sqlite"
        ) ?: cachedExternalImportFile("hills.sqlite") ?: return emptyList()
        return parseHillsDatabase(dbFile)
    }

    private fun parseHillsDatabase(dbFile: File): List<AuthRepository.ImportedServerCandidate> {
        return openDatabase(dbFile).use { db ->
            val cursor = db.rawQuery(
                "select id,display_name,server_name,server_url,authenticate_json,username,selected_server_routing_id,server_routing_list,is_jellyfin,remark,icon_url,main_routing_name from emby_servers order by order_index asc,id asc",
                null
            )
            cursor.use {
                buildList {
                    while (cursor.moveToNext()) {
                        val primaryUrl = cursor.string("server_url")?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        val username = cursor.string("username").orEmpty()
                        val authJson = cursor.string("authenticate_json")
                        val userId = parseHillsUserId(authJson).ifBlank { importedUserId(username) }
                        val name = cursor.string("display_name")?.takeIf { it.isNotBlank() }
                            ?: cursor.string("server_name")?.takeIf { it.isNotBlank() }
                            ?: primaryUrl
                        val lines = parseHillsRoutes(
                            raw = cursor.string("server_routing_list"),
                            primaryUrl = primaryUrl,
                            mainRoutingName = cursor.string("main_routing_name")
                        )
                        val selected = cursor.string("selected_server_routing_id")?.takeIf { it.isNotBlank() }
                        add(
                            AuthRepository.ImportedServerCandidate(
                                serverUrl = canonicalServerUrlWithDefaultPort(primaryUrl),
                                serverName = name,
                                serverTypeRaw = if (cursor.int("is_jellyfin") == 1) ServerType.JELLYFIN.name else ServerType.EMBY.name,
                                username = username,
                                userId = userId,
                                serverLogoUrl = cursor.string("icon_url"),
                                serverRemark = cursor.string("remark"),
                                serverLines = lines,
                                activeLineId = selected?.let { id -> lines.firstOrNull { it.id == id }?.id },
                                source = ExternalAppSource.HILLS.label
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readYambyServers(): List<AuthRepository.ImportedServerCandidate> {
        // Yamby stores the saved-server list in MMKV on current builds, and the MMKV payload
        // can be encrypted/obfuscated. Its OkHttp cache still contains server API URLs for the
        // user's saved/used Emby instances, so use that as a ROOT-only fallback. We only import
        // address + user id hints; credentials/tokens are intentionally discarded.
        val rootOutput = rootShell(
            "for f in /data/data/${ExternalAppSource.YAMBY.packageName}/files/mmkv/emby_setting " +
                "/data/data/${ExternalAppSource.YAMBY.packageName}/files/mmkv/user_setting " +
                "/data/data/${ExternalAppSource.YAMBY.packageName}/files/mmkv/prefs " +
                "/data/data/${ExternalAppSource.YAMBY.packageName}/shared_prefs/*.xml " +
                "/data/data/${ExternalAppSource.YAMBY.packageName}/cache/network_cache/*.0; do " +
                "[ -f \"\${'$'}f\" ] && grep -aEho 'https?://[^\\\" <>]+' \"\${'$'}f\" 2>/dev/null; done | head -1500"
        ).getOrNull().orEmpty()
        val output = rootOutput + "\n" + cachedYambyUrlText()
        val urls = extractYambyServerHints(output)
            .distinctBy { hint -> hint.serverUrl.lowercase() to hint.userId.lowercase() }
            .take(20)
        return urls.mapIndexed { index, hint ->
            AuthRepository.ImportedServerCandidate(
                serverUrl = hint.serverUrl,
                serverName = hint.serverName.ifBlank { "Yamby 导入 ${index + 1}" },
                serverTypeRaw = ServerType.EMBY.name,
                username = "",
                userId = hint.userId.ifBlank { "yamby:${hint.serverUrl.hashCode()}" },
                source = ExternalAppSource.YAMBY.label
            )
        }
    }

    private fun cachedYambyUrlText(): String {
        return listOf(
            File(File(appContext.filesDir, "external_import"), "yamby_network_cache"),
            File(File(appContext.cacheDir, "external_import"), "yamby_network_cache")
        ).asSequence()
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().asSequence() }
            .filter { file -> file.isFile && (file.name.endsWith(".0") || file.name.endsWith(".xml") || !file.name.endsWith(".crc")) }
            .take(2000)
            .mapNotNull { file -> runCatching { file.readBytes().toString(Charsets.ISO_8859_1) }.getOrNull() }
            .joinToString("\n")
    }

    private data class YambyServerHint(
        val serverUrl: String,
        val serverName: String,
        val userId: String
    )

    private fun extractYambyServerHints(text: String): List<YambyServerHint> {
        val rawUrlRegex = Regex("https?://[^\\s\"'<>]+")
        return rawUrlRegex.findAll(text)
            .flatMap { match -> nestedUrls(match.value).asSequence() }
            .mapNotNull { raw -> yambyHintFromUrl(raw) }
            .groupBy { it.serverUrl.lowercase() to it.userId.lowercase() }
            .values
            .map { group -> group.maxByOrNull { hint -> hint.userId.length } ?: group.first() }
    }

    private fun nestedUrls(raw: String): List<String> {
        val decoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
        val starts = Regex("https?://").findAll(decoded).map { it.range.first }.toList()
        return starts.map { start ->
            decoded.substring(start)
                .takeWhile { char -> !char.isWhitespace() && char !in listOf('"', '\'', '<', '>') }
                .trimEnd(',', ';', ')', ']', '}')
        }
    }

    private fun yambyHintFromUrl(raw: String): YambyServerHint? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (YAMBY_IGNORED_HOST_PARTS.any { host.contains(it) }) return null
        val segments = uri.pathSegments.orEmpty()
        val apiIndex = segments.indexOfFirst { segment -> segment.lowercase() in EMBY_API_SEGMENTS }
        if (apiIndex < 0) return null
        val baseSegments = if (apiIndex > 0 && segments[apiIndex - 1].equals("emby", ignoreCase = true)) {
            segments.take(apiIndex)
        } else {
            segments.take(apiIndex)
        }
        val basePath = baseSegments.joinToString(separator = "/", prefix = "/").takeIf { it != "/" }.orEmpty()
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        val baseUrl = canonicalServerUrlWithDefaultPort("${uri.scheme}://$host$port$basePath")
        val userId = segments.windowed(2)
            .firstOrNull { pair -> pair[0].equals("Users", ignoreCase = true) }
            ?.getOrNull(1)
            ?.takeIf { candidate -> candidate.length >= 12 && candidate.all { it.isLetterOrDigit() || it == '-' } }
            .orEmpty()
        val name = host.substringBefore(':')
        return YambyServerHint(serverUrl = baseUrl, serverName = name, userId = userId)
    }

    private fun parseCapyBackupRoutes(raw: String?, primaryUrl: String): List<AuthRepository.ServerLine> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val element = com.grmemby.data.network.GrmembyJson.parseToJsonElement(raw)
            element.jsonArray.mapIndexedNotNull { index, route ->
                val obj = route.jsonObject
                val host = obj.string("host") ?: return@mapIndexedNotNull null
                val https = obj.boolean("https") ?: true
                val port = obj.int("port")
                val path = obj.string("path")
                val url = buildServerUrl(host, port, path, https)
                if (url.equals(primaryUrl, ignoreCase = true)) return@mapIndexedNotNull null
                AuthRepository.ServerLine(
                    id = obj.string("id") ?: "capy-${index}-${url.hashCode()}",
                    name = obj.string("name") ?: "备用线路 ${index + 1}",
                    url = url,
                    isReverseProxy = obj.boolean("isProxy") ?: false
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseHillsRoutes(
        raw: String?,
        primaryUrl: String,
        mainRoutingName: String?
    ): List<AuthRepository.ServerLine> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val element = com.grmemby.data.network.GrmembyJson.parseToJsonElement(raw)
            element.jsonArray.mapIndexedNotNull { index, route ->
                val obj = route.jsonObject
                val url = obj.firstString("serverUrl", "server_url", "url", "routingUrl", "routing_url")
                    ?: obj.string("host")?.let { host -> buildServerUrl(host, obj.int("port"), obj.string("path"), obj.boolean("https") ?: true) }
                    ?: return@mapIndexedNotNull null
                val normalized = canonicalServerUrlWithDefaultPort(url)
                if (normalized.equals(canonicalServerUrlWithDefaultPort(primaryUrl), ignoreCase = true)) return@mapIndexedNotNull null
                AuthRepository.ServerLine(
                    id = obj.firstString("id", "routingId", "routing_id") ?: "hills-${index}-${normalized.hashCode()}",
                    name = obj.firstString("name", "displayName", "display_name") ?: mainRoutingName?.takeIf { it.isNotBlank() } ?: "备用线路 ${index + 1}",
                    url = normalized,
                    isReverseProxy = obj.boolean("isProxy") ?: obj.boolean("is_proxy") ?: false
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseHillsUserId(authJson: String?): String {
        if (authJson.isNullOrBlank()) return ""
        return runCatching {
            val obj = com.grmemby.data.network.GrmembyJson.parseToJsonElement(authJson).jsonObject
            obj.firstString("UserId", "userId")
                ?: obj["User"]?.jsonObject?.firstString("Id", "id")
                ?: ""
        }.getOrDefault("")
    }

    private fun collectSharedPreferences(): Map<String, Map<String, BackupPreferenceValue>> {
        return PLAYBACK_PREF_FILES.mapNotNull { name ->
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val values = prefs.all.mapValuesNotNull { (_, value) -> value.toBackupValue() }
            if (values.isEmpty()) null else name to values
        }.toMap()
    }

    private fun restoreSharedPreferences(groups: Map<String, Map<String, BackupPreferenceValue>>): Int {
        var restored = 0
        groups.forEach { (name, values) ->
            if (name !in PLAYBACK_PREF_FILES) return@forEach
            val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            values.forEach { (key, value) -> editor.putBackupValue(name, key, value) }
            if (editor.commit()) restored++
        }
        return restored
    }

    private fun rootCopyFile(source: String, prefix: String, suffix: String): File? {
        val target = File.createTempFile(prefix, suffix, appContext.cacheDir)
        val command = "cp ${shellQuote(source)} ${shellQuote(target.absolutePath)} && chmod 600 ${shellQuote(target.absolutePath)}"
        return if (rootShell(command).isSuccess && target.exists() && target.length() > 0L) target else null
    }

    private fun cachedExternalImportFile(name: String): File? {
        return listOf(
            File(File(appContext.filesDir, "external_import"), name),
            File(File(appContext.cacheDir, "external_import"), name)
        ).firstOrNull { it.exists() && it.length() > 0L && it.canRead() }
    }

    private fun rootShell(command: String): Result<String> = runCatching {
        // KernelSU/APatch often isolate app mount namespaces. Use the system su binary and
        // mount-master mode so ROOT reads the real /data/data/<other-app> files.
        val process = ProcessBuilder("/system/bin/su", "-M", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) error(output.ifBlank { "root command failed: $code" })
        output
    }

    private fun openDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    )

    private fun buildServerUrl(host: String, port: Int?, path: String?, https: Boolean): String {
        val scheme = if (https) "https" else "http"
        val cleanedHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val needsPort = port != null && port > 0 && !((https && port == 443) || (!https && port == 80)) && ':' !in cleanedHost.substringAfterLast('@')
        val base = "$scheme://$cleanedHost${if (needsPort) ":$port" else ""}"
        val cleanedPath = path?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
        return canonicalServerUrlWithDefaultPort(if (cleanedPath == null) base else "$base/$cleanedPath")
    }

    private fun importedUserId(username: String): String = "imported:${username.ifBlank { "user" }}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun android.database.Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }
    private fun android.database.Cursor.int(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { string(it) }

    private fun Map<String, Any?>.mapValuesNotNull(): Map<String, BackupPreferenceValue> = mapValuesNotNull { (_, value) ->
        value.toBackupValue()
    }

    private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
        val result = linkedMapOf<K, R>()
        for (entry in entries) {
            val value = transform(entry) ?: continue
            result[entry.key] = value
        }
        return result
    }

    private fun Any?.toBackupValue(): BackupPreferenceValue? = when (this) {
        is String -> BackupPreferenceValue.StringValue(this)
        is Boolean -> BackupPreferenceValue.BooleanValue(this)
        is Int -> BackupPreferenceValue.IntValue(this)
        is Long -> BackupPreferenceValue.LongValue(this)
        is Float -> BackupPreferenceValue.DoubleValue(toDouble())
        is Set<*> -> BackupPreferenceValue.StringSetValue(mapNotNull { it as? String }.toSet())
        else -> null
    }

    private fun SharedPreferences.Editor.putBackupValue(
        groupName: String,
        key: String,
        value: BackupPreferenceValue
    ): SharedPreferences.Editor {
        return when (value) {
            is BackupPreferenceValue.StringValue -> putString(key, value.value)
            is BackupPreferenceValue.BooleanValue -> putBoolean(key, value.value)
            is BackupPreferenceValue.IntValue -> putInt(key, value.value)
            is BackupPreferenceValue.LongValue -> {
                if (value.value in Int.MIN_VALUE..Int.MAX_VALUE && shouldRestoreLegacyLongAsInt(groupName, key)) {
                    putInt(key, value.value.toInt())
                } else {
                    putLong(key, value.value)
                }
            }
            is BackupPreferenceValue.DoubleValue -> putFloat(key, value.value.toFloat())
            is BackupPreferenceValue.StringSetValue -> putStringSet(key, value.value)
        }
    }

    private fun shouldRestoreLegacyLongAsInt(groupName: String, key: String): Boolean {
        return when (groupName) {
            "grmemby_network_prefs" -> key in NETWORK_INT_PREF_KEYS
            "grmemby_player_prefs" -> key in PLAYER_INT_PREF_KEYS ||
                key.startsWith("audio_stream_index_") ||
                key.startsWith("subtitle_stream_index_")
            "grmemby_splash_prefs" -> key in SPLASH_INT_PREF_KEYS
            else -> false
        }
    }

    companion object {
        private const val SERVER_ICON_PACK_ASSET = "server_icon_pack_tubiao.json"
        private val ICON_IGNORED_WORDS = setOf(
            "emby", "jellyfin", "server", "media", "tv", "cn", "com", "net", "org", "top", "xyz", "vip", "www",
            "icon", "yuan", "fileball", "raw", "github", "githubusercontent", "baiitang", "sakura", "main", "softlyx"
        )
        private const val MAX_EXTERNAL_IMPORT_BYTES = 16 * 1024 * 1024
        private val SECRET_WORDS = setOf(
            "token",
            "password",
            "passwd",
            "pwd",
            "secret",
            "apikey",
            "api_key",
            "accesskey",
            "credential",
            "auth"
        )
        private val PLAYBACK_PREF_FILES = setOf(
            "grmemby_app_prefs",
            "grmemby_player_prefs",
            "grmemby_network_prefs",
            "grmemby_download_prefs",
            "grmemby_splash_prefs",
            "seerr_connection_prefs"
        )
        private val NETWORK_INT_PREF_KEYS = setOf(
            "request_timeout_ms",
            "connection_timeout_ms",
            "socket_timeout_ms",
            "image_memory_cache_mb"
        )
        private val SPLASH_INT_PREF_KEYS = setOf(
            "last_version_code"
        )
        private val PLAYER_INT_PREF_KEYS = setOf(
            "long_press_speed_boost_rate",
            "player_cache_size_mb",
            "player_cache_time_seconds",
            "seek_backward_interval_seconds",
            "seek_forward_interval_seconds",
            "danmaku_line_count",
            "danmaku_speed_percent",
            "danmaku_opacity_percent",
            "danmaku_font_size_sp",
            "subtitle_text_opacity_percent",
            "subtitle_bottom_edge_percent",
            "subtitle_top_edge_percent"
        )
        private val EMBY_API_SEGMENTS = setOf(
            "system",
            "users",
            "items",
            "sessions",
            "videos",
            "audio",
            "shows",
            "movies",
            "playlists",
            "persons",
            "studios",
            "genres",
            "artists",
            "livetv",
            "sync",
            "quickconnect",
            "search",
            "branding",
            "displaypreferences",
            "userconfiguration",
            "plugins",
            "scheduledtasks",
            "library",
            "web",
            "socket"
        )
        private val YAMBY_IGNORED_HOST_PARTS = setOf(
            "google",
            "googleapis",
            "gstatic",
            "firebase",
            "crashlytics",
            "douban",
            "tmdb",
            "themoviedb",
            "trakt",
            "github",
            "jsdelivr",
            "unpkg",
            "bilibili",
            "qq.com",
            "apple.com"
        )
    }
}

@Serializable
data class GrmembyTransferPayload(
    @SerialName("version") val version: Int = 1,
    @SerialName("exportedAt") val exportedAt: Long,
    @SerialName("playbackSettings") val playbackSettings: Map<String, Map<String, BackupPreferenceValue>> = emptyMap(),
    @SerialName("servers") val servers: List<AuthRepository.ImportedServerCandidate> = emptyList()
)

@Serializable
sealed class BackupPreferenceValue {
    @Serializable
    @SerialName("string")
    data class StringValue(@SerialName("value") val value: String) : BackupPreferenceValue()

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(@SerialName("value") val value: Boolean) : BackupPreferenceValue()

    @Serializable
    @SerialName("int")
    data class IntValue(@SerialName("value") val value: Int) : BackupPreferenceValue()

    @Serializable
    @SerialName("long")
    data class LongValue(@SerialName("value") val value: Long) : BackupPreferenceValue()

    @Serializable
    @SerialName("double")
    data class DoubleValue(@SerialName("value") val value: Double) : BackupPreferenceValue()

    @Serializable
    @SerialName("stringSet")
    data class StringSetValue(@SerialName("value") val value: Set<String>) : BackupPreferenceValue()
}
