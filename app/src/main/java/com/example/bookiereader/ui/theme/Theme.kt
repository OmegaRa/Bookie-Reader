package com.example.bookiereader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BookieAccent,
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = BookieDarkBackground,
    surface = BookieDarkSurface,
    surfaceVariant = BookieDarkSurface,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.7f)
)

private val LightColorScheme = lightColorScheme(
    primary = BookieAccent,
    secondary = Color(0xFF625b71),
    tertiary = Color(0xFF7D5260),
    background = BookieLightBackground,
    surface = BookieLightSurface,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun BookieReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}