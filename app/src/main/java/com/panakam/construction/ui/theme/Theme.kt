package com.panakam.construction.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary              = Blue40,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFD6E4FF),
    onPrimaryContainer   = Blue10,
    secondary            = SkyBlue40,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFCDE5FF),
    onSecondaryContainer = Color(0xFF001E2F),
    tertiary             = Teal40,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFB2DFDB),
    onTertiaryContainer  = Color(0xFF00201D),
    background           = BackgroundLight,
    onBackground         = Color(0xFF0D0D0D),
    surface              = SurfaceLight,
    onSurface            = Color(0xFF0D0D0D),
    surfaceVariant       = Color(0xFFDEE3F3),
    onSurfaceVariant     = Color(0xFF333333),
    error                = Color(0xFFB3261E),
    onError              = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary              = Blue80,
    onPrimary            = Blue20,
    primaryContainer     = BlueGrey40,
    onPrimaryContainer   = Blue80,
    secondary            = SkyBlue80,
    onSecondary          = Color(0xFF001E2F),
    secondaryContainer   = Color(0xFF004C6E),
    onSecondaryContainer = SkyBlue80,
    tertiary             = Teal80,
    onTertiary           = Color(0xFF003733),
    tertiaryContainer    = Color(0xFF005049),
    onTertiaryContainer  = Teal80,
    background           = Color(0xFF1A1C1E),
    surface              = Color(0xFF1A1C1E),
)

@Composable
fun ConstructionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Dynamic color intentionally disabled — keep consistent blue brand theme
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = (if (darkTheme) Blue20 else GradientTop).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}