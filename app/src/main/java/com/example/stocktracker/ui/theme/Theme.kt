package com.example.stocktracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 中国股市习惯：红涨绿跌
val UpColor = Color(0xFFE53935)
val DownColor = Color(0xFF43A047)
val FlatColor = Color(0xFF9E9E9E)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF26A69A),
    background = Color(0xFFF5F6F8),
    surface = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82B1FF),
    background = Color(0xFF101012),
    surface = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5)
)

@Composable
fun StockTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

/** 根据盈亏返回颜色 */
@Composable
fun pnlColor(v: Double): Color = when {
    v > 0.0001 -> UpColor
    v < -0.0001 -> DownColor
    else -> FlatColor
}
