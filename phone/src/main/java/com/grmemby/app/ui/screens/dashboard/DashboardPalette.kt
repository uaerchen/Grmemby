package com.grmemby.app.ui.screens.dashboard

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DashboardPalette {
    private val defaultSurface = Color(0xFF2C3650)
    private val defaultBottomBarColors = listOf(
        Color(0xFF22304A),
        Color(0xFF2C3650),
        Color(0xFF1E293F)
    )
    private val _surfaceColor = MutableStateFlow(defaultSurface)
    private val _bottomBarColors = MutableStateFlow(defaultBottomBarColors)
    val surfaceColor: StateFlow<Color> = _surfaceColor.asStateFlow()
    val bottomBarColors: StateFlow<List<Color>> = _bottomBarColors.asStateFlow()

    fun updateSurface(color: Color) {
        _surfaceColor.value = color
    }

    fun updateBottomBarColors(colors: List<Color>) {
        if (colors.isEmpty()) return
        _bottomBarColors.value = colors.take(5)
    }
}
