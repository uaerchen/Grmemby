package com.grmemby.app.ui.screens.dashboard.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grmemby.app.R
import com.grmemby.app.ui.components.glass.GlassDarkGradient
import com.grmemby.app.ui.components.glass.GlassTokens
import com.grmemby.data.preferences.NetworkPreferences
import com.grmemby.player.preferences.PlayerPreferences
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    onBackPressed: () -> Unit = {},
    backgroundColor: Color = Color(0xFF2C3650)
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context) }
    val uiState by viewModel.uiState.collectAsState()
    var showCacheSizeDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_cache),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item { CacheSectionLabel("图片缓存") }

            item {
                CacheSection {
                    CacheSwitchItem(
                        icon = Icons.Rounded.Image,
                        title = stringResource(R.string.cache_settings_cache_images),
                        subtitle = stringResource(R.string.cache_settings_cache_images_summary),
                        checked = uiState.imageCachingEnabled,
                        onCheckedChange = viewModel::setImageCachingEnabled,
                        accentColor = Color(0xFF22D3EE)
                    )
                    CacheDivider()
                    CacheActionItem(
                        icon = Icons.Rounded.SdCard,
                        title = stringResource(R.string.cache_settings_cache_size),
                        subtitle = if (uiState.imageMemoryCacheMb == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB) {
                            stringResource(R.string.settings_auto)
                        } else {
                            stringResource(R.string.cache_settings_cache_size_value_mb, uiState.imageMemoryCacheMb)
                        },
                        enabled = uiState.imageCachingEnabled,
                        onClick = { showCacheSizeDialog = true },
                        accentColor = Color(0xFF60A5FA)
                    )
                }
            }

            item { CacheSectionLabel("播放器缓存") }

            item {
                CacheSection {
                    CacheSwitchItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.player_settings_disk_cache),
                        subtitle = stringResource(R.string.player_settings_disk_cache_summary),
                        checked = uiState.playerDiskCacheEnabled,
                        onCheckedChange = viewModel::setPlayerDiskCacheEnabled,
                        accentColor = Color(0xFF34D399)
                    )
                    CacheDivider()
                    CacheSwitchItem(
                        icon = Icons.Rounded.SkipNext,
                        title = stringResource(R.string.cache_settings_cache_next_episode),
                        subtitle = stringResource(R.string.cache_settings_cache_next_episode_summary),
                        checked = uiState.cacheNextEpisodeEnabled,
                        onCheckedChange = viewModel::setCacheNextEpisodeEnabled,
                        enabled = uiState.playerDiskCacheEnabled,
                        accentColor = Color(0xFFF59E0B)
                    )
                    CacheDivider()
                    CacheValueSliderItem(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(R.string.player_settings_player_cache_size),
                        subtitle = stringResource(R.string.player_settings_player_cache_size_summary),
                        value = uiState.playerCacheSizeMb,
                        defaultValue = PlayerPreferences.DEFAULT_PLAYER_CACHE_SIZE_MB,
                        minValue = PlayerPreferences.MIN_PLAYER_CACHE_SIZE_MB,
                        maxValue = PlayerPreferences.MAX_PLAYER_CACHE_SIZE_MB,
                        stepSize = PlayerPreferences.PLAYER_CACHE_SIZE_STEP_MB,
                        enabled = uiState.playerDiskCacheEnabled,
                        onValueChanged = viewModel::setPlayerCacheSizeMb,
                        valueLabel = { sizeMb -> stringResource(R.string.player_settings_player_cache_size_value, sizeMb) },
                        defaultLabel = { sizeMb ->
                            stringResource(
                                R.string.player_settings_default_value,
                                stringResource(R.string.player_settings_player_cache_size_value, sizeMb)
                            )
                        },
                        accentColor = Color(0xFF60A5FA)
                    )
                    CacheDivider()
                    CacheValueSliderItem(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.player_settings_player_cache_time),
                        subtitle = stringResource(R.string.player_settings_player_cache_time_summary),
                        value = uiState.playerCacheTimeSeconds,
                        defaultValue = PlayerPreferences.DEFAULT_PLAYER_CACHE_TIME_SECONDS,
                        minValue = PlayerPreferences.MIN_PLAYER_CACHE_TIME_SECONDS,
                        maxValue = PlayerPreferences.MAX_PLAYER_CACHE_TIME_SECONDS,
                        stepSize = PlayerPreferences.PLAYER_CACHE_TIME_STEP_SECONDS,
                        enabled = uiState.playerDiskCacheEnabled,
                        onValueChanged = viewModel::setPlayerCacheTimeSeconds,
                        valueLabel = { seconds -> stringResource(R.string.player_settings_player_cache_time_value, seconds) },
                        defaultLabel = { seconds ->
                            stringResource(
                                R.string.player_settings_default_value,
                                stringResource(R.string.player_settings_player_cache_time_value, seconds)
                            )
                        },
                        accentColor = Color(0xFFA78BFA)
                    )
                }
            }
        }
    }

    if (showCacheSizeDialog) {
        CacheSizeValueDialog(
            initialValue = uiState.imageMemoryCacheMb,
            onDismiss = { showCacheSizeDialog = false },
            onSave = { value ->
                viewModel.setImageMemoryCacheMb(value)
                showCacheSizeDialog = false
            }
        )
    }
}

