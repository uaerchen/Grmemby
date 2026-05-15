package com.grmemby.app.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

object GlassTokens {
    val glassBackgroundLight: Color = Color(0xD8F4F8FF)
    val glassBackgroundDark: Color = Color(0xE21B2232)
    val glassBorder: Color = Color.White.copy(alpha = 0.18f)
    val glassShadow: Color = Color.Black.copy(alpha = 0.30f)
    val selectedBlue: Color = Color(0xFF78B7FF)
    val primaryText: Color = Color.White.copy(alpha = 0.98f)
    val secondaryText: Color = Color.White.copy(alpha = 0.78f)
    val popupRadius = 24.dp
    val navBarRadius = 30.dp
    val blurAmount = 96.dp
}

internal fun blendGlassColor(base: Color, overlay: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1f - t) + overlay.red * t,
        green = base.green * (1f - t) + overlay.green * t,
        blue = base.blue * (1f - t) + overlay.blue * t,
        alpha = 1f
    )
}

internal fun dynamicGlassBrush(
    surfaceColor: Color?,
    dark: Boolean,
    topAlpha: Float = if (dark) 0.88f else 0.86f,
    bottomAlpha: Float = if (dark) 0.82f else 0.76f
): Brush {
    if (surfaceColor == null) return if (dark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF283044).copy(alpha = topAlpha),
                Color(0xFF1D2536).copy(alpha = (topAlpha + bottomAlpha) / 2f),
                Color(0xFF151B2A).copy(alpha = bottomAlpha)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF7FAFF).copy(alpha = topAlpha),
                Color(0xFFE9F1FB).copy(alpha = (topAlpha + bottomAlpha) / 2f),
                Color(0xFFDCE8F4).copy(alpha = bottomAlpha)
            )
        )
    }

    val base = if (dark) blendGlassColor(surfaceColor, Color.Black, 0.28f) else blendGlassColor(surfaceColor, Color.White, 0.58f)
    return Brush.verticalGradient(
        colors = listOf(
            blendGlassColor(base, if (dark) Color(0xFF5F7EA9) else Color.White, if (dark) 0.18f else 0.22f).copy(alpha = topAlpha),
            blendGlassColor(base, if (dark) Color(0xFF263954) else Color(0xFFEAF3FF), if (dark) 0.10f else 0.16f).copy(alpha = (topAlpha + bottomAlpha) / 2f),
            blendGlassColor(base, if (dark) Color.Black else Color(0xFFD2DFEF), if (dark) 0.16f else 0.12f).copy(alpha = bottomAlpha)
        )
    )
}

internal fun dynamicGlassBorder(surfaceColor: Color?, dark: Boolean): Color {
    return if (dark) {
        blendGlassColor(surfaceColor ?: Color(0xFF24334D), Color.White, 0.42f).copy(alpha = 0.20f)
    } else {
        Color.White.copy(alpha = 0.62f)
    }
}

val GlassPillShape = RoundedCornerShape(28.dp)
val GlassMenuShape: RoundedCornerShape get() = RoundedCornerShape(GlassTokens.popupRadius)
val GlassOptionShape = RoundedCornerShape(16.dp)

val GlassLightGradient: Brush
    @Composable get() = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFDFEFF).copy(alpha = 0.68f),
            Color(0xFFF0F6FF).copy(alpha = 0.54f),
            Color(0xFFD6E1EE).copy(alpha = 0.42f)
        )
    )

val GlassDarkGradient: Brush
    @Composable get() = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF30313A).copy(alpha = 0.78f),
            Color(0xFF1F2028).copy(alpha = 0.74f),
            Color(0xFF14151C).copy(alpha = 0.70f)
        )
    )

val GlassSelectedBlue: Color = GlassTokens.selectedBlue

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    minWidth: androidx.compose.ui.unit.Dp = 172.dp,
    dark: Boolean = true,
    surfaceColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = GlassMenuShape
    val menuBrush = dynamicGlassBrush(
        surfaceColor = surfaceColor,
        dark = dark,
        topAlpha = if (dark) 0.90f else 0.88f,
        bottomAlpha = if (dark) 0.84f else 0.80f
    )
    val menuBorder = dynamicGlassBorder(surfaceColor, dark)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        modifier = modifier
            .widthIn(min = minWidth)
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = GlassTokens.glassShadow,
                spotColor = Color.Black.copy(alpha = 0.30f)
            )
            .clip(shape)
            .background(menuBrush)
            .border(
                1.dp,
                menuBorder,
                shape
            )
            .padding(horizontal = 5.dp, vertical = 6.dp),
        properties = PopupProperties(focusable = true),
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}

@Composable
fun GlassDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    dark: Boolean = true,
    trailingSelectedCheck: Boolean = true,
    surfaceColor: Color? = null
) {
    val dynamicBase = surfaceColor ?: if (dark) Color(0xFF22324A) else Color(0xFFEAF4FF)
    val selectedTint = surfaceColor?.let { blendGlassColor(it, Color.White, 0.62f) }
        ?: if (dark) Color(0xFF8CC8FF) else Color(0xFF177DDC)
    val selectedMix = surfaceColor?.let { blendGlassColor(it, Color.White, 0.38f) } ?: GlassTokens.selectedBlue
    val textColor = when {
        !enabled -> if (dark) GlassTokens.secondaryText.copy(alpha = 0.46f) else Color(0xFF111827).copy(alpha = 0.38f)
        selected -> selectedTint
        dark -> GlassTokens.primaryText
        else -> Color(0xFF111827)
    }
    val secondaryColor = if (dark) Color.White.copy(alpha = 0.80f) else Color(0xFF111827).copy(alpha = 0.62f)
    val rowBackground = if (selected) {
        Brush.horizontalGradient(
            colors = listOf(
                blendGlassColor(dynamicBase, selectedMix, 0.36f).copy(alpha = if (dark) 0.22f else 0.18f),
                blendGlassColor(dynamicBase, selectedMix, 0.18f).copy(alpha = if (dark) 0.12f else 0.09f),
                blendGlassColor(dynamicBase, Color.White, 0.04f).copy(alpha = if (dark) 0.06f else 0.05f)
            )
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(
                blendGlassColor(dynamicBase, Color.White, if (dark) 0.08f else 0.18f).copy(alpha = if (dark) 0.035f else 0.055f),
                Color.Transparent
            )
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlassOptionShape)
            .background(rowBackground)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (selected) selectedTint else secondaryColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailingSelectedCheck && selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = selectedTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun GlassOptionContainer(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dark: Boolean = true,
    surfaceColor: Color? = null,
    content: @Composable () -> Unit
) {
    val dynamicBase = surfaceColor ?: if (dark) Color(0xFF22324A) else Color(0xFFEAF4FF)
    val selectedMix = surfaceColor?.let { blendGlassColor(it, Color.White, 0.38f) } ?: GlassTokens.selectedBlue
    Box(
        modifier = modifier
            .clip(GlassOptionShape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(
                        listOf(
                            blendGlassColor(dynamicBase, selectedMix, 0.34f).copy(alpha = if (dark) 0.20f else 0.16f),
                            blendGlassColor(dynamicBase, Color.White, if (dark) 0.12f else 0.24f).copy(alpha = if (dark) 0.07f else 0.10f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            blendGlassColor(dynamicBase, Color.White, if (dark) 0.08f else 0.18f).copy(alpha = if (dark) 0.04f else 0.06f),
                            Color.Transparent
                        )
                    )
                }
            ),
        content = { content() }
    )
}
