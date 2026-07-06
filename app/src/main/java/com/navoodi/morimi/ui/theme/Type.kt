package com.navoodi.morimi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.R

// Pretendard — 한국어 최적화 기하학적 산세리프 (OFL)
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular,  FontWeight.Normal),
    Font(R.font.pretendard_medium,   FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold,     FontWeight.Bold),
)

// 음수 자간 — Toss 질감의 핵심. 대형 디스플레이 -0.03em, 일반 UI/본문 -0.01em.
private val TrackTight  = (-0.03).em
private val TrackNormal = (-0.01).em

val Typography = Typography(
    // Display / Hero (대시보드 헤드라인, 챕터 제목)
    displayLarge = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Bold,
        fontSize = 38.sp, lineHeight = 46.sp, letterSpacing = TrackTight,
    ),
    displayMedium = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = TrackTight,
    ),
    // Headline (페이지 제목)
    headlineLarge = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = TrackTight,
    ),
    headlineMedium = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = TrackNormal,
    ),
    // Title (섹션 헤더, 카드 제목)
    titleLarge = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = TrackNormal,
    ),
    titleMedium = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = TrackNormal,
    ),
    titleSmall = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = TrackNormal,
    ),
    // Body
    bodyLarge = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = TrackNormal,
    ),
    bodyMedium = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = TrackNormal,
    ),
    bodySmall = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = TrackNormal,
    ),
    // Label (버튼, UI 레이블, 캡션)
    labelLarge = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = TrackNormal,
    ),
    labelMedium = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = TrackNormal,
    ),
    labelSmall = TextStyle(
        fontFamily = Pretendard, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = TrackNormal,
    ),
)
