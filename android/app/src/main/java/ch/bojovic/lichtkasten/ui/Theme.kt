package ch.bojovic.lichtkasten.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFE8D5A3),
    onPrimary = Color(0xFF1A1712),
    secondary = Color(0xFFC9B27A),
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1E1C18),
    onSurfaceVariant = Color(0xFFBDB7A8),
    error = Color(0xFFFF6E6E),
)

@Composable
fun LichtkastenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
