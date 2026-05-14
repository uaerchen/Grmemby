package com.grmemby.app.update

import com.grmemby.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val UPDATE_CONFIG_URL = "https://aac-1322384155.cos.ap-nanjing.myqcloud.com/embyupdate.json"

private val UPDATE_HTTP_CLIENT = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()

@Serializable
data class AppUpdateInfo(
    val version: String = "",
    val versionCode: Int = 0,
    @SerialName("Url") val url: String = "",
    val changelog: String = ""
)

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult
    data class NoUpdate(val currentVersionName: String, val currentVersionCode: Int) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

object AppUpdateChecker {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }


    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(UPDATE_CONFIG_URL)
                .header("Cache-Control", "no-cache")
                .build()

            UPDATE_HTTP_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppUpdateCheckResult.Failed("HTTP ${response.code}")
                }

                val body = response.body.string()
                val info = json.decodeFromString<AppUpdateInfo>(body)
                if (info.versionCode <= 0) {
                    return@withContext AppUpdateCheckResult.Failed("Invalid versionCode")
                }

                val resolvedInfo = info.copy(
                    changelog = loadChangelogText(info.changelog)
                )

                if (isRemoteVersionNewer(
                        remoteVersionName = resolvedInfo.version,
                        remoteVersionCode = resolvedInfo.versionCode,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        currentVersionCode = BuildConfig.VERSION_CODE
                    )
                ) {
                    AppUpdateCheckResult.UpdateAvailable(resolvedInfo)
                } else {
                    AppUpdateCheckResult.NoUpdate(
                        currentVersionName = BuildConfig.VERSION_NAME,
                        currentVersionCode = BuildConfig.VERSION_CODE
                    )
                }
            }
        }.getOrElse { error ->
            AppUpdateCheckResult.Failed(error.readableMessage())
        }
    }

    fun updateConfigUrl(): String = UPDATE_CONFIG_URL
}

internal fun isRemoteVersionNewer(
    remoteVersionName: String,
    remoteVersionCode: Int,
    currentVersionName: String,
    currentVersionCode: Int
): Boolean {
    val remoteSemantic = remoteVersionName.toSemanticVersionParts()
    val currentSemantic = currentVersionName.toSemanticVersionParts()
    if (remoteSemantic != null && currentSemantic != null) {
        val semanticCompare = compareSemanticVersions(remoteSemantic, currentSemantic)
        if (semanticCompare != 0) return semanticCompare > 0
    }
    return remoteVersionCode > currentVersionCode
}

private fun String.toSemanticVersionParts(): List<Int>? {
    val versionText = Regex("""\d+(?:\.\d+)*""").find(this)?.value ?: return null
    return versionText.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
}

private fun compareSemanticVersions(left: List<Int>, right: List<Int>): Int {
    val maxSize = maxOf(left.size, right.size)
    repeat(maxSize) { index ->
        val leftPart = left.getOrElse(index) { 0 }
        val rightPart = right.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    return 0
}

private fun loadChangelogText(changelog: String): String {
    val trimmed = changelog.trim()
    if (trimmed.isBlank()) return ""
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return trimmed

    return runCatching {
        val request = Request.Builder()
            .url(trimmed)
            .header("Cache-Control", "no-cache")
            .build()
        UPDATE_HTTP_CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            response.body.string().trim()
        }
    }.getOrDefault("")
}

private fun Throwable.readableMessage(): String {
    return when (this) {
        is IOException -> message ?: "Network error"
        else -> message ?: this::class.java.simpleName
    }
}
