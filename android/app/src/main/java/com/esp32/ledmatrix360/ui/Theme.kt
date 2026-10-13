package com.esp32.ledmatrix360.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material You (Material Design 3) Color Schemes
 * Supports dynamic Monet theming on Android 12+ (API 31+) with fallback
 * to curated M3 tonal palettes for dark & light modes.
 */
private val M3DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DD8E6),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF90F1FF),

    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF324B4F),
    onSecondaryContainer = Color(0xFFCDE7EC),

    tertiary = Color(0xFFBAC6EA),
    onTertiary = Color(0xFF24304D),
    tertiaryContainer = Color(0xFF3B4664),
    onTertiaryContainer = Color(0xFFD9E2FF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),

    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A)
)

private val M3LightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF90F1FF),
    onPrimaryContainer = Color(0xFF001F24),

    secondary = Color(0xFF4A6267),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EC),
    onSecondaryContainer = Color(0xFF051F23),

    tertiary = Color(0xFF525E7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E2FF),
    onTertiaryContainer = Color(0xFF0F1B36),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF5FAFB),
    onBackground = Color(0xFF171D1E),
    surface = Color(0xFFF5FAFB),
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDBE4E6),
    onSurfaceVariant = Color(0xFF3F484A),

    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBFC8CA)
)

// Material 3 semantic accent colors
val AmberWarm = Color(0xFFFF9800)
val GreenJade = Color(0xFF10B981)
val PinkCyber = Color(0xFFE91E63)

// Fallback compatibility bridges
val BgDark @Composable get() = MaterialTheme.colorScheme.background
val CardBg @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val CardBorder @Composable get() = MaterialTheme.colorScheme.outlineVariant
val CyanNeon @Composable get() = MaterialTheme.colorScheme.primary
val BlueAccent @Composable get() = MaterialTheme.colorScheme.secondary
val TextLight @Composable get() = MaterialTheme.colorScheme.onSurface
val TextMuted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val DarkSurfaceElevated @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
fun LampTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> M3DarkColorScheme
        else -> M3LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28)
        ),
        content = content
    )
}