@Composable
private fun CacheSectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun CacheSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun CacheDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun CacheSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    color = if (enabled) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.38f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor,
                checkedBorderColor = accentColor,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.7f),
                disabledCheckedTrackColor = accentColor.copy(alpha = 0.45f),
                disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun CacheActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    color = if (enabled) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.38f)
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.4f else 0.24f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheValueSliderItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Int,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
    stepSize: Int = 1,
    enabled: Boolean = true,
    onValueChanged: (Int) -> Unit,
    accentColor: Color,
    valueLabel: @Composable (Int) -> String,
    defaultLabel: @Composable (Int) -> String
) {
    val safeMin = minValue
    val safeMax = maxValue.coerceAtLeast(minValue + 1)
    val safeStepSize = stepSize.coerceAtLeast(1)
    val valueRange = safeMin..safeMax
    val sliderSteps = (((safeMax - safeMin) / safeStepSize) - 1).coerceAtLeast(0)
    val safeValue = value.coerceIn(valueRange.first, valueRange.last)
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.38f)
    val effectiveAccent = if (enabled) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = effectiveAccent.copy(alpha = if (enabled) 0.16f else 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = effectiveAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor
                )
            }
            Text(
                text = valueLabel(safeValue),
                style = MaterialTheme.typography.titleSmall,
                color = effectiveAccent
            )
        }

        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .graphicsLayer(scaleY = 0.76f),
            value = safeValue.toFloat(),
            onValueChange = { changed ->
                val steppedValue = (
                    ((changed - safeMin) / safeStepSize).roundToInt() * safeStepSize + safeMin
                ).coerceIn(valueRange.first, valueRange.last)
                if (enabled) onValueChanged(steppedValue)
            },
            enabled = enabled,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = sliderSteps,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = accentColor.copy(alpha = 0.25f),
                disabledThumbColor = Color.White.copy(alpha = 0.34f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.18f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.10f)
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(18.dp)
                        .background(
                            color = if (enabled) accentColor else Color.White.copy(alpha = 0.34f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = valueLabel(valueRange.first),
                style = MaterialTheme.typography.labelSmall,
                color = secondaryColor
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = defaultLabel(defaultValue),
                style = MaterialTheme.typography.labelMedium,
                color = effectiveAccent,
                modifier = Modifier.clickable(enabled = enabled) {
                    onValueChanged(defaultValue.coerceIn(valueRange.first, valueRange.last))
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueLabel(valueRange.last),
                style = MaterialTheme.typography.labelSmall,
                color = secondaryColor
            )
        }
    }
}

@Composable
private fun CacheSizeValueDialog(
    initialValue: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var textValue by remember(initialValue) {
        mutableStateOf(
            if (initialValue == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB) {
                ""
            } else {
                initialValue.toString()
            }
        )
    }
    val parsedValue = textValue.toIntOrNull()
    val valueToPersist = parsedValue ?: NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB
    val isValid = parsedValue == null || (
        parsedValue == NetworkPreferences.AUTO_IMAGE_MEMORY_CACHE_MB ||
            parsedValue in NetworkPreferences.MIN_IMAGE_MEMORY_CACHE_MB..NetworkPreferences.MAX_IMAGE_MEMORY_CACHE_MB
    )
    val hasValidationError = textValue.isNotBlank() && !isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.cache_settings_cache_size_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input.filter { it.isDigit() }.take(4)
                    },
                    label = { Text(stringResource(R.string.cache_settings_cache_size_input_label)) },
                    singleLine = true,
                    isError = hasValidationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF111827),
                        unfocusedTextColor = Color(0xFF111827),
                        focusedLabelColor = Color(0xFF258BFF),
                        unfocusedLabelColor = Color(0xFF64748B),
                        focusedBorderColor = Color(0xFF258BFF),
                        unfocusedBorderColor = Color(0xFFCBD5E1),
                        cursorColor = Color(0xFF258BFF),
                        errorBorderColor = Color(0xFFFF6B6B),
                        errorLabelColor = Color(0xFFFF6B6B),
                        errorCursorColor = Color(0xFFFF6B6B)
                    )
                )
                Text(
                    text = stringResource(
                        R.string.cache_settings_allowed_range_mb,
                        NetworkPreferences.MIN_IMAGE_MEMORY_CACHE_MB,
                        NetworkPreferences.MAX_IMAGE_MEMORY_CACHE_MB
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
                Text(
                    text = stringResource(R.string.cache_settings_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = isValid, onClick = { onSave(valueToPersist) }) {
                Text(stringResource(R.string.settings_apply), color = Color(0xFF258BFF), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color(0xFF64748B))
            }
        }
    )
}
