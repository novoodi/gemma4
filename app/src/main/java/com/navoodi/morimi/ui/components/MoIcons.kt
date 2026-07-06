package com.navoodi.morimi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.navoodi.morimi.ui.theme.MoBlue500
import com.navoodi.morimi.ui.theme.MoGreen600
import com.navoodi.morimi.ui.theme.MoAmber500
import com.navoodi.morimi.ui.theme.MoPurple500
import com.navoodi.morimi.ui.theme.MoRed500

// Gemini 스파크 아이콘 — chat/ailoading/aireport 중복 정의를 대체하는 단일 소스
val GeminiIcon: ImageVector = ImageVector.Builder(
    name = "Gemini",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(11.5f, 7.8f, 7.8f, 11.5f, 2f, 12f)
    curveTo(7.8f, 12.5f, 11.5f, 16.2f, 12f, 22f)
    curveTo(12.5f, 16.2f, 16.2f, 12.5f, 22f, 12f)
    curveTo(16.2f, 11.5f, 12.5f, 7.8f, 12f, 2f)
    close()
}.build()

// 아바타 배경색 — 이름 해시로 도메인 팔레트에서 결정론적으로 선택
private val avatarPalette = listOf(MoBlue500, MoGreen600, MoAmber500, MoPurple500, MoRed500)

fun avatarColorFor(seed: String): Color =
    avatarPalette[(seed.hashCode() and 0x7fffffff) % avatarPalette.size]
