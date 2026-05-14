package com.grmemby.app.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grmemby.app.R
import com.grmemby.app.download.DownloadRepositoryProvider
import com.grmemby.app.ui.components.common.DownloadActionMenu
import com.grmemby.app.ui.components.common.DownloadContent
import com.grmemby.app.ui.components.common.DetailBackdropHero
import com.grmemby.app.ui.components.common.canResumeDownloads
import com.grmemby.app.ui.components.common.containerHeightDp
import com.grmemby.app.ui.components.common.containerWidthDp
import com.grmemby.app.ui.components.common.downloadButtonVisualState
import com.grmemby.app.ui.components.common.hasActiveDownloads
import com.grmemby.app.ui.components.common.isTabletDetailLayout
import com.grmemby.app.ui.components.common.pausableItemIds
import com.grmemby.app.ui.components.common.rememberDownloadPanelProgress
import com.grmemby.app.ui.components.common.rememberDownloadPanelState
import com.grmemby.shared.util.image.JellyfinPosterImage
import com.grmemby.shared.util.image.imageTagFor
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.repository.MediaRepository
import com.grmemby.data.repository.MediaRepositoryProvider
import com.grmemby.detail.CodecUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SeasonDetailScreen(
    seriesId: String,
    seasonId: String,
    seasonName: String? = null,
    inheritedSurfaceColor: Color = Color(0xFF2C3650),
    onBackPressed: () -> Unit = {},
    onEpisodeClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val screenWidthDp = containerWidthDp()
    val screenHeightDp = containerHeightDp()
    val useTabletBackdropLayout = isTabletDetailLayout(
        screenWidthDp = screenWidthDp,
        screenHeightDp = screenHeightDp
    )
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    val downloadRepository = remember { DownloadRepositoryProvider.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var episodes by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var seasonQueueInProgress by remember(seasonId) { mutableStateOf(false) }
    var downloadErrorDialogMessage by remember(seasonId) { mutableStateOf<String?>(null) }
    var storageSelectionDialogState by remember(seasonId) { mutableStateOf<SeasonEpisodeSelectionDialogState?>(null) }
    var seriesTitle by remember(seriesId) { mutableStateOf<String?>(null) }
    var seasonMetadata by remember(seasonId) { mutableStateOf<BaseItemDto?>(null) }
    var seriesMetadata by remember(seriesId) { mutableStateOf<BaseItemDto?>(null) }
    var heroImageCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var heroImageIndex by remember { mutableIntStateOf(0) }
    var logoImageCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var logoImageIndex by remember { mutableIntStateOf(0) }
    var logoCandidateLookup by remember(seasonId, seriesId) { mutableStateOf(true) }
    var logoLoadError by remember(seasonId, seriesId) { mutableStateOf(false) }
    val trackedDownloads by downloadRepository.observeTrackedDownloads().collectAsState(initial = emptyList())

    // Load episodes for this season
    LaunchedEffect(seasonId) {
        isLoading = true
        try {
            val result = mediaRepository.getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                limit = 100
            )
            result.fold(
                onSuccess = { episodeList ->
                    episodes = episodeList.sortedBy { it.indexNumber ?: 0 }
                    isLoading = false
                },
                onFailure = { exception ->
                    error = exception.message
                    isLoading = false
                }
            )
        } catch (e: Exception) {
            error = e.message
            isLoading = false
        }
    }

    LaunchedEffect(seriesId) {
        seriesTitle = null
        try {
            mediaRepository.getItemById(seriesId).fold(
                onSuccess = { seriesItem ->
                    seriesMetadata = seriesItem
                    seriesTitle = seriesItem.name?.takeIf { it.isNotBlank() }
                },
                onFailure = {
                    seriesMetadata = null
                }
            )
        } catch (_: Exception) {
            seriesMetadata = null
            seriesTitle = null
        }
    }

    LaunchedEffect(seasonId) {
        seasonMetadata = try {
            mediaRepository.getItemById(seasonId).getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    // Prepare hero image candidates in fallback order
    LaunchedEffect(seasonId, seriesId, seasonMetadata, seriesMetadata) {
        try {
            heroImageCandidates = listOfNotNull(
                mediaRepository.getBackdropImageUrl(
                    itemId = seasonId,
                    imageIndex = 0,
                    width = 1200,
                    height = 675,
                    quality = 92,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Backdrop",
                        targetItemId = seasonId
                    )
                ).first(),
                mediaRepository.getBackdropImageUrl(
                    itemId = seriesId,
                    imageIndex = 0,
                    width = 1200,
                    height = 675,
                    quality = 92,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Backdrop",
                        targetItemId = seriesId
                    ) ?: seriesMetadata?.imageTagFor(
                        imageType = "Backdrop",
                        targetItemId = seriesId
                    )
                ).first(),
                mediaRepository.getImageUrl(
                    itemId = seasonId,
                    imageType = "Primary",
                    width = 900,
                    height = 1200,
                    quality = 92,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Primary",
                        targetItemId = seasonId
                    )
                ).first(),
                mediaRepository.getImageUrl(
                    itemId = seriesId,
                    imageType = "Primary",
                    width = 900,
                    height = 1200,
                    quality = 92,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Primary",
                        targetItemId = seriesId
                    ) ?: seriesMetadata?.imageTagFor(
                        imageType = "Primary",
                        targetItemId = seriesId
                    )
                ).first()
            ).distinct()
            heroImageIndex = 0
        } catch (e: Exception) {
            heroImageCandidates = emptyList()
            heroImageIndex = 0
        }
    }

    // Prepare logo candidates (season first, then series)
    LaunchedEffect(seasonId, seriesId, seasonMetadata, seriesMetadata) {
        logoCandidateLookup = true
        logoLoadError = false
        try {
            logoImageCandidates = listOfNotNull(
                mediaRepository.getImageUrl(
                    itemId = seasonId,
                    imageType = "Logo",
                    width = 1200,
                    quality = 95,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Logo",
                        targetItemId = seasonId
                    )
                ).first(),
                mediaRepository.getImageUrl(
                    itemId = seriesId,
                    imageType = "Logo",
                    width = 1200,
                    quality = 95,
                    imageTag = seasonMetadata?.imageTagFor(
                        imageType = "Logo",
                        targetItemId = seriesId
                    ) ?: seriesMetadata?.imageTagFor(
                        imageType = "Logo",
                        targetItemId = seriesId
                    )
                ).first()
            ).distinct()
            logoImageIndex = 0
        } catch (e: Exception) {
            logoImageCandidates = emptyList()
            logoImageIndex = 0
        } finally {
            logoCandidateLookup = false
        }
    }

    val currentHeroImageUrl = heroImageCandidates.getOrNull(heroImageIndex)
    val currentLogoImageUrl = logoImageCandidates.getOrNull(logoImageIndex)
    val showLogoImage = currentLogoImageUrl != null && !logoLoadError
    val reserveLogoSpace = showLogoImage || logoCandidateLookup
    val fallbackHeaderTitle = seriesTitle
        ?: episodes.firstOrNull()?.seriesName?.takeIf { it.isNotBlank() }
        ?: seasonName
        ?: "Season"
    val seasonEpisodeIds = remember(episodes) { episodes.mapNotNull { it.id }.toSet() }
    val seasonDownloadEntries = remember(trackedDownloads, seasonEpisodeIds) {
        trackedDownloads.filter { seasonEpisodeIds.contains(it.itemId) }
    }
    val seasonDownload = rememberDownloadPanelState(
        entries = seasonDownloadEntries,
        expectedCount = seasonEpisodeIds.size
    )
    val hasActiveSeasonDownloads = seasonDownload.hasActiveDownloads
    val canResumeSeasonDownloads = seasonDownload.canResumeDownloads
    var seasonDownloadActionMenu by remember(
        seasonId,
        seasonDownload.status,
        seasonDownload.activeItemIds.size,
        seasonDownload.pausedItemIds.size
    ) { mutableStateOf(false) }
    val animatedSeasonDownloadProgress = rememberDownloadPanelProgress(
        panelState = seasonDownload,
        label = "season_download_progress"
    )
    val heroHeight = if (useTabletBackdropLayout) 430.dp else 380.dp
    val seasonSurface = inheritedSurfaceColor
    val seasonTopSurface = remember(seasonSurface) {
        Color(
            red = seasonSurface.red * 0.90f,
            green = seasonSurface.green * 0.90f,
            blue = seasonSurface.blue * 0.90f,
            alpha = 1f
        )
    }
    val seasonBottomSurface = remember(seasonSurface) {
        Color(
            red = seasonSurface.red * 0.78f,
            green = seasonSurface.green * 0.78f,
            blue = seasonSurface.blue * 0.78f,
            alpha = 1f
        )
    }
    val dynamicColors = rememberDetailDynamicColors(seasonSurface)

    when {
        error != null -> {
            LaunchedEffect(Unit) {
                onBackPressed()
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                seasonTopSurface,
                                seasonSurface,
                                seasonBottomSurface
                            )
                        )
                    ),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DetailBackdropHero(
                            imageUrl = currentHeroImageUrl,
                            contentDescription = seasonName,
                            heroHeight = heroHeight,
                            bottomFadeHeight = 120.dp,
                            onErrorStateChange = { hasError ->
                                if (hasError && heroImageIndex < heroImageCandidates.lastIndex) {
                                    heroImageIndex += 1
                                }
                            }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-22).dp)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .height(58.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (showLogoImage) {
                                    JellyfinPosterImage(
                                        context = context,
                                        imageUrl = currentLogoImageUrl,
                                        contentDescription = seasonName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterStart,
                                        onErrorStateChange = { hasError ->
                                            if (hasError) {
                                                if (logoImageIndex < logoImageCandidates.lastIndex) {
                                                    logoImageIndex += 1
                                                } else {
                                                    logoLoadError = true
                                                }
                                            } else {
                                                logoLoadError = false
                                            }
                                        }
                                    )
                                } else if (!reserveLogoSpace) {
                                    Text(
                                        text = fallbackHeaderTitle,
                                        fontSize = 17.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Text(
                                text = seasonName ?: "Season",
                                fontSize = 15.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.96f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = "${episodes.size} episodes",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.86f)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        episodes.firstOrNull()?.id?.let { firstEpisodeId ->
                                            onEpisodeClick(firstEpisodeId)
                                        }
                                    },
                                    enabled = episodes.isNotEmpty(),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(23.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = dynamicColors.accent,
                                        contentColor = dynamicColors.accentText,
                                        disabledContainerColor = dynamicColors.glassSoft,
                                        disabledContentColor = dynamicColors.secondaryText.copy(alpha = 0.48f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "播放本季",
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "播放",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            when {
                                                hasActiveSeasonDownloads -> seasonDownloadActionMenu = true
                                                else -> {
                                                    coroutineScope.launch {
                                                        seasonQueueInProgress = true
                                                        try {
                                                            val estimateResult = downloadRepository.buildEpisodeBatchEstimate(episodes)
                                                            estimateResult.fold(
                                                                onSuccess = { estimate ->
                                                                    storageSelectionDialogState = SeasonEpisodeSelectionDialogState.fromEstimate(estimate)
                                                                },
                                                                onFailure = { throwable ->
                                                                    downloadErrorDialogMessage = throwable.message
                                                                        ?: "准备整季下载失败。"
                                                                }
                                                            )
                                                        } finally {
                                                            seasonQueueInProgress = false
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = episodes.isNotEmpty() && (!seasonQueueInProgress || hasActiveSeasonDownloads),
                                        modifier = Modifier.fillMaxSize(),
                                        shape = RoundedCornerShape(23.dp),
                                        border = BorderStroke(1.dp, dynamicColors.border),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = dynamicColors.glass,
                                            contentColor = dynamicColors.accent,
                                            disabledContainerColor = dynamicColors.glassSoft.copy(alpha = 0.18f),
                                            disabledContentColor = dynamicColors.secondaryText.copy(alpha = 0.42f)
                                        )
                                    ) {
                                        DownloadContent(
                                            visualState = downloadButtonVisualState(
                                                panelState = seasonDownload,
                                                isQueueing = seasonQueueInProgress,
                                                supportsCompleted = true
                                            ),
                                            progress = animatedSeasonDownloadProgress,
                                            idleLabelRes = R.string.downloads_action_download,
                                            fontSize = 13.sp,
                                            iconSize = 18.dp,
                                            progressSize = 16.dp
                                        )
                                    }

                                    DownloadActionMenu(
                                        expanded = seasonDownloadActionMenu,
                                        canResume = canResumeSeasonDownloads,
                                        hasActiveDownloads = hasActiveSeasonDownloads,
                                        onDismissRequest = { seasonDownloadActionMenu = false },
                                        onPauseResume = {
                                            seasonDownloadActionMenu = false
                                            if (canResumeSeasonDownloads) {
                                                seasonDownload.pausedItemIds.forEach(downloadRepository::resumeDownload)
                                            } else {
                                                seasonDownload.pausableItemIds.forEach(downloadRepository::pauseDownload)
                                            }
                                        },
                                        onCancel = {
                                            seasonDownloadActionMenu = false
                                            seasonDownload.activeItemIds.forEach(downloadRepository::cancelDownload)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "剧集",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp)
                    )
                }

                if (isLoading) {
                    items(4) {
                        EpisodeCardSkeleton(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            surfaceColor = seasonSurface
                        )
                    }
                } else {
                    items(
                        items = episodes,
                        key = { episode -> episode.id ?: "episode_${episode.parentIndexNumber}_${episode.indexNumber}_${episode.name}" }
                    ) { episode ->
                        EpisodeListItem(
                            episode = episode,
                            mediaRepository = mediaRepository,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            surfaceColor = seasonSurface,
                            onClick = {
                                episode.id?.let { episodeId ->
                                    onEpisodeClick(episodeId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    storageSelectionDialogState?.let { dialogState ->
        DownloadDialog(
            title = "选择分集",
            subtitle = "选择要下载的分集，所选总大小不能超过可用存储空间。",
            availableBytes = dialogState.availableBytes,
            options = dialogState.options,
            initialSelection = dialogState.options.map { it.id }.toSet(),
            confirmLabel = "Download Episodes",
            surfaceColor = seasonSurface,
            onDismiss = { storageSelectionDialogState = null },
            onConfirm = { selectedIds ->
                val selectedEpisodes = dialogState.options
                    .filter { selectedIds.contains(it.id) }
                    .mapNotNull { option -> dialogState.episodesById[option.id] }
                storageSelectionDialogState = null
                coroutineScope.launch {
                    seasonQueueInProgress = true
                    try {
                        downloadRepository.enqueueEpisodeDownloads(selectedEpisodes).onFailure { throwable ->
                            downloadErrorDialogMessage = throwable.message
                                ?: "Failed to queue selected episodes."
                        }
                    } finally {
                        seasonQueueInProgress = false
                    }
                }
            }
        )
    }

    downloadErrorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { downloadErrorDialogMessage = null },
            containerColor = dynamicColors.glass,
            titleContentColor = Color.White,
            textContentColor = dynamicColors.secondaryText,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = "下载失败",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(text = message)
            },
            confirmButton = {
                TextButton(
                    onClick = { downloadErrorDialogMessage = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = dynamicColors.accent
                    )
                ) {
                    Text("确定", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

private data class SeasonEpisodeSelectionDialogState(
    val availableBytes: Long,
    val options: List<StorageSelectionOption>,
    val episodesById: Map<String, BaseItemDto>
) {
    companion object {
        fun fromEstimate(estimate: com.grmemby.app.download.BatchDownloadEstimate): SeasonEpisodeSelectionDialogState {
            val candidates = estimate.candidates
                .filter { !it.item.id.isNullOrBlank() }
                .sortedWith(
                    compareBy<com.grmemby.app.download.BatchDownloadCandidate>(
                        { it.item.parentIndexNumber ?: Int.MAX_VALUE },
                        { it.item.indexNumber ?: Int.MAX_VALUE },
                        { it.item.name.orEmpty() }
                    )
                )

            val options = candidates.map { candidate ->
                val episode = candidate.item
                val episodeId = episode.id.orEmpty()
                val episodeCode = buildString {
                    episode.parentIndexNumber?.let { season ->
                        episode.indexNumber?.let { episodeNumber ->
                            append("S$season:E$episodeNumber")
                        }
                    }
                }
                val episodeSubtitle = buildString {
                    if (episodeCode.isNotBlank()) append(episodeCode)
                    if (!episode.seriesName.isNullOrBlank()) {
                        if (isNotBlank()) append("  |  ")
                        append(episode.seriesName)
                    }
                }.ifBlank { null }

                StorageSelectionOption(
                    id = episodeId,
                    title = episode.name?.takeIf { it.isNotBlank() } ?: "第",
                    subtitle = episodeSubtitle,
                    requiredBytes = candidate.remainingBytes ?: 0L
                )
            }

            return SeasonEpisodeSelectionDialogState(
                availableBytes = estimate.availableBytes,
                options = options,
                episodesById = candidates.associate { it.item.id.orEmpty() to it.item }
            )
        }
    }
}

@Composable
private fun EpisodeListItem(
    episode: BaseItemDto,
    mediaRepository: MediaRepository,
    modifier: Modifier = Modifier,
    surfaceColor: Color = Color(0xFF2C3650),
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var episodeImageUrl by remember(episode.id) { mutableStateOf<String?>(null) }
    val dynamicColors = rememberDetailDynamicColors(surfaceColor)

    LaunchedEffect(episode.id) {
        episodeImageUrl = resolveEpisodePrimaryOrSeriesBackdrop(
            episode = episode,
            mediaRepository = mediaRepository,
            width = 1280,
            height = 720,
            quality = 95
        )
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = dynamicColors.glassSoft),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, dynamicColors.border)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .height(74.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(dynamicColors.glassSoft)
                ) {
                    JellyfinPosterImage(
                        context = context,
                        imageUrl = episodeImageUrl,
                        contentDescription = episode.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = buildString {
                            episode.indexNumber?.let { append("$it. ") }
                            append(episode.name ?: "Unknown Episode")
                        },
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    episode.runTimeTicks?.let { ticks ->
                        Text(
                            text = CodecUtils.formatRuntime(ticks),
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.82f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
