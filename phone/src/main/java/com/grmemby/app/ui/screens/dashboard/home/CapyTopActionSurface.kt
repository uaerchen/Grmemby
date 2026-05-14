package com.grmemby.app.ui.screens.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Top hero action chips use the same compact dark-glass vocabulary as the
 * CapyPlayer-style bottom navigation bar, while their dropdown/menu content
 * keeps its original styling.
 */
internal fun Modifier.capyTopActionSurface(
    shape: RoundedCornerShape = RoundedCornerShape(100.dp)
): Modifier = this
    .shadow(
        elevation = 16.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.26f)
    )
    .clip(shape)
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
        shape = shape
    )
