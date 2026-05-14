package com.grmemby.app.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

internal data class DetailDynamicColors(
    val surface: Color,
    val midSurface: Color,
    val deepSurface: Color,
    val accent: Color,
    val accentText: Color,
    val glass: Color,
    val glassSoft: Color,
    val border: Color,
    val secondaryText: Color
)

internal fun detailBlendColor(base: Color, overlay: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1f - t) + overlay.red * t,
        green = base.green * (1f - t) + overlay.green * t,
        blue = base.blue * (1f - t) + overlay.blue * t,
        alpha = 1f
    )
}

@Composable
internal fun rememberDetailDynamicColors(surfaceColor: Color): DetailDynamicColors {
    return remember(surfaceColor) {
        DetailDynamicColors(
            surface = surfaceColor,
            midSurface = detailBlendColor(surfaceColor, Color.Black, 0.10f),
            deepSurface = detailBlendColor(surfaceColor, Color.Black, 0.24f),
            accent = detailBlendColor(surfaceColor, Color.White, 0.58f),
            accentText = Color.White.copy(alpha = 0.96f),
            glass = detailBlendColor(surfaceColor, Color.White, 0.12f).copy(alpha = 0.34f),
            glassSoft = detailBlendColor(surfaceColor, Color.White, 0.18f).copy(alpha = 0.24f),
            border = detailBlendColor(surfaceColor, Color.White, 0.46f).copy(alpha = 0.22f),
            secondaryText = detailBlendColor(surfaceColor, Color.White, 0.68f).copy(alpha = 0.84f)
        )
    }
}
