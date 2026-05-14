package com.grmemby.app.ui.screens.dashboard
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.sqrt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.grmemby.shared.preferences.Preferences
import com.grmemby.shared.ui.components.common.ShimmerEffect
import com.grmemby.app.ui.screens.dashboard.home.Dashboard
import com.grmemby.app.ui.screens.dashboard.settings.Settings
import com.grmemby.app.ui.screens.dashboard.settings.ServerManagementScreen
import com.grmemby.app.ui.screens.dashboard.media.MyMedia
import com.grmemby.app.ui.screens.dashboard.media.ForYou
import com.grmemby.app.ui.screens.dashboard.favorites.Favorites
import com.grmemby.app.ui.screens.dashboard.search.SearchContainer
import com.grmemby.app.ui.screens.dashboard.watchparty.WatchPartyRoomScreen
import com.grmemby.app.ui.components.glass.blendGlassColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import com.grmemby.app.R
import com.grmemby.data.network.NetworkModule
import com.grmemby.data.repository.AuthRepositoryProvider
import com.grmemby.data.repository.SeerrRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private fun DashboardEnterTransition(): EnterTransition {
    return fadeIn(animationSpec = tween(180, easing = LinearOutSlowInEasing))
}

private fun DashboardExitTransition(): ExitTransition {
    return fadeOut(animationSpec = tween(120, easing = FastOutLinearInEasing))
}

private fun navigationBarAtmosphereBrush(
    surfaceColor: Color,
    regionColors: List<Color>
): Brush {
    val sampled = regionColors.ifEmpty { listOf(surfaceColor) }
    val left = sampled.getOrNull(0) ?: surfaceColor
    val center = sampled.getOrNull(1) ?: surfaceColor
    val right = sampled.getOrNull(2) ?: center

    // CapyPlayer bottom bar does not rely on plain transparency: AOT shows the
    // bar color is fed into GlassDefaults.frosted() and then a real
    // BackdropFilterLayer/GaussianBlurImageFilter is applied. Compose has no
    // backdrop blur here, so this layer keeps the dynamic page atmosphere while
    // raising the fill enough that text behind the capsule is not readable.
    return Brush.horizontalGradient(
        colors = listOf(
            blendGlassColor(left, Color.White, 0.66f).copy(alpha = 0.58f),
            blendGlassColor(center, Color.White, 0.72f).copy(alpha = 0.62f),
            blendGlassColor(right, Color.White, 0.66f).copy(alpha = 0.58f)
        )
    )
}

private fun navigationBarOpaqueFrostBrush(
    surfaceColor: Color,
    regionColors: List<Color>
): Brush {
    val sampled = regionColors.ifEmpty { listOf(surfaceColor) }
    val center = sampled.getOrNull(1) ?: surfaceColor
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.White.copy(alpha = 0.34f),
            0.18f to blendGlassColor(center, Color.White, 0.84f).copy(alpha = 0.62f),
            0.58f to blendGlassColor(center, Color.White, 0.76f).copy(alpha = 0.54f),
            1.00f to blendGlassColor(center, Color.White, 0.64f).copy(alpha = 0.46f)
        )
    )
}

private fun navigationBarSpecularBrush(
    surfaceColor: Color,
    regionColors: List<Color>
): Brush {
    val sampled = regionColors.ifEmpty { listOf(surfaceColor) }
    val center = sampled.getOrNull(1) ?: surfaceColor
    return Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.40f),
            blendGlassColor(center, Color.White, 0.80f).copy(alpha = 0.11f),
            Color.White.copy(alpha = 0.035f)
        )
    )
}

