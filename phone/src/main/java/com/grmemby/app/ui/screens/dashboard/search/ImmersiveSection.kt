package com.grmemby.app.ui.screens.dashboard.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImmersiveSection(
    title: String,
    tags: List<String>,
    isLoading: Boolean,
    backgroundColor: Color = Color(0xFF2C3650),
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val topContentPadding = if (title.isBlank()) 68.dp else 88.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = topContentPadding, bottom = 120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isLoading) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val suggestions = remember(tags) {
                    tags
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                        .take(28)
                }
                if (suggestions.isEmpty()) {
                    Text(
                        text = "暂无推荐",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val gap = 10.dp
                        val maxChipWidth = if (maxWidth >= 520.dp) 180.dp else 166.dp
                        val minChipWidth = if (maxWidth >= 360.dp) 78.dp else 68.dp
                        val visibleSuggestions = suggestions.take(if (maxWidth >= 520.dp) 30 else 24)

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.Start),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            visibleSuggestions.forEach { tag ->
                                val chipShape = RoundedCornerShape(18.dp)
                                Surface(
                                    modifier = Modifier
                                        .widthIn(min = minChipWidth, max = maxChipWidth)
                                        .clip(chipShape)
                                        .clickable { onTagClick(tag) },
                                    shape = chipShape,
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                                ) {
                                    Text(
                                        text = tag,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
