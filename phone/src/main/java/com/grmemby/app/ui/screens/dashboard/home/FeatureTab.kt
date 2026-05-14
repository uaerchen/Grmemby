package com.grmemby.app.ui.screens.dashboard.home

import android.os.SystemClock
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import java.net.URL
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.*
import coil3.size.Precision
import com.grmemby.app.R
import com.grmemby.app.ui.components.common.ScreenCastButton
import com.grmemby.app.ui.components.glass.GlassDropdownMenu
import com.grmemby.app.ui.components.glass.GlassDropdownMenuItem
import com.grmemby.app.ui.screens.auth.ProfileImageLoader
import com.grmemby.app.ui.screens.dashboard.DashboardPalette
import com.grmemby.shared.util.image.imageTagFor
import com.grmemby.app.watchparty.WatchPartySessionStore
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.app.watchparty.ActiveWatchPartySession
import com.grmemby.app.watchparty.WatchPartyDeviceIdentity
import com.grmemby.app.watchparty.copyableWatchPartyInviteText
import com.grmemby.app.watchparty.sanitizeWatchPartyErrorMessage
import com.grmemby.app.watchparty.sameServerJoinFailureMessage
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.PersistedHomeSnapshot
import com.grmemby.data.repository.AuthRepository
import com.grmemby.data.repository.AuthRepository.ActiveSessionSnapshot
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.MediaRepositoryProvider
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal object CachedData {
    var featuredItems: List<BaseItemDto> = emptyList()
    var lastLoadTime: Long = 0
    private var _isCurrentlyLoading: Boolean = false

    val isCurrentlyLoading: Boolean get() = _isCurrentlyLoading

    fun shouldRefresh(): Boolean {
        return featuredItems.isEmpty() || System.currentTimeMillis() - lastLoadTime > 300_000
    }

    fun updateFeaturedItems(items: List<BaseItemDto>) {
        featuredItems = items
        lastLoadTime = System.currentTimeMillis()
        _isCurrentlyLoading = false
    }

    fun clearAllCache() {
        featuredItems = emptyList()
        lastLoadTime = 0
        _isCurrentlyLoading = false
    }

    fun markAsLoading(loading: Boolean) {
        _isCurrentlyLoading = loading
    }
}

private data class FeatureCardImages(
    val lowBackdropUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val surfaceColor: Color? = null,
    val versionKey: String? = null
)

private val HillsHomeSurface = Color(0xFF2C3650)
private val HillsHeroBlend = Color(0xFF20283C)
private val HillsHeroWarmSurface = Color(0xFF4A1F1C)
private val HillsHeroBlueSurface = Color(0xFF2C3650)

private fun Color.hillsDarker(factor: Float = 0.72f): Color {
    return Color(
        red = red * factor,
        green = green * factor,
        blue = blue * factor,
        alpha = alpha
    )
}

private fun positiveModulo(value: Int, divisor: Int): Int {
    return if (divisor <= 0) 0 else ((value % divisor) + divisor) % divisor
}

private fun hillsSurfaceForHeroItem(item: BaseItemDto): Color {
    val text = buildString {
        append(item.name.orEmpty())
        append(' ')
        append(item.originalTitle.orEmpty())
        append(' ')
        append(item.genres.orEmpty().joinToString(" "))
        append(' ')
        append(item.collectionType.orEmpty())
    }.lowercase()

    return when {
        listOf("奇幻", "科幻", "fantasy", "sci-fi", "science fiction", "xianxia").any { it in text } -> HillsHeroBlueSurface
        listOf("动作", "冒险", "action", "adventure", "crime", "thriller").any { it in text } -> HillsHeroWarmSurface
        listOf("动画", "anime", "animation").any { it in text } -> Color(0xFF293956)
        listOf("爱情", "romance", "drama", "剧情").any { it in text } -> Color(0xFF32314F)
        listOf("纪录", "documentary").any { it in text } -> Color(0xFF263C3A)
        else -> {
            val palette = listOf(
                Color(0xFF4A1F1C),
                Color(0xFF2C3650),
                Color(0xFF273F46),
                Color(0xFF392B4E),
                Color(0xFF3E3425),
                Color(0xFF24364A)
            )
            val key = item.id ?: item.name ?: item.premiereDate ?: "hills"
            palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]
        }
    }
}

