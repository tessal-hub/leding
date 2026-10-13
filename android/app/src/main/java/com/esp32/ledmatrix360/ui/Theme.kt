package com.esp32.ledmatrix360.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Signature Cyber Luminescence Palette
val BgDark = Color(0xFF090D16)
val CardBg = Color(0xFF141B2D)
val CardBorder = Color(0x1FFFFFFF)
val CyanNeon = Color(0xFF00F2FE)
val BlueAccent = Color(0xFF4FACFE)
val AmberWarm = Color(0xFFFF9900)
val GreenJade = Color(0xFF10B981)
val PinkCyber = Color(0xFFFF007F)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val DarkSurfaceElevated = Color(0xFF1A233A)

private val SignatureDarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = Color(0xFF051329),
    primaryContainer = Color(0xFF00363A),
    onPrimaryContainer = CyanNeon,
    secondary = BlueAccent,
    onSecondary = Color(0xFF041E3A),
    secondaryContainer = Color(0xFF112D4E),
    onSecondaryContainer = BlueAccent,
    tertiary = PinkCyber,
    onTertiary = Color(0xFF380016),
    background = BgDark,
    onBackground = TextLight,
    surface = CardBg,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    outlineVariant = Color(0x3300F2FE),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun LampTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(context)
        }
        else -> SignatureDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
