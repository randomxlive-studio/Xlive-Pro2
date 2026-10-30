package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val StudioColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = StudioVoid,
    primaryContainer = NeonCyanDim,
    onPrimaryContainer = StudioTextPrimary,
    secondary = NeonPurple,
    onSecondary = StudioTextPrimary,
    secondaryContainer = StudioCardBg,
    onSecondaryContainer = StudioLabelAccent,
    tertiary = NeonPink,
    onTertiary = StudioVoid,
    background = StudioVoid,
    onBackground = StudioTextPrimary,
    surface = StudioDeepNavy,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioCardBg,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorderBlue,
    error = LivePulseRed
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(
            colorScheme = StudioColorScheme,
            typography = Typography,
            shapes = StudioShapes,
            content = content
        )
    }
}