sealed class DashboardDestination(
    val route: String,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : DashboardDestination(
        "dashboard_home",
        R.string.home,
        Icons.Filled.Home,
        Icons.Outlined.Home
    )
    object MyMedia : DashboardDestination(
        "my_media",
        R.string.dashboard_for_you,
        Icons.Filled.PlayArrow,
        Icons.Outlined.PlayArrow
    )
    object Search : DashboardDestination(
        "search",
        R.string.search,
        Icons.Filled.Search,
        Icons.Outlined.Search
    )
    object Favorites : DashboardDestination(
        "favorites",
        R.string.favorites,
        Icons.Filled.Favorite,
        Icons.Outlined.FavoriteBorder
    )
    object WatchParty : DashboardDestination(
        "watch_party_room",
        R.string.watch_party_room,
        Icons.Filled.PlayArrow,
        Icons.Outlined.PlayArrow
    )
    object Settings : DashboardDestination(
        "settings",
        R.string.settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings
    )
    object ServerManagement : DashboardDestination(
        "server_management",
        R.string.settings_server_label,
        Icons.Filled.Storage,
        Icons.Outlined.Storage
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContainer(
    onLogout: () -> Unit = {},
    onNavigateToDetail: (com.grmemby.data.model.BaseItemDto) -> Unit = {},
    onNavigateToViewAll: (String, String?, String) -> Unit = { _, _, _ -> },
    onNavigateToPlayer: (String) -> Unit = {},
    onNavigateToPlayerSettings: () -> Unit = {},
    onNavigateToInterfaceSettings: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToCacheSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onAddServer: () -> Unit = {},
    onAddUser: (serverUrl: String, serverName: String?) -> Unit = { _, _ -> },
    onEditUser: (serverUrl: String, serverName: String?, username: String?) -> Unit = { serverUrl, serverName, _ -> onAddUser(serverUrl, serverName) },
    onNestedRouteChanged: (String?) -> Unit = {}
) {
    val navController = rememberNavController()
    val homeScrollState = rememberLazyListState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val preferences = remember(context) { Preferences(context) }
    val appContext = remember(context) { context.applicationContext }
    val authRepository = remember(appContext) { AuthRepositoryProvider.getInstance(appContext) }
    val seerrRepository = remember(appContext) { SeerrRepository(appContext) }
    val networkAvailabilityFlow = remember(appContext) {
        NetworkModule.observeNetworkAvailability(appContext)
    }
    val isNetworkAvailable by networkAvailabilityFlow.collectAsStateWithLifecycle(
        initialValue = NetworkModule.isInternetAvailable(appContext)
    )
    val sharedDashboardSurface by DashboardPalette.surfaceColor.collectAsStateWithLifecycle(
        initialValue = Color(0xFF2C3650)
    )
    val bottomBarRegionColors by DashboardPalette.bottomBarColors.collectAsStateWithLifecycle(
        initialValue = listOf(
            blendGlassColor(sharedDashboardSurface, Color.Black, 0.35f),
            sharedDashboardSurface,
            blendGlassColor(sharedDashboardSurface, Color.Black, 0.48f)
        )
    )
    val useMyMediaTabEnabled by preferences.UseMyMediaTabEnabled()
        .collectAsStateWithLifecycle(
            initialValue = preferences.isUseMyMediaTabEnabled()
        )
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Match the reference geometry: the outer glass capsule should be only a
    // thin rim around the selected pill, with the selected state almost filling
    // one tab slot instead of floating inside a roomy frame.
    val bottomBarHeight = 52.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = configuration.screenWidthDp >= 600
    val capyWidthRatio = when {
        isTablet && isLandscape -> 0.30f
        isTablet -> 0.34f
        isLandscape -> 0.46f
        else -> 0.59f
    }
    val capyBarWidth = (configuration.screenWidthDp * capyWidthRatio)
        .dp
        .coerceIn(248.dp, 396.dp)
    val navigationBarInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val bottomBarHideDistancePx = with(density) { (bottomBarHeight + 40.dp).toPx() } + navigationBarInsetPx
    val hideThresholdPx = with(density) { 22.dp.toPx() }
    val showThresholdPx = with(density) { 14.dp.toPx() }
    var isBottomBarVisible by remember { mutableStateOf(true) }
    var accumulatedScrollPx by remember { mutableFloatStateOf(0f) }
    var homeScrollToTop by remember { mutableStateOf(0) }

    val bottomBarScrollConnection = remember {
        object : NestedScrollConnection {}
    }

    LaunchedEffect(currentRoute) {
        onNestedRouteChanged(currentRoute)
        isBottomBarVisible = true
        accumulatedScrollPx = 0f
    }
    DisposableEffect(Unit) {
        onDispose { onNestedRouteChanged(null) }
    }
    LaunchedEffect(isNetworkAvailable) {
        if (!isNetworkAvailable) {
            isBottomBarVisible = true
            accumulatedScrollPx = 0f
        }
    }

    val bottomBarTransition = updateTransition(
        targetState = isBottomBarVisible,
        label = "bottom_bar_visibility"
    )
    val bottomBarTranslationPx by bottomBarTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 440 else 320,
                easing = FastOutSlowInEasing
            )
        },
        label = "bottom_bar_translation"
    ) { visible ->
        if (visible) 0f else bottomBarHideDistancePx
    }
    val bottomBarAlpha by bottomBarTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 420 else 260,
                easing = LinearOutSlowInEasing
            )
        },
        label = "bottom_bar_alpha"
    ) { visible ->
        if (visible) 1f else 0f
    }



    // CapyPlayer bottom-bar item metrics were confirmed from AOT _NavItem:
    // single floating GlassSurface pill, equally expanded children, icon+label.
    // User-facing Grmemby bottom tabs are intentionally limited to 3 entries.
    val mainDestinations = listOf(
        DashboardDestination.Home,
        DashboardDestination.Search,
        DashboardDestination.Settings
    )
    val bottomBarRoutes = remember {
        setOf(
            DashboardDestination.Home.route,
            DashboardDestination.Search.route,
            DashboardDestination.Settings.route
        )
    }
    val shouldShowBottomBar = currentRoute in bottomBarRoutes
    val isOfflineTwoTabMode = false
    val offlineAllowedRoutes = remember {
        setOf(
            DashboardDestination.Home.route,
            DashboardDestination.MyMedia.route,
            DashboardDestination.Favorites.route,
            DashboardDestination.Search.route,
            DashboardDestination.Settings.route
        )
    }
    val navigateToDestination: (DashboardDestination) -> Unit = { destination ->
        if (
            destination == DashboardDestination.Home &&
            currentRoute == DashboardDestination.Home.route
        ) {
            homeScrollToTop += 1
        }
        if (currentRoute != destination.route) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(currentRoute, homeScrollToTop) {
        if (
            currentRoute != DashboardDestination.Home.route ||
            homeScrollToTop == 0
        ) {
            return@LaunchedEffect
        }

        if (
            homeScrollState.firstVisibleItemIndex != 0 ||
            homeScrollState.firstVisibleItemScrollOffset != 0
        ) {
            homeScrollState.animateScrollToItem(0)
        }
        homeScrollToTop = 0
    }

    LaunchedEffect(isNetworkAvailable, currentRoute) {
        if (!isNetworkAvailable && currentRoute != null && !offlineAllowedRoutes.contains(currentRoute)) {
            navigateToDestination(DashboardDestination.Home)
        }
    }

    LaunchedEffect(authRepository, seerrRepository) {
        authRepository.observeActiveSession()
            .map { snapshot -> snapshot.activeServerId }
            .distinctUntilChanged()
            .collect { scopeId ->
                if (!scopeId.isNullOrBlank() && seerrRepository.getSavedConnectionInfo(scopeId) != null) {
                    seerrRepository.refreshConnection(scopeId)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(sharedDashboardSurface)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Main content area with transitions and parallax effect
            NavHost(
                navController = navController,
                startDestination = DashboardDestination.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isNetworkAvailable) {
                            Modifier.nestedScroll(bottomBarScrollConnection)
                        } else {
                            Modifier
                        }
                    )
                    .graphicsLayer(
                        translationY = when (currentRoute) {
                            DashboardDestination.Search.route -> -2f
                            else -> 0f
                        },
                        transformOrigin = TransformOrigin.Center
                    )
            ) {
                composable(
                    DashboardDestination.Home.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    // Track when Home tab becomes active
                    val isHomeActive = currentRoute == DashboardDestination.Home.route

                    ContentWrapper {
                        Dashboard(
                            onLogout = onLogout,
                            onNavigateToDetail = onNavigateToDetail,
                            onNavigateToViewAll = onNavigateToViewAll,
                            onNavigateToPlayer = onNavigateToPlayer,
                            onNavigateToWatchPartyRoom = {
                                navController.navigate(DashboardDestination.WatchParty.route) {
                                    launchSingleTop = true
                                }
                            },
                            onAddServer = onAddServer,
                            onAddUser = onAddUser,
                            onEditUser = onEditUser,
                            isTabActive = isHomeActive,
                            dashboardScrollState = homeScrollState
                        )
                    }
                }
                composable(
                    DashboardDestination.MyMedia.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ContentWrapper {
                        if (useMyMediaTabEnabled) {
                            MyMedia(
                                onLibraryClick = { contentType, parentId, title ->
                                    onNavigateToViewAll(contentType.name, parentId, title)
                                }
                            )
                        } else {
                            ForYou(onItemClick = onNavigateToDetail)
                        }
                    }
                }
                composable(
                    DashboardDestination.Search.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ContentWrapper {
                        SearchContainer(
                            backgroundColor = sharedDashboardSurface,
                            onNavigateToDetail = onNavigateToDetail,
                            onCancel = {
                                navigateToDestination(DashboardDestination.Home)
                            }
                        )
                    }
                }
                composable(
                    DashboardDestination.Favorites.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ContentWrapper {
                        Favorites(
                            onItemClick = onNavigateToDetail
                        )
                    }
                }
                composable(
                    DashboardDestination.WatchParty.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ContentWrapper {
                        WatchPartyRoomScreen(
                            onNavigateToPlayer = onNavigateToPlayer,
                            onBackToHome = {
                                navController.navigate(DashboardDestination.Home.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
                composable(
                    DashboardDestination.Settings.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ContentWrapper {
                        Settings(
                            onLogout = onLogout,
                            onNavigateToPlayerSettings = onNavigateToPlayerSettings,
                            onNavigateToInterfaceSettings = onNavigateToInterfaceSettings,
                            onNavigateToDownloads = onNavigateToDownloads,
                            onNavigateToCacheSettings = onNavigateToCacheSettings,
                            onNavigateToAbout = onNavigateToAbout,
                            onAddServer = onAddServer,
                            onAddUser = onAddUser,
                            onEditUser = onEditUser,
                            onNavigateToServerManagement = {
                                navController.navigate(DashboardDestination.ServerManagement.route) {
                                    launchSingleTop = true
                                }
                            },
                            backgroundColor = sharedDashboardSurface
                        )
                    }
                }
                composable(
                    DashboardDestination.ServerManagement.route,
                    enterTransition = { DashboardEnterTransition() },
                    exitTransition = { DashboardExitTransition() }
                ) {
                    ServerManagementScreen(
                        onBackPressed = { navController.popBackStack() },
                        onAddServer = onAddServer,
                        onAddUser = onAddUser,
                        onEditUser = onEditUser,
                        backgroundColor = sharedDashboardSurface
                    )
                }
            }

            if (shouldShowBottomBar) {
                val pillShape = RoundedCornerShape(999.dp)
                val selectedMainIndex = mainDestinations.indexOfFirst { it.route == currentRoute }
                val indicatorProgress by animateFloatAsState(
                    targetValue = selectedMainIndex.coerceAtLeast(0).toFloat(),
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    label = "capy_nav_indicator_position"
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (selectedMainIndex >= 0) 1f else 0f,
                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
                    label = "capy_nav_indicator_alpha"
                )

                // CapyPlayer AOT _buildBottomNavBar evidence:
                // GlassSurface(GlassDefaults.frosted(color = appColors.surface alpha 0.1, blur = 40),
                // LiquidRoundedRectangle, LiquidGlassSettings) -> Padding(7 horizontal, 3 vertical)
                // -> SizedBox(height = 46 + 3*2 on phone) -> Stack(AnimatedAlign indicator + Row _NavItem).
                // Keep Grmemby entries to Home/Search/Settings, but preserve Capy global dark-glass tokens.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 8.dp)
                        .height(bottomBarHeight)
                        .width(capyBarWidth)
                        .graphicsLayer {
                            translationY = bottomBarTranslationPx
                            alpha = bottomBarAlpha
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .shadow(
                            elevation = 16.dp,
                            shape = pillShape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.18f),
                            spotColor = Color.Black.copy(alpha = 0.26f)
                        )
                        .clip(pillShape)
                        .background(Color(0x1A1C1C1E))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.075f),
                                    Color.White.copy(alpha = 0.022f),
                                    Color.Black.copy(alpha = 0.045f)
                                )
                            )
                        )
                        .border(
                            width = 0.6.dp,
                            color = Color(0x99EBEBF5),
                            shape = pillShape
                        )
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 3.dp, vertical = 2.dp)
                    ) {
                        val itemWidth = maxWidth / mainDestinations.size.toFloat()
                        val indicatorOffset = with(LocalDensity.current) {
                            (itemWidth.toPx() * indicatorProgress).toDp()
                        }
                        Box(
                            modifier = Modifier
                                .offset(x = indicatorOffset)
                                .width(itemWidth)
                                .fillMaxHeight()
                                .alpha(indicatorAlpha)
                                .clip(RoundedCornerShape(100.dp))
                                .background(Color(0x4CEBEBF5))
                        )
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            mainDestinations.forEach { destination ->
                                val isSelected = currentRoute == destination.route
                                NavigationItem(
                                    modifier = Modifier.weight(1f),
                                    destination = destination,
                                    title = capyNavigationTitle(destination, useMyMediaTabEnabled),
                                    selectedIcon = destination.selectedIcon,
                                    unselectedIcon = destination.unselectedIcon,
                                    isSelected = isSelected,
                                    onClick = { navigateToDestination(destination) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Function to draw curved navigation bar with dynamic effects
private fun DrawScope.draw3DCurvedNavigationBar(
    width: Float,
    height: Float
) {
    val centerWidth = width / 2f
    val topCornerRadius = 24.dp.toPx()
    val bottomCornerRadius = 26.dp.toPx()

    // Use dimensions that are proportional to the FAB size for a good fit
    val fabRadius = 28.dp.toPx()
    val curveDepth = fabRadius + (fabRadius / 3f)
    val targetCurveWidth = (fabRadius * 2) + (fabRadius * 1.5f)
    val sideInset = topCornerRadius + 6.dp.toPx()
    val targetHalfWidth = targetCurveWidth / 2f
    fun snapX(value: Float): Float = round(value * 2f) / 2f
    val notchCenterX = snapX(centerWidth)
    val availableHalfWidth = min(
        notchCenterX - sideInset,
        (width - sideInset) - notchCenterX
    ).coerceAtLeast(0f)
    val curveHalfWidth = min(targetHalfWidth, availableHalfWidth)
    var curveStartX = snapX(notchCenterX - curveHalfWidth)
    var curveEndX = snapX(notchCenterX + (notchCenterX - curveStartX))
    val maxRightX = width - sideInset
    if (curveEndX > maxRightX) {
        curveEndX = maxRightX
        curveStartX = snapX(notchCenterX - (curveEndX - notchCenterX))
    }
    val adjustedCurveWidth = curveEndX - curveStartX

    // Build left control points first, then reflect for perfect symmetry.
    val controlPoint1X = snapX(curveStartX + adjustedCurveWidth * 0.12f)
    val controlPoint2X = snapX(notchCenterX - adjustedCurveWidth * 0.35f)
    val controlPoint3X = snapX(notchCenterX + (notchCenterX - controlPoint2X))
    val controlPoint4X = snapX(notchCenterX + (notchCenterX - controlPoint1X))

    val backgroundPath = Path().apply {
        moveTo(0f, topCornerRadius)
        quadraticTo(0f, 0f, topCornerRadius, 0f)
        lineTo(curveStartX, 0f)

        cubicTo(
            x1 = controlPoint1X, y1 = 0f,
            x2 = controlPoint2X, y2 = curveDepth,
            x3 = notchCenterX, y3 = curveDepth
        )

        cubicTo(
            x1 = controlPoint3X, y1 = curveDepth,
            x2 = controlPoint4X, y2 = 0f,
            x3 = curveEndX, y3 = 0f
        )

        lineTo(width - topCornerRadius, 0f)
        quadraticTo(width, 0f, width, topCornerRadius)
        lineTo(width, height - bottomCornerRadius)
        quadraticTo(width, height, width - bottomCornerRadius, height)
        lineTo(bottomCornerRadius, height)
        quadraticTo(0f, height, 0f, height - bottomCornerRadius)
        close()
    }

    // AMOLED Black background with subtle depth
    drawPath(
        path = backgroundPath,
        color = Color.Black
    )

    drawPath(
        path = backgroundPath,
        color = Color.White.copy(alpha = 0.05f),
        style = Stroke(width = 1.dp.toPx())
    )

    val notchGlowPath = Path().apply {
        moveTo(curveStartX, 0f)
        cubicTo(
            x1 = controlPoint1X, y1 = 0f,
            x2 = controlPoint2X, y2 = curveDepth,
            x3 = notchCenterX, y3 = curveDepth
        )
        cubicTo(
            x1 = controlPoint3X, y1 = curveDepth,
            x2 = controlPoint4X, y2 = 0f,
            x3 = curveEndX, y3 = 0f
        )
    }

    val glowCenterColor = Color(0xFFBEE8FF)
    val glowBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            glowCenterColor.copy(alpha = 0.14f),
            Color.Transparent
        ),
        startX = curveStartX,
        endX = curveEndX
    )
    val glowCoreBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            glowCenterColor.copy(alpha = 0.28f),
            Color.Transparent
        ),
        startX = curveStartX,
        endX = curveEndX
    )

    drawPath(
        path = notchGlowPath,
        brush = glowBrush,
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
    )
    drawPath(
        path = notchGlowPath,
        brush = glowCoreBrush,
        style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawSimpleNavigationBar(
    width: Float,
    height: Float
) {
    val corner = 26.dp.toPx()
    drawRoundRect(
        color = Color.Black,
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = CornerRadius(corner, corner)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.05f),
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = 1.dp.toPx())
    )
}

@Composable
private fun capyNavigationTitle(
    destination: DashboardDestination,
    useMyMediaTabEnabled: Boolean
): String {
    return when (destination) {
        DashboardDestination.Home -> stringResource(R.string.home)
        DashboardDestination.MyMedia -> stringResource(
            if (useMyMediaTabEnabled) R.string.my_media else R.string.dashboard_for_you
        )
        DashboardDestination.Favorites -> stringResource(R.string.favorites)
        DashboardDestination.Settings -> stringResource(R.string.settings)
        DashboardDestination.Search -> stringResource(R.string.search)
        else -> stringResource(destination.titleRes)
    }
}

@Composable
private fun NavigationItem(
    modifier: Modifier = Modifier,
    destination: DashboardDestination,
    title: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFF2F2F7) else Color(0x99EBEBF5),
        animationSpec = tween(durationMillis = 220),
        label = "capy_nav_icon_tint"
    )
    val textTint by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFF2F2F7) else Color(0x99EBEBF5),
        animationSpec = tween(durationMillis = 220),
        label = "capy_nav_text_tint"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .padding(horizontal = 1.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Reference tab spacing: icon/text remain compact but breathe slightly
        // inside the almost-full-height selected pill.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = if (isSelected) selectedIcon else unselectedIcon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            color = textTint,
            fontSize = 9.sp,
            lineHeight = 9.9.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}