private fun extractHillsSurfaceColorFromImageUrl(imageUrl: String?): Color? {
    if (imageUrl.isNullOrBlank()) return null
    return runCatching {
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
            ) ?: return@runCatching null

            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            var weightSum = 0L
            val stepX = (bitmap.width / 42).coerceAtLeast(1)
            val stepY = (bitmap.height / 26).coerceAtLeast(1)
            var y = bitmap.height / 5
            val yEnd = (bitmap.height * 4) / 5
            while (y < yEnd) {
                var x = bitmap.width / 8
                val xEnd = (bitmap.width * 7) / 8
                while (x < xEnd) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = AndroidColor.red(pixel)
                    val g = AndroidColor.green(pixel)
                    val b = AndroidColor.blue(pixel)
                    val maxChannel = maxOf(r, g, b)
                    val minChannel = minOf(r, g, b)
                    val brightness = (r + g + b) / 3
                    val saturation = maxChannel - minChannel
                    if (brightness in 28..214 && saturation > 10) {
                        val weight = (saturation + 18).coerceAtMost(128)
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

            if (weightSum <= 0L) return@runCatching null
            val red = (redSum / weightSum).toInt()
            val green = (greenSum / weightSum).toInt()
            val blue = (blueSum / weightSum).toInt()
            Color(
                red = (red / 255f * 0.58f).coerceIn(0.07f, 0.36f),
                green = (green / 255f * 0.58f).coerceIn(0.07f, 0.36f),
                blue = (blue / 255f * 0.62f).coerceIn(0.08f, 0.42f),
                alpha = 1f
            )
        }
    }.getOrNull()
}

private fun FeatureCardImages?.isHeroReady(): Boolean {
    return !this?.backdropUrl.isNullOrBlank() || !this?.lowBackdropUrl.isNullOrBlank()
}

