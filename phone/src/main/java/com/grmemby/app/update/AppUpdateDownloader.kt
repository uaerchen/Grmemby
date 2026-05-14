package com.grmemby.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface AppUpdateDownloadResult {
    data class Success(val file: File) : AppUpdateDownloadResult
    data class Failed(val message: String) : AppUpdateDownloadResult
}

data class AppUpdateDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L
) {
    val percent: Int = if (totalBytes > 0L) {
        ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
    } else {
        0
    }
}

object AppUpdateDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun findCachedApk(context: Context, updateInfo: AppUpdateInfo): File? {
        val file = cachedApkFile(context, updateInfo)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0L }
    }

    private fun cachedApkFile(context: Context, updateInfo: AppUpdateInfo): File {
        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir,
            "updates"
        ).apply { mkdirs() }
        return File(outputDir, "grmemby-${updateInfo.versionCode}.apk")
    }

    suspend fun downloadApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: suspend (AppUpdateDownloadProgress) -> Unit
    ): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        val url = updateInfo.url.trim()
        if (url.isBlank()) return@withContext AppUpdateDownloadResult.Failed("Empty update URL")
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https")) {
            return@withContext AppUpdateDownloadResult.Failed("Unsupported update URL")
        }

        val outputFile = cachedApkFile(context, updateInfo)
        val partialFile = File(outputFile.parentFile, "${outputFile.name}.download")

        findCachedApk(context, updateInfo)?.let { cachedFile ->
            return@withContext AppUpdateDownloadResult.Success(cachedFile)
        }

        runCatching {
            if (partialFile.exists()) partialFile.delete()
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppUpdateDownloadResult.Failed("HTTP ${response.code}")
                }

                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                withContext(Dispatchers.Main) {
                    onProgress(AppUpdateDownloadProgress(downloadedBytes, totalBytes))
                }

                body.byteStream().use { input ->
                    partialFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            withContext(Dispatchers.Main) {
                                onProgress(AppUpdateDownloadProgress(downloadedBytes, totalBytes))
                            }
                        }
                    }
                }

                if (totalBytes > 0L && downloadedBytes != totalBytes) {
                    throw IOException("Downloaded $downloadedBytes of $totalBytes bytes")
                }
            }

            if (outputFile.exists()) outputFile.delete()
            if (!partialFile.renameTo(outputFile)) {
                partialFile.copyTo(outputFile, overwrite = true)
                partialFile.delete()
            }
            AppUpdateDownloadResult.Success(outputFile)
        }.getOrElse { error ->
            partialFile.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            AppUpdateDownloadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