@Composable
private fun ContentWrapper(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        content()
    }
}

// Poster Component
@Composable
fun PosterCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Animation states
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val rotationX by animateFloatAsState(
        targetValue = when {
            isPressed -> 8f
            isHovered -> -12f
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val rotationY by animateFloatAsState(
        targetValue = when {
            isPressed -> -3f
            isHovered -> 5f
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    val elevation by animateDpAsState(
        targetValue = when {
            isPressed -> 2.dp
            isHovered -> 16.dp
            else -> 4.dp
        },
        animationSpec = tween(300)
    )

    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                rotationX = rotationX,
                rotationY = rotationY,
                transformOrigin = TransformOrigin.Center,
                cameraDistance = 12f * density.density
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        content()
    }
}

// List Item Component
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        )
    )

    val rotationX by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                rotationX = rotationX,
                transformOrigin = TransformOrigin.Center,
                cameraDistance = 20f * density.density
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardContainerPreview() {
    DashboardContainer()
}

/**
 * Skeleton for poster/card items in horizontal rows
 * Used in: Dashboard sections, Continue Watching, etc.
 */
@Composable
fun ActualImageBlurPlaceholder(
    itemId: String,
    mediaRepository: com.grmemby.data.repository.MediaRepository,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    height: Dp = 210.dp,
    cornerRadius: Float = 16f,
    imageType: String = "Primary"
) {
    var blurImageUrl by remember(itemId) { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        try {
            val url = mediaRepository.getImageUrl(
                itemId = itemId,
                imageType = imageType,
                width = if (imageType == "Thumb") 50 else 30,
                height = if (imageType == "Thumb") 30 else 45,
                quality = 5
            ).first()
            blurImageUrl = url
        } catch (e: Exception) {
        }
    }

    if (blurImageUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(blurImageUrl)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)
                .allowRgb565(true)
                .crossfade(0)
                .build(),
            contentDescription = null,
            modifier = modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(cornerRadius.dp))
                .blur(radius = 8.dp),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        )
    }
}

