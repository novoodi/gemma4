package com.navoodi.morimi.ui.theme

import androidx.compose.ui.graphics.Color

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)

// ─────────────────────────────────────────────────────────────
// 레거시 팔레트 (Untitled UI 세대) — 화면 마이그레이션 완료 전까지 유지.
// 신규 코드는 아래 StoryMind 토큰(MoColors)을 사용할 것.
// ─────────────────────────────────────────────────────────────
val Gray25  = Color(0xFFFCFCFD)
val Gray50  = Color(0xFFF9FAFB)
val Gray100 = Color(0xFFF2F4F7)
val Gray200 = Color(0xFFEAECF0)
val Gray300 = Color(0xFFD0D5DD)
val Gray400 = Color(0xFF98A2B3)
val Gray500 = Color(0xFF667085)
val Gray600 = Color(0xFF475467)
val Gray700 = Color(0xFF344054)
val Gray800 = Color(0xFF1D2939)
val Gray900 = Color(0xFF101828)

val Blue50  = Color(0xFFEFF8FF)
val Blue100 = Color(0xFFD1E9FF)
val Blue300 = Color(0xFF84CAFF)
val Blue500 = Color(0xFF2E90FA)
val Blue600 = Color(0xFF1570EF)
val Blue700 = Color(0xFF175CD3)

val Success50  = Color(0xFFECFDF3)
val Success600 = Color(0xFF039855)

val Error50  = Color(0xFFFEF3F2)
val Error600 = Color(0xFFD92D20)

val Warning50  = Color(0xFFFFFAEB)
val Warning600 = Color(0xFFDC6803)

val Purple50  = Color(0xFFF9F5FF)
val Purple600 = Color(0xFF7F56D9)

// ─────────────────────────────────────────────────────────────
// StoryMind (Toss) 팔레트 — 원시 색상
// ─────────────────────────────────────────────────────────────

// Brand Blue (Toss Blue)
val MoBlue50  = Color(0xFFEBF0FF)
val MoBlue100 = Color(0xFFD6E2FF)
val MoBlue200 = Color(0xFFADCDFF)
val MoBlue400 = Color(0xFF4D79F8)
val MoBlue500 = Color(0xFF1F4EF5)   // brand main
val MoBlue600 = Color(0xFF1940D4)
val MoBlue700 = Color(0xFF1533A8)

// Blue-tinted neutrals — 순수 검정(#000) 금지
val MoNeutral50  = Color(0xFFF8F9FC)
val MoNeutral100 = Color(0xFFF2F4F9)
val MoNeutral200 = Color(0xFFE5E7EF)
val MoNeutral300 = Color(0xFFC8CBDA)
val MoNeutral400 = Color(0xFF9EA3B3)
val MoNeutral500 = Color(0xFF6B7089)
val MoNeutral600 = Color(0xFF5C5F6B)
val MoNeutral700 = Color(0xFF3D404D)
val MoNeutral800 = Color(0xFF2D2F36)
val MoNeutral900 = Color(0xFF22242C)
val MoNeutral950 = Color(0xFF1A1B1E)  // primary text

// Warning / Error (파스텔 — 공격적 빨강 금지)
val MoRed50  = Color(0xFFFFF0F0)
val MoRed200 = Color(0xFFFFA8A8)
val MoRed400 = Color(0xFFF87171)
val MoRed500 = Color(0xFFEF4444)

// Success / 장소 노드
val MoGreen50  = Color(0xFFF0FFF4)
val MoGreen500 = Color(0xFF22C55E)
val MoGreen600 = Color(0xFF16A34A)

// 소품 노드
val MoAmber50  = Color(0xFFFFFBEB)
val MoAmber500 = Color(0xFFF59E0B)
val MoAmber600 = Color(0xFFD97706)

// 활동 노드
val MoPurple50  = Color(0xFFF5F3FF)
val MoPurple500 = Color(0xFF8B5CF6)
val MoPurple600 = Color(0xFF7C3AED)

// ─────────────────────────────────────────────────────────────
// 시맨틱 토큰 — 화면/컴포넌트는 이것만 참조 (다크 대비 구조)
// ─────────────────────────────────────────────────────────────
object MoColors {
    // Brand
    val brand        = MoBlue500
    val brandHover   = MoBlue600
    val brandActive  = MoBlue700
    val brandSubtle  = MoBlue50

    // Text
    val textPrimary   = MoNeutral950
    val textSecondary = MoNeutral600
    val textTertiary  = MoNeutral400
    val textDisabled  = MoNeutral300
    val textOnBrand   = White

    // Surface
    val surfaceBase   = White
    val surfaceSubtle = MoNeutral50
    val surfaceCard   = White

    // Border
    val border       = MoNeutral200
    val borderStrong = MoNeutral300

    // Warning
    val warningBg     = MoRed50
    val warningText   = MoRed500
    val warningBorder = MoRed200

    // 도메인/노드 색상: 참가자=Blue · 장소=Green · 준비물=Amber · 활동=Purple · 미확정=Red
    val participant   = MoBlue500;   val participantBg = MoBlue50
    val place         = MoGreen600;  val placeBg       = MoGreen50
    val item          = MoAmber500;  val itemBg        = MoAmber50
    val activity      = MoPurple500; val activityBg    = MoPurple50
    val warn          = MoRed500;    val warnBg        = MoRed50

    // 그림자 기반색 (blue-tinted dark)
    val shadow        = Color(0xFF1A1B1E)
}
