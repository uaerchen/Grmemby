package com.grmemby.shared.util.image

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.svg.SvgDecoder
import com.grmemby.shared.BuildConfig
import com.grmemby.data.datastore.DataStoreProvider
import com.grmemby.data.model.AuthHeaderDto
import com.grmemby.data.network.NetworkModule
import com.grmemby.data.network.ServerType
import com.grmemby.data.preferences.NetworkPreferences
import com.grmemby.data.security.AuthSessionIds
import com.grmemby.data.security.LEGACY_ACCESS_TOKEN_KEY
import com.grmemby.data.security.SecureSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object ImageLoaderConfig {
    private val SERVER_URL_KEY = stringPreferencesKey("server_url")
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val SERVER_TYPE_KEY = stringPreferencesKey("server_type")
    private val SAVED_SERVERS_KEY = stringPreferencesKey("saved_servers_v1")
    private val ACTIVE_SERVER_ID_KEY = stringPreferencesKey("active_server_id")
    private const val BYTES_PER_MB = 1024L * 1024L

    private val deviceId by lazy { UUID.randomUUID().toString() }
    private const val IMAGE_STORE_DIR = "media_store/image_cache"

    private data class SavedServerAuthSnapshot(
        val id: String,
        val serverUrl: String,
        val userId: String,
        val serverTypeRaw: String?
    )

    private fun parseSavedServerAuthSnapshots(raw: String?): List<SavedServerAuthSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val serverUrl = item.optString("serverUrl").takeIf { it.isNotBlank() } ?: continue
                    val userId = item.optString("userId").takeIf { it.isNotBlank() } ?: continue
                    add(
                        SavedServerAuthSnapshot(
                            id = id,
                            serverUrl = serverUrl,
                            userId = userId,
                            serverTypeRaw = item.optString("serverTypeRaw").takeIf { it.isNotBlank() }
                                ?: item.optString("serverType").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistentImageCacheDir(context: Context): File {
        val dir = File(context.filesDir, IMAGE_STORE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun DiskCacheSize(context: Context): Long {
        val availableBytes = context.filesDir.usableSpace
        val percent = when {
            availableBytes > 32L * 1024 * 1024 * 1024 -> 0.02
            availableBytes > 8L * 1024 * 1024 * 1024 -> 0.04
            else -> 0.05
        }

        val calculatedSize = (availableBytes * percent).toLong()
        val finalSize = max(50L * 1024 * 1024, min(500L * 1024 * 1024, calculatedSize))

        return finalSize
    }

    private fun configuredImageCacheBytes(context: Context): Long? {
        val configuredMb = NetworkPreferences(context).getImageMemoryCacheMb()
        if (configuredMb == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB) {
            return null
        }
        return configuredMb * BYTES_PER_MB
    }

    private fun getOptimalMemoryPercent(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMB = memoryInfo.totalMem
        val isLargeHeap = activityManager.memoryClass != activityManager.largeMemoryClass

        val basePercent = when {
            totalRamMB >= 8192 -> if (isLargeHeap) 0.15 else 0.12
            totalRamMB >= 4096 -> if (isLargeHeap) 0.12 else 0.10
            totalRamMB >= 2048 -> if (isLargeHeap) 0.10 else 0.08
            else -> if (isLargeHeap) 0.08 else 0.06
        }

        return max(0.08, min(0.30, basePercent))
    }

    private fun ImageMemoryCacheBytes(context: Context): Long? {
        val configuredMb = NetworkPreferences(context).getImageMemoryCacheMb()
        if (configuredMb == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB) {
            return null
        }
        return configuredMb * BYTES_PER_MB
    }

    private fun createAuthenticatedOkHttpClient(context: Context): OkHttpClient {
        val dataStore = DataStoreProvider.getDataStore(context)
        val networkPreferences = NetworkPreferences(context)
        val secureSessionStore = SecureSessionStore(context)
        val authHeaderLock = Any()
        var cachedAuthHeader: String? = null
        var cachedAuthHeaderAt = 0L
        // Image grids can issue dozens of requests on a cold cache. Avoid
        // serializing them through DataStore/token reads every second.
        val authHeaderTtlMs = 30_000L

        fun buildAuthHeader(): String {
            val now = System.currentTimeMillis()
            synchronized(authHeaderLock) {
                val existingHeader = cachedAuthHeader
                if (existingHeader != null && (now - cachedAuthHeaderAt) < authHeaderTtlMs) {
                    return existingHeader
                }

                val preferences = runBlocking {
                    runCatching { dataStore.data.first() }.getOrNull()
                }
                val savedServers = parseSavedServerAuthSnapshots(preferences?.get(SAVED_SERVERS_KEY))
                val activeServer = preferences?.get(ACTIVE_SERVER_ID_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { activeId -> savedServers.firstOrNull { it.id == activeId } }
                val serverUrl = activeServer?.serverUrl ?: preferences?.get(SERVER_URL_KEY)
                val userId = activeServer?.userId ?: preferences?.get(USER_ID_KEY)
                val accessToken = activeServer?.id
                    ?.let { secureSessionStore.getToken(it) }
                    ?: if (!serverUrl.isNullOrBlank() && !userId.isNullOrBlank()) {
                        secureSessionStore.getToken(AuthSessionIds.buildServerId(serverUrl, userId))
                    } else {
                        null
                    } ?: preferences?.get(LEGACY_ACCESS_TOKEN_KEY)
                val serverType = (activeServer?.serverTypeRaw ?: preferences?.get(SERVER_TYPE_KEY))?.let {
                    runCatching { ServerType.valueOf(it) }.getOrNull()
                }
                val header = AuthHeaderDto.fromServerType(
                    serverType = serverType,
                    deviceId = deviceId,
                    version = BuildConfig.CLIENT_VERSION,
                    accessToken = accessToken
                ).asHeaderValue()
                cachedAuthHeader = header
                cachedAuthHeaderAt = now
                return header
            }
        }

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val authHeader = buildAuthHeader()
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", authHeader)
                .addHeader("X-Emby-Authorization", authHeader)
                .addHeader("Accept", "image/*,*/*;q=0.8")
                .build()

            var response = chain.proceed(newRequest)
            var retryCount = 0

            while (!response.isSuccessful && response.code >= 500 && retryCount < 2) {
                response.close()
                retryCount++
                response = chain.proceed(newRequest)
            }

            response
        }

        val dispatcher = Dispatcher().apply {
            // Glide/Hills keeps image fetch parallelism bounded. Large values
            // make slow Emby servers queue too much network + decode work at
            // once and can make Compose feel globally frozen on first load.
            maxRequests = 32
            maxRequestsPerHost = 8
        }

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dns(NetworkModule.ipv4OnlyDns(networkPreferences::isBlockIpv6ConnectionsEnabled))
            .addInterceptor(authInterceptor)
            .build()
    }

    fun createOptimizedImageLoader(context: Context): ImageLoader {
        val networkPreferences = NetworkPreferences(context)
        val imageCachingEnabled = networkPreferences.isImageCachingEnabled()
        val configuredMemoryCacheBytes = ImageMemoryCacheBytes(context)

        val componentRegistry = ComponentRegistry.Builder()
            .add(SvgDecoder.Factory())
            .add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { createAuthenticatedOkHttpClient(context) }
                )
            )
            .build()

        val builder = ImageLoader.Builder(context)
            .components(componentRegistry)
            .memoryCache {
                val memoryCacheBuilder = MemoryCache.Builder()
                if (configuredMemoryCacheBytes != null) {
                    memoryCacheBuilder.maxSizeBytes(configuredMemoryCacheBytes)
                } else {
                    memoryCacheBuilder.maxSizePercent(context, getOptimalMemoryPercent(context))
                }
                memoryCacheBuilder.build()
            }

        builder.diskCache {
            DiskCache.Builder()
                .directory(persistentImageCacheDir(context).toOkioPath())
                .maxSizeBytes(configuredImageCacheBytes(context) ?: DiskCacheSize(context))
                .build()
        }

        if (imageCachingEnabled) {
            builder.memoryCachePolicy(CachePolicy.ENABLED)
            builder.diskCachePolicy(CachePolicy.ENABLED)
            builder.networkCachePolicy(CachePolicy.ENABLED)
        } else {
            builder.memoryCachePolicy(CachePolicy.DISABLED)
            builder.diskCachePolicy(CachePolicy.DISABLED)
            builder.networkCachePolicy(CachePolicy.DISABLED)
        }

        return builder.build()
    }

}