@Composable
fun PosterSkeleton(
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    height: Dp = 260.dp,
    cornerRadius: Float = 16f
) {
    Column(
        modifier = modifier
            .width(width)
            .height(height)
    ) {
        // Image skeleton
        ShimmerEffect(
            modifier = Modifier
                .width(width)
                .aspectRatio(0.67f),
            cornerRadius = cornerRadius
        )

        // Title and metadata skeleton
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(top = 8.dp, start = 4.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Title skeleton
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(16.dp),
                cornerRadius = 4f
            )
            // Year/type skeleton
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp),
                cornerRadius = 4f
            )
        }
    }
}

/**
 * Skeleton for continue watching items (landscape orientation)
 */
@Composable
fun ContinueWatchingSkeleton(
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(5) {
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .height(180.dp)
            ) {
                // Image skeleton
                ShimmerEffect(
                    modifier = Modifier
                        .width(200.dp)
                        .height(120.dp),
                    cornerRadius = 12f
                )

                // Title and info skeleton
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Title skeleton
                    ShimmerEffect(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(16.dp),
                        cornerRadius = 4f
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Year/type skeleton
                    ShimmerEffect(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(12.dp),
                        cornerRadius = 4f
                    )
                }
            }
        }
    }
}

/**
 * Skeleton for library/poster grid sections
 */
@Composable
fun LibrarySkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 6
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(
                compositingStrategy = CompositingStrategy.Offscreen
            )
    ) {
        items(itemCount) {
            PosterSkeleton()
        }
    }
}

/**
 * Skeleton for grid view (search results, view all screens)
 */
@Composable
fun GridSkeleton(
    modifier: Modifier = Modifier,
    columns: Int = 2,
    itemCount: Int = 6,
    aspectRatio: Float = 0.65f
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        items(itemCount) {
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                cornerRadius = 16f
            )
        }
    }
}

/**
 * Skeleton for section titles
 */
@Composable
fun SectionTitleSkeleton(
    modifier: Modifier = Modifier,
    width: Dp = 150.dp
) {
    ShimmerEffect(
        modifier = modifier
            .width(width)
            .height(24.dp),
        cornerRadius = 4f
    )
}

/**
 * Skeleton for genre sections with title + horizontal list
 */
@Composable
fun GenreSectionSkeleton(
    modifier: Modifier = Modifier,
    sectionCount: Int = 3
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        repeat(sectionCount) {
            Column {
                // Genre title skeleton
                SectionTitleSkeleton(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Genre items skeleton
                LibrarySkeleton()

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
