package com.grmemby.app.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grmemby.app.ui.components.glass.GlassTokens
import com.grmemby.shared.ui.theme.JellyBlue

@Composable
fun AmoledDialogFrame(
    dismissOnRequest: Boolean,
    onDismiss: () -> Unit,
    topPadding: Dp = 0.dp,
    lightGlass: Boolean = false,
    content: @Composable () -> Unit
) {
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val sheetInteractionSource = remember { MutableInteractionSource() }
    val scrimColor = if (lightGlass) Color.Black.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.30f)
    val surfaceColor = if (lightGlass) GlassTokens.glassBackgroundLight else GlassTokens.glassBackgroundDark
    val borderColor = if (lightGlass) Color.White.copy(alpha = 0.82f) else GlassTokens.glassBorder
    val haloColors = if (lightGlass) {
        listOf(Color(0x88F9FBFF), Color(0x553E9CFF), Color.Transparent)
    } else {
        listOf(Color(0x5538BDF8), Color(0x33258BFF), Color.Transparent)
    }
    val contentGradient = if (lightGlass) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFDFEFF).copy(alpha = 0.92f),
                Color(0xFFF1F6FF).copy(alpha = 0.84f),
                Color(0xFFE6EEF8).copy(alpha = 0.76f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF2D3850).copy(alpha = 0.86f),
                Color(0xFF172033).copy(alpha = 0.82f),
                Color(0xFF0B1020).copy(alpha = 0.78f)
            )
        )
    }

    Dialog(
        onDismissRequest = {
            if (dismissOnRequest) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnRequest,
            dismissOnClickOutside = dismissOnRequest,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .clickable(
                    enabled = dismissOnRequest,
                    indication = null,
                    interactionSource = scrimInteractionSource
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = topPadding)
                    .widthIn(max = 880.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .blur(GlassTokens.blurAmount)
                        .background(
                            brush = Brush.radialGradient(
                                colors = haloColors
                            ),
                            shape = RoundedCornerShape(GlassTokens.popupRadius + 12.dp)
                        )
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = sheetInteractionSource
                        ) {},
                    shape = RoundedCornerShape(GlassTokens.popupRadius + 6.dp),
                    color = surfaceColor,
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = contentGradient
                            )
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun amoledAuthFieldColors(
    hasLeadingIcon: Boolean = false
) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = JellyBlue,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
    focusedBorderColor = JellyBlue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.26f),
    focusedLeadingIconColor = if (hasLeadingIcon) JellyBlue else Color.Unspecified,
    unfocusedLeadingIconColor = if (hasLeadingIcon) Color.White.copy(alpha = 0.65f) else Color.Unspecified,
    cursorColor = JellyBlue,
    selectionColors = TextSelectionColors(
        handleColor = JellyBlue,
        backgroundColor = JellyBlue.copy(alpha = 0.28f)
    )
)
