package com.navoodi.morimi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TalkPlusColorScheme = lightColorScheme(
    primary            = Blue600,
    onPrimary          = White,
    primaryContainer   = Blue50,
    onPrimaryContainer = Blue700,
    secondary          = Gray600,
    onSecondary        = White,
    secondaryContainer = Gray100,
    onSecondaryContainer = Gray800,
    background         = White,
    onBackground       = Gray900,
    surface            = White,
    onSurface          = Gray900,
    surfaceVariant     = Gray50,
    onSurfaceVariant   = Gray600,
    outline            = Gray200,
    outlineVariant     = Gray100,
    error              = Error600,
    onError            = White,
    errorContainer     = Error50,
)

@Composable
fun Gemma4Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TalkPlusColorScheme,
        typography  = Typography,
        content     = content,
    )
}