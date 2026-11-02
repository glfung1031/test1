package com.macrorunner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val Light = lightColorScheme(
    primary = Color(0xFF4A55E8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF), onPrimaryContainer = Color(0xFF0A1070),
    secondary = Color(0xFF0E8F7E), secondaryContainer = Color(0xFFCFF5EE), onSecondaryContainer = Color(0xFF00302A),
    tertiaryContainer = Color(0xFFFFE9C7), onTertiaryContainer = Color(0xFF3D2800),
    background = Color(0xFFF6F6FB), surface = Color(0xFFF6F6FB), onSurface = Color(0xFF16171F),
    surfaceVariant = Color(0xFFE4E5EF), onSurfaceVariant = Color(0xFF50525F),
    surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFECEDF6)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9AA2FF), onPrimary = Color(0xFF0E1470),
    primaryContainer = Color(0xFF262C8A), onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = Color(0xFF5FD9C4), secondaryContainer = Color(0xFF00504A), onSecondaryContainer = Color(0xFFCFF5EE),
    tertiaryContainer = Color(0xFF5A3F00), onTertiaryContainer = Color(0xFFFFE9C7),
    background = Color(0xFF101116), surface = Color(0xFF101116), onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF2A2C38), onSurfaceVariant = Color(0xFFC3C4D2),
    surfaceContainerLow = Color(0xFF181A22), surfaceContainerHigh = Color(0xFF22242E)
)

@Composable
fun MacroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
