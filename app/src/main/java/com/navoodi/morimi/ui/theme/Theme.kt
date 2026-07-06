package com.navoodi.morimi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

// StoryMind(Toss) 라이트 스킴 — 시맨틱 토큰(MoColors) 기반. 다크는 추후 단계.
private val MoLightColorScheme = lightColorScheme(
    primary              = MoColors.brand,
    onPrimary            = MoColors.textOnBrand,
    primaryContainer     = MoColors.brandSubtle,
    onPrimaryContainer   = MoColors.brandActive,
    secondary            = MoColors.textSecondary,
    onSecondary          = White,
    secondaryContainer   = MoNeutral100,
    onSecondaryContainer = MoNeutral800,
    background            = MoColors.surfaceBase,
    onBackground          = MoColors.textPrimary,
    surface               = MoColors.surfaceBase,
    onSurface             = MoColors.textPrimary,
    surfaceVariant        = MoColors.surfaceSubtle,
    onSurfaceVariant      = MoColors.textSecondary,
    outline               = MoColors.border,
    outlineVariant        = MoNeutral100,
    error                 = MoColors.warningText,
    onError               = White,
    errorContainer        = MoColors.warningBg,
    onErrorContainer      = MoColors.warningText,
)

// Radius 스케일 — 컴포넌트가 MaterialTheme.shapes로 참조 가능
private val MoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(6.dp),
    medium     = RoundedCornerShape(10.dp),
    large      = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun Gemma4Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MoLightColorScheme,
        typography  = Typography,
        shapes      = MoShapes,
    ) {
        // 전역 기본 폰트 = Pretendard. style 미지정 Text도 자동으로 Pretendard + 음수 자간 적용.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = Pretendard,
                letterSpacing = (-0.01).em,
            ),
            content = content,
        )
    }
}