@Composable
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalFoundationApi::class)
fun FeatureTab(
    modifier: Modifier = Modifier,
    featuredItems: List<BaseItemDto> = emptyList(),
    isLoading: Boolean = true,
    error: String? = null,
    selectedCategory: String = HomeCategory.HOME,
    verticalParallaxOffsetPx: Float = 0f,
    onItemClick: (BaseItemDto) -> Unit = {},
    onLogout: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onCastButtonClick: () -> Unit = {},
    onServerClick: () -> Unit = {},
    onServerSelected: (AuthRepository.SavedServer) -> Unit = {},
    isServerSwitching: Boolean = false,
    onCategorySelected: (String) -> Unit = {},
    onWatchPartyRoomReady: () -> Unit = {},
    onHeroSurfaceColorChange: (Color) -> Unit = {},
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    val authRepository = remember { AuthRepositoryProvider.getInstance(context) }
    val watchPartyRepository = remember { WatchPartyRepository() }
    var isCreatingWatchPartyRoom by remember { mutableStateOf(false) }
    var isJoiningWatchPartyRoom by remember { mutableStateOf(false) }
    var isLeavingWatchPartyRoom by remember { mutableStateOf(false) }
    var showJoinRoomDialog by remember { mutableStateOf(false) }
    var joinRoomId by remember { mutableStateOf("") }
    var lastJoinRoomClickAt by rememberSaveable { mutableStateOf(0L) }
    val activeWatchPartySession by WatchPartySessionStore.activeSession.collectAsState()
    val userFallback = stringResource(R.string.settings_unknown_user)
    var persistedHomeSnapshot by remember {
        mutableStateOf<PersistedHomeSnapshot?>(mediaRepository.getPersistedHomeSnapshot())
    }
    val sessionSnapshot by authRepository.observeActiveSession().collectAsState(
        initial = ActiveSessionSnapshot(
            serverName = null,
            serverUrl = null,
            serverType = null,
            username = null,
            savedServers = emptyList(),
            activeServerId = null
        )
    )
    val currentUsername = sessionSnapshot.username ?: persistedHomeSnapshot?.username
    val currentServerUrl = sessionSnapshot.serverUrl ?: persistedHomeSnapshot?.serverUrl
    var displayUsername by rememberSaveable(currentUsername, currentServerUrl) {
        mutableStateOf(currentUsername ?: persistedHomeSnapshot?.username ?: userFallback)
    }
    var userProfileImageUrl by rememberSaveable(currentUsername, currentServerUrl) {
        mutableStateOf<String?>(persistedHomeSnapshot?.profileImageUrl)
    }

    val featuredRowState = remember(selectedCategory) { LazyListState() }
    val featuredFlingBehavior = rememberSnapFlingBehavior(lazyListState = featuredRowState)
    val imageCacheByItemId = remember { mutableStateMapOf<String, FeatureCardImages>() }
    var stableFeaturedItems by remember(selectedCategory) { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    val metadataQualifiedFeaturedItems = remember(featuredItems) {
        derivedStateOf {
            featuredItems.filter(::hasFeatureHeroAssets)
        }
    }
    val displayFeaturedItems = remember(metadataQualifiedFeaturedItems.value, imageCacheByItemId) {
        derivedStateOf {
            metadataQualifiedFeaturedItems.value.filter { item ->
                val itemId = item.id ?: return@filter false
                val cachedImages = imageCacheByItemId[itemId] ?: return@filter false
                !cachedImages.backdropUrl.isNullOrBlank() ||
                    !cachedImages.lowBackdropUrl.isNullOrBlank()
            }
        }
    }
    val CurrentAssetsReady = remember(metadataQualifiedFeaturedItems.value, imageCacheByItemId) {
        metadataQualifiedFeaturedItems.value.isNotEmpty() &&
            metadataQualifiedFeaturedItems.value.all { candidate ->
                imageCacheByItemId[candidate.id.orEmpty()].isHeroReady()
            }
    }
    val resolvedFeaturedItems = remember(
        metadataQualifiedFeaturedItems.value,
        displayFeaturedItems.value,
        stableFeaturedItems,
        CurrentAssetsReady
    ) {
        derivedStateOf {
            val targetItems = metadataQualifiedFeaturedItems.value
            if (targetItems.isEmpty()) return@derivedStateOf stableFeaturedItems

            val fallbackItems = if (stableFeaturedItems.isNotEmpty()) stableFeaturedItems else targetItems
            if (CurrentAssetsReady || fallbackItems.isEmpty()) return@derivedStateOf targetItems

            buildList {
                targetItems.forEachIndexed { index, targetItem ->
                    val fallbackAtIndex = fallbackItems.getOrNull(index)
                    val isTargetReady = imageCacheByItemId[targetItem.id.orEmpty()].isHeroReady()

                    when {
                        index < 2 && fallbackAtIndex != null -> add(fallbackAtIndex)
                        isTargetReady -> add(targetItem)
                        fallbackAtIndex != null -> add(fallbackAtIndex)
                        else -> add(targetItem)
                    }
                }
            }.distinctBy { it.id ?: it.name.orEmpty() }
        }
    }

    LaunchedEffect(CurrentAssetsReady, metadataQualifiedFeaturedItems.value) {
        if (CurrentAssetsReady && metadataQualifiedFeaturedItems.value.isNotEmpty()) {
            stableFeaturedItems = metadataQualifiedFeaturedItems.value
        } else if (stableFeaturedItems.isEmpty() && metadataQualifiedFeaturedItems.value.isNotEmpty()) {
            stableFeaturedItems = metadataQualifiedFeaturedItems.value
        }
    }

    val featuredKeys = remember(resolvedFeaturedItems.value) {
        resolvedFeaturedItems.value.mapIndexed { index, item -> item.id ?: item.name ?: index.toString() }
    }
    val isResolvingFeatureAssets = remember(
        isLoading,
        featuredItems,
        metadataQualifiedFeaturedItems.value,
        resolvedFeaturedItems.value
    ) {
        !isLoading &&
            featuredItems.isNotEmpty() &&
            metadataQualifiedFeaturedItems.value.isNotEmpty() &&
            resolvedFeaturedItems.value.isEmpty()
    }
    val infiniteStartIndex = remember(featuredKeys) {
        if (featuredKeys.isEmpty()) 0 else (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % featuredKeys.size)
    }
    var autoScroll by rememberSaveable(selectedCategory) { mutableStateOf(false) }
    var hasSeededCarousel by rememberSaveable(selectedCategory) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val heroHeight = (configuration.screenHeightDp.dp * 0.58f).coerceIn(390.dp, 430.dp)
    val activeHeroIndex by remember(featuredRowState, resolvedFeaturedItems.value) {
        derivedStateOf {
            val count = resolvedFeaturedItems.value.size
            if (count == 0) 0 else positiveModulo(featuredRowState.firstVisibleItemIndex, count)
        }
    }
    val activeHeroSurfaceTarget by remember(resolvedFeaturedItems.value, activeHeroIndex, imageCacheByItemId) {
        derivedStateOf {
            val item = resolvedFeaturedItems.value.getOrNull(activeHeroIndex)
            val itemId = item?.id
            itemId?.let { imageCacheByItemId[it]?.surfaceColor }
                ?: item?.let(::hillsSurfaceForHeroItem)
                ?: HillsHomeSurface
        }
    }
    val activeHeroSurface by animateColorAsState(
        targetValue = activeHeroSurfaceTarget,
        animationSpec = tween(durationMillis = 450),
        label = "hills_home_surface"
    )

    LaunchedEffect(activeHeroSurfaceTarget) {
        onHeroSurfaceColorChange(activeHeroSurfaceTarget)
    }

    LaunchedEffect(currentServerUrl, currentUsername) {
        persistedHomeSnapshot = mediaRepository.loadPersistedHomeSnapshot()
    }

    LaunchedEffect(currentUsername, currentServerUrl, refreshTrigger) {
        val activeUsername = currentUsername ?: persistedHomeSnapshot?.username
        displayUsername = activeUsername?.takeIf { it.isNotBlank() } ?: "用户"

        val persistedProfileUrl = persistedHomeSnapshot?.profileImageUrl
        if (!persistedProfileUrl.isNullOrBlank()) {
            userProfileImageUrl = persistedProfileUrl
        }

        val user = withContext(Dispatchers.IO) {
            try {
                mediaRepository.getCurrentUser().getOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        currentCoroutineContext().ensureActive()
        val profileUrl = withContext(Dispatchers.IO) {
            try {
                mediaRepository.getUserProfileImageUrl(user?.primaryImageTag)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        currentCoroutineContext().ensureActive()
        userProfileImageUrl = profileUrl
        authRepository.updateActiveServerProfileImage(
            profileImageUrl = profileUrl ?: persistedProfileUrl
        )
    }

    LaunchedEffect(featuredItems, isLoading) {
        CachedData.markAsLoading(isLoading)
        if (featuredItems.isNotEmpty()) {
            CachedData.updateFeaturedItems(featuredItems)
        }
    }

    LaunchedEffect(featuredKeys, isLoading, selectedCategory) {
        if (isLoading || resolvedFeaturedItems.value.size <= 1 || hasSeededCarousel) return@LaunchedEffect
        runCatching { featuredRowState.scrollToItem(infiniteStartIndex) }
        hasSeededCarousel = true
    }

    LaunchedEffect(metadataQualifiedFeaturedItems.value) {
        if (metadataQualifiedFeaturedItems.value.isEmpty()) return@LaunchedEffect

        val imageLoader = context.imageLoader
        coroutineScope {
            metadataQualifiedFeaturedItems.value.forEach { item ->
                val itemId = item.id ?: return@forEach
                val versionKey = listOfNotNull(
                    item.imageTagFor(imageType = "Backdrop", targetItemId = itemId),
                    item.imageTagFor(imageType = "Logo", targetItemId = itemId)
                ).distinct().takeIf { it.isNotEmpty() }?.joinToString("|")
                val cachedImages = imageCacheByItemId[itemId]
                if (cachedImages != null && cachedImages.versionKey == versionKey) return@forEach

                launch(Dispatchers.IO) {
                    val backdropTag = item.imageTagFor(
                        imageType = "Backdrop",
                        targetItemId = itemId
                    )
                    val logoTag = item.imageTagFor(
                        imageType = "Logo",
                        targetItemId = itemId
                    )
                    val lowBackdropUrl = mediaRepository.getImageUrlString(
                        itemId = itemId,
                        imageType = "Backdrop",
                        width = 1280,
                        height = 720,
                        quality = 88,
                        imageTag = backdropTag
                    )
                    val paletteBackdropUrl = mediaRepository.getImageUrlString(
                        itemId = itemId,
                        imageType = "Backdrop",
                        width = 160,
                        height = 90,
                        quality = 68,
                        imageTag = backdropTag
                    )
                    val surfaceColor = extractHillsSurfaceColorFromImageUrl(paletteBackdropUrl)
                        ?: hillsSurfaceForHeroItem(item)
                    val backdropUrl = mediaRepository.getImageUrlString(
                        itemId = itemId,
                        imageType = "Backdrop",
                        width = 1920,
                        height = 1080,
                        quality = 96,
                        imageTag = backdropTag
                    )
                    val logoUrl = mediaRepository.getImageUrlString(
                        itemId = itemId,
                        imageType = "Logo",
                        width = 720,
                        height = 320,
                        quality = 90,
                        imageTag = logoTag
                    )

                    withContext(Dispatchers.Main) {
                        imageCacheByItemId[itemId] = FeatureCardImages(
                            lowBackdropUrl = lowBackdropUrl,
                            backdropUrl = backdropUrl,
                            logoUrl = logoUrl,
                            surfaceColor = surfaceColor,
                            versionKey = versionKey
                        )
                    }

                    if (!lowBackdropUrl.isNullOrBlank()) {
                        imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(lowBackdropUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .allowHardware(true)
                                .allowRgb565(false)
                                .build()
                        )
                    }
                    if (!backdropUrl.isNullOrBlank()) {
                        imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(backdropUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .allowHardware(true)
                                .allowRgb565(false)
                                .build()
                        )
                    }

                    if (!logoUrl.isNullOrBlank()) {
                        imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(logoUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .crossfade(false)
                                .allowHardware(true)
                                .allowRgb565(true)
                                .build()
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(featuredKeys) {
        if (featuredKeys.isEmpty()) {
            autoScroll = false
            hasSeededCarousel = false
        }
    }

    LaunchedEffect(featuredKeys, isLoading, resolvedFeaturedItems.value.size) {
        if (autoScroll || isLoading) return@LaunchedEffect
        if (resolvedFeaturedItems.value.isNotEmpty()) {
            autoScroll = true
        }
    }

    LaunchedEffect(featuredKeys, isLoading, autoScroll) {
        if (isLoading || resolvedFeaturedItems.value.size <= 1 || !autoScroll) return@LaunchedEffect
        while (true) {
            delay(10_000L)
            val nextIndex = featuredRowState.firstVisibleItemIndex + 1
            runCatching {
                featuredRowState.animateScrollToItem(index = nextIndex)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(activeHeroSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + 22.dp)
        ) {
            when {
                resolvedFeaturedItems.value.isNotEmpty() -> {
                    LazyRow(
                        state = featuredRowState,
                        modifier = Modifier
                            .fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        flingBehavior = featuredFlingBehavior
                    ) {
                        items(
                            count = Int.MAX_VALUE,
                            key = { index ->
                                val item = resolvedFeaturedItems.value[index % resolvedFeaturedItems.value.size]
                                item.id ?: item.name ?: index.toString()
                            }
                        ) { index ->
                            val item = resolvedFeaturedItems.value[index % resolvedFeaturedItems.value.size]
                            val cachedImages = item.id?.let { imageCacheByItemId[it] }
                            FeatureHeroCard(
                                item = item,
                                itemIndex = index,
                                listState = featuredRowState,
                                verticalParallaxOffsetPx = verticalParallaxOffsetPx,
                                images = cachedImages,
                                onClick = { onItemClick(item) },
                                heroHeight = heroHeight,
                                surfaceColor = activeHeroSurface,
                                modifier = Modifier.fillParentMaxWidth()
                            )
                        }
                    }
                }

                isLoading || isResolvingFeatureAssets -> FeatureHeroSkeleton(heroHeight = heroHeight)

                !error.isNullOrBlank() -> FeatureHeroError(error = error, heroHeight = heroHeight)

                else -> FeatureHeroError(error = "暂无推荐内容", heroHeight = heroHeight)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WatchPartyActionMenu(
                        activeSession = activeWatchPartySession,
                        isLoading = isCreatingWatchPartyRoom || isJoiningWatchPartyRoom || isLeavingWatchPartyRoom,
                        onCreateRoom = {
                            if (isCreatingWatchPartyRoom) return@WatchPartyActionMenu
                            val existingSession = WatchPartySessionStore.get()
                            if (existingSession != null) {
                                val inviteText = copyableWatchPartyInviteText(
                                    roomId = existingSession.roomId,
                                    inviteText = existingSession.inviteText
                                )
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                    ClipData.newPlainText("Grmemby 一起看", inviteText)
                                )
                                Toast.makeText(
                                    context,
                                    "已有房间：${existingSession.roomId}，已复制房间号",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@WatchPartyActionMenu
                            }
                            val hostName = displayUsername.takeIf { it.isNotBlank() } ?: "房主"
                            isCreatingWatchPartyRoom = true
                            scope.launch {
                                runCatching {
                                    watchPartyRepository.createRoom(
                                        name = "Grmemby 一起看",
                                        hostName = hostName,
                                        serverUrl = sessionSnapshot.serverUrl,
                                        serverName = sessionSnapshot.serverName,
                                        memberId = WatchPartyDeviceIdentity.memberId(context)
                                    )
                                }.onSuccess { created ->
                                    val inviteText = copyableWatchPartyInviteText(created.room.id)
                                    val session = ActiveWatchPartySession(
                                        roomId = created.room.id,
                                        memberId = created.memberId,
                                        isHost = true,
                                        roomName = created.room.name,
                                        inviteText = inviteText
                                    )
                                    WatchPartySessionStore.set(session)
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                        ClipData.newPlainText("Grmemby 一起看", inviteText)
                                    )
                                    Toast.makeText(
                                        context,
                                        "房间已创建并复制：${created.room.id}，请选择影片开始同播",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        sanitizeWatchPartyErrorMessage(error.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                isCreatingWatchPartyRoom = false
                            }
                        },
                        onJoinRoom = { showJoinRoomDialog = true },
                        onLeaveRoom = {
                            val leavingSession = WatchPartySessionStore.get() ?: return@WatchPartyActionMenu
                            isLeavingWatchPartyRoom = true
                            scope.launch {
                                runCatching {
                                    if (leavingSession.isHost) {
                                        watchPartyRepository.disbandRoom(leavingSession.roomId, leavingSession.memberId)
                                    } else {
                                        watchPartyRepository.leaveRoom(leavingSession.roomId, leavingSession.memberId)
                                    }
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        sanitizeWatchPartyErrorMessage(error.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                WatchPartySessionStore.clear(leavingSession.roomId)
                                isLeavingWatchPartyRoom = false
                                Toast.makeText(context, "已退出房间", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                GlassServerSwitchDropdown(
                    serverName = sessionSnapshot.serverName,
                    savedServers = sessionSnapshot.savedServers,
                    activeServerId = sessionSnapshot.activeServerId,
                    enabled = !isServerSwitching,
                    onServerSelected = onServerSelected,
                    onEmptyClick = onServerClick
                )
            }

            val indicatorCount = minOf(resolvedFeaturedItems.value.size, 5)
            if (indicatorCount > 1) {
                FeaturePageIndicator(
                    pageCount = indicatorCount,
                    currentPage = positiveModulo(activeHeroIndex, indicatorCount),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
    }

    if (showJoinRoomDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isJoiningWatchPartyRoom) showJoinRoomDialog = false
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 0.dp,
            title = { Text("加入房间", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "输入或粘贴房间 ID，加入后进入等待页；房主开始播放后会自动跟随。",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = joinRoomId,
                        onValueChange = { joinRoomId = it.trim() },
                        singleLine = true,
                        label = { Text("房间 ID") },
                        enabled = !isJoiningWatchPartyRoom,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF111827),
                            unfocusedTextColor = Color(0xFF111827),
                            disabledTextColor = Color(0xFF94A3B8),
                            focusedLabelColor = Color(0xFF258BFF),
                            unfocusedLabelColor = Color(0xFF64748B),
                            disabledLabelColor = Color(0xFF94A3B8),
                            focusedBorderColor = Color(0xFF258BFF),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            disabledBorderColor = Color(0xFFE2E8F0),
                            cursorColor = Color(0xFF258BFF)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = joinRoomId.isNotBlank() && !isJoiningWatchPartyRoom,
                    onClick = {
                        val cleanRoomId = joinRoomId.trim()
                        if (isJoiningWatchPartyRoom) return@TextButton
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastJoinRoomClickAt < 1_500L) return@TextButton
                        lastJoinRoomClickAt = now
                        val memberName = displayUsername.takeIf { it.isNotBlank() } ?: "用户"
                        isJoiningWatchPartyRoom = true
                        scope.launch {
                            runCatching {
                                val roomPreview = watchPartyRepository.getRoom(cleanRoomId)
                                roomPreview.sameServerJoinFailureMessage(
                                    activeServerUrl = sessionSnapshot.serverUrl,
                                    savedServerUrls = sessionSnapshot.savedServers.map { it.serverUrl }
                                )?.let { message ->
                                    throw IllegalStateException(message)
                                }
                                watchPartyRepository.joinRoom(
                                    roomId = cleanRoomId,
                                    name = memberName,
                                    serverUrl = sessionSnapshot.serverUrl,
                                    serverName = sessionSnapshot.serverName,
                                    memberId = WatchPartyDeviceIdentity.memberId(context)
                                )
                            }.onSuccess { joined ->
                                val inviteText = copyableWatchPartyInviteText(joined.room.id)
                                val session = ActiveWatchPartySession(
                                    roomId = joined.room.id,
                                    memberId = joined.memberId,
                                    isHost = joined.room.hostMemberId == joined.memberId,
                                    roomName = joined.room.name,
                                    inviteText = inviteText
                                )
                                WatchPartySessionStore.set(session)
                                showJoinRoomDialog = false
                                joinRoomId = ""
                                Toast.makeText(context, "已加入房间", Toast.LENGTH_LONG).show()
                                onWatchPartyRoomReady()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    sanitizeWatchPartyErrorMessage(error.message),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isJoiningWatchPartyRoom = false
                        }
                    }
                ) {
                    Text(
                        if (isJoiningWatchPartyRoom) "加入中..." else "加入",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isJoiningWatchPartyRoom,
                    onClick = { showJoinRoomDialog = false }
                ) {
                    Text("取消", color = Color(0xFF64748B))
                }
            }
        )
    }
}

private fun hasFeatureHeroAssets(item: BaseItemDto): Boolean {
    val hasBackdrop = item.backdropImageTags?.any { it.isNotBlank() } == true ||
        item.parentBackdropImageTags?.any { it.isNotBlank() } == true ||
        item.imageTags
            ?.any { (type, tag) -> type.equals("Backdrop", ignoreCase = true) && tag.isNotBlank() } == true

    return hasBackdrop
}

@Composable
private fun CategoryChipMenu(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val menuOptions = remember(selectedCategory) {
        HomeCategory.all.filterNot { it == selectedCategory }
    }
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "category_arrow"
    )
    val pillShape = RoundedCornerShape(24.dp)
    val glassGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFFF9FBFF).copy(alpha = 0.94f),
            Color(0xFFEFF3F8).copy(alpha = 0.88f),
            Color.White.copy(alpha = 0.68f)
        )
    )
    val glassBorder = Color.White.copy(alpha = 0.58f)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(pillShape)
                .background(glassGradient)
                .border(1.dp, glassBorder, pillShape)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.grmemby_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(28.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                text = stringResource(HomeCategory.titleRes(selectedCategory)),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF111827)
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF111827).copy(alpha = 0.76f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(rotationZ = arrowRotation)
            )
        }

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 156.dp
        ) {
            menuOptions.forEach { category ->
                GlassDropdownMenuItem(
                    text = stringResource(HomeCategory.titleRes(category)),
                    selected = category == selectedCategory,
                    onClick = {
                        expanded = false
                        onCategorySelected(category)
                    }
                )
            }
        }
    }
}

@Composable
internal fun WatchPartyActionMenu(
    activeSession: ActiveWatchPartySession?,
    isLoading: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "watch_party_arrow"
    )
    val surfaceColor by DashboardPalette.surfaceColor.collectAsState()
    val pillShape = RoundedCornerShape(100.dp)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .capyTopActionSurface(pillShape)
                .clickable(enabled = !isLoading) { expanded = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isLoading) "处理中..." else "一起看",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF2F2F7)
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0x99EBEBF5),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(rotationZ = arrowRotation)
            )
        }

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = 190.dp,
            dark = true,
            surfaceColor = surfaceColor
        ) {
            GlassDropdownMenuItem(
                text = if (activeSession == null) "创建房间" else "创建房间（返回 ${activeSession.roomId}）",
                enabled = !isLoading,
                selected = activeSession != null,
                leadingIcon = Icons.Rounded.AddCircle,
                dark = true,
                surfaceColor = surfaceColor,
                onClick = {
                    expanded = false
                    onCreateRoom()
                }
            )
            GlassDropdownMenuItem(
                text = if (activeSession == null) "加入房间" else "已在房间，不能加入",
                enabled = activeSession == null && !isLoading,
                leadingIcon = Icons.Rounded.PersonAddAlt1,
                dark = true,
                surfaceColor = surfaceColor,
                onClick = {
                    expanded = false
                    onJoinRoom()
                }
            )
            GlassDropdownMenuItem(
                text = "退出房间",
                enabled = activeSession != null && !isLoading,
                leadingIcon = Icons.Rounded.ExitToApp,
                dark = true,
                surfaceColor = surfaceColor,
                onClick = {
                    expanded = false
                    onLeaveRoom()
                }
            )
        }
    }
}

@Composable
private fun WatchPartyMenuRow(
    text: String,
    enabled: Boolean,
    pillShape: RoundedCornerShape,
    glassGradient: Brush,
    glassBorder: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(pillShape)
            .background(glassGradient)
            .border(1.dp, glassBorder, pillShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = if (enabled) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.24f),
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer(rotationZ = -90f)
        )
    }
}

@Composable
private fun FeatureHeroCard(
    item: BaseItemDto,
    itemIndex: Int,
    listState: LazyListState,
    verticalParallaxOffsetPx: Float,
    images: FeatureCardImages?,
    onClick: () -> Unit,
    heroHeight: Dp,
    surfaceColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val itemName = item.name ?: stringResource(R.string.search_result_unknown_title)
    var contentVisible by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) { contentVisible = true }
    val logoAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        label = "hero_logo_alpha"
    )
    val metaAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        label = "hero_meta_alpha"
    )
    val metaOffset by animateFloatAsState(
        targetValue = if (contentVisible) 0f else 10f,
        label = "hero_meta_offset"
    )

    val lowBackdropUrl = images?.lowBackdropUrl ?: images?.backdropUrl
    val backdropUrl = images?.backdropUrl
    val logoUrl = images?.logoUrl
    var lowResImage by remember(item.id, lowBackdropUrl) { mutableStateOf(false) }
    val backdropParallaxShift = remember(verticalParallaxOffsetPx) { verticalParallaxOffsetPx * 0.4f }
    val pageOffset by remember(listState, itemIndex) {
        derivedStateOf {
            val visibleItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            if (visibleItemInfo == null || visibleItemInfo.size == 0) {
                1f
            } else {
                visibleItemInfo.offset.toFloat() / visibleItemInfo.size.toFloat()
            }
        }
    }
    val scrollInfluence = abs(pageOffset).coerceIn(0f, 1f)
    val contentAlpha = 1f - (0.35f * scrollInfluence)
    val contentScale = 1f - (0.05f * scrollInfluence)
    val contentShift = 12f * scrollInfluence
    val heroBlendColor = remember(surfaceColor) { surfaceColor.hillsDarker(0.78f) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!lowBackdropUrl.isNullOrBlank()) {
                val lowPainter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(lowBackdropUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false)
                        .allowHardware(true)
                        .allowRgb565(false)
                        .precision(Precision.EXACT)
                        .build()
                )
                val lowState by lowPainter.state.collectAsState()
                LaunchedEffect(lowState) {
                    if (lowState is AsyncImagePainter.State.Success ||
                        lowState is AsyncImagePainter.State.Error
                    ) {
                        lowResImage = true
                    }
                }

                Image(
                    painter = lowPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            translationY = backdropParallaxShift * 0.45f,
                            alpha = 0.24f,
                            scaleX = 1.08f,
                            scaleY = 1.08f
                        )
                )

                Image(
                    painter = lowPainter,
                    contentDescription = itemName,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            translationY = backdropParallaxShift,
                            scaleX = 1.0f,
                            scaleY = 1.0f
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            if (!backdropUrl.isNullOrBlank() && lowResImage) {
                val highPainter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .crossfade(false)
                        .allowHardware(true)
                        .allowRgb565(false)
                        .precision(Precision.EXACT)
                        .build()
                )
                val highState by highPainter.state.collectAsState()
                val highResImage = highState is AsyncImagePainter.State.Success
                val highAlpha by animateFloatAsState(
                    targetValue = if (highResImage) 1f else 0f,
                    label = "hero_backdrop_high_alpha"
                )

                Image(
                    painter = highPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            translationY = backdropParallaxShift * 0.45f,
                            alpha = highAlpha * 0.24f,
                            scaleX = 1.08f,
                            scaleY = 1.08f
                        )
                )

                Image(
                    painter = highPainter,
                    contentDescription = itemName,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            translationY = backdropParallaxShift,
                            alpha = highAlpha,
                            scaleX = 1.0f,
                            scaleY = 1.0f
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.36f),
                                0.18f to Color.Transparent,
                                0.54f to heroBlendColor.copy(alpha = 0.18f),
                                0.78f to heroBlendColor.copy(alpha = 0.72f),
                                1.0f to surfaceColor
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(heroHeight * 0.54f)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.36f to heroBlendColor.copy(alpha = 0.38f),
                                0.72f to heroBlendColor.copy(alpha = 0.88f),
                                1.0f to surfaceColor
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val radius = size.maxDimension * 0.72f
                        val brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                surfaceColor.copy(alpha = 0.24f)
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = radius
                        )
                        onDrawBehind { drawRect(brush) }
                    }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 30.dp)
                    .graphicsLayer(
                        alpha = contentAlpha,
                        scaleX = contentScale,
                        scaleY = contentScale,
                        translationY = contentShift
                    ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(logoUrl)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .crossfade(false)
                            .allowHardware(true)
                            .allowRgb565(false)
                            .build(),
                        contentDescription = stringResource(
                            R.string.feature_logo_content_description,
                            itemName
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(66.dp)
                            .fillMaxWidth(0.66f)
                            .graphicsLayer(
                                alpha = logoAlpha
                            )
                    )
                } else {
                    Text(
                        text = itemName,
                        color = Color.White,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .graphicsLayer(alpha = logoAlpha)
                    )
                }

                val ratingText = item.communityRating?.let { String.format("%.1f", it) }
                val resolvedYear = item.productionYear ?: item.premiereDate
                    ?.take(4)
                    ?.toIntOrNull()
                val genres = item.genres.orEmpty().take(3)

                val certificateText = item.officialRating?.takeIf { it.isNotBlank() }
                val hasMetaRow = !ratingText.isNullOrBlank() || resolvedYear != null || genres.isNotEmpty() || !certificateText.isNullOrBlank()
                if (hasMetaRow) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.graphicsLayer(
                            alpha = metaAlpha,
                            translationY = metaOffset
                        )
                    ) {
                        if (!ratingText.isNullOrBlank()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFE84B3C),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = ratingText,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (resolvedYear != null) {
                            Text(
                                text = resolvedYear.toString(),
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (genres.isNotEmpty()) {
                            Text(
                                text = genres.joinToString(separator = "/"),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!certificateText.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = certificateText,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .graphicsLayer(alpha = metaAlpha, translationY = metaOffset)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeaturePageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (selected) 7.dp else 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            Color.White.copy(alpha = 0.88f)
                        } else {
                            Color(0xFF4E5668).copy(alpha = 0.68f)
                        }
                    )
            )
        }
    }
}

@Composable
internal fun UserProfileAvatar(
    imageUrl: String?,
    serverTypeRaw: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ProfileImageLoader(
            imageUrl = imageUrl,
            serverTypeRaw = serverTypeRaw,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun FeatureHeroSkeleton(heroHeight: Dp) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }
}

@Composable
private fun FeatureHeroError(error: String, heroHeight: Dp) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}
