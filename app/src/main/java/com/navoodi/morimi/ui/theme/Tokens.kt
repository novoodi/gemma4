package com.navoodi.morimi.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// ── 간격 (4px 그리드) ──
object MoSpacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x12 = 48.dp
    val x16 = 64.dp

    // 화면 패딩 (문맥 의존)
    val screenOpen = 15.dp     // 대시보드 / 리스트 — 넓고 여유롭게
    val screenFocused = 22.dp  // 폼 / 집중형
}

// ── 모서리 반경 ──
object MoRadius {
    val xs = RoundedCornerShape(4.dp)
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(10.dp)
    val lg = RoundedCornerShape(14.dp)   // 카드 기본
    val xl = RoundedCornerShape(18.dp)   // 바텀시트 상단
    val xl2 = RoundedCornerShape(24.dp)
    val full = RoundedCornerShape(9999.dp)
}

// ── 모션 ──
object MoMotion {
    const val instant = 80
    const val fast = 150     // 버튼 press, hover
    const val normal = 200   // shake
    const val slow = 280     // 드로어 / 바텀시트
    const val deliberate = 350

    const val pressScale = 0.97f

    val easeStandard   = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val easeDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)   // 진입
    val easeAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)   // 이탈
    val easeBounce     = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)  // 스프링
}

// ── 카드 표면: 얕은 blue-tinted 그림자 + 1px 헤어라인 링 + 흰 배경 ──
// StoryMind shadow-card: 0 1px 4px rgba(26,27,30,.06) + 0 0 0 1px rgba(26,27,30,.05)
fun Modifier.moCardSurface(shape: Shape = MoRadius.lg): Modifier =
    this
        .shadow(
            elevation = 1.dp,
            shape = shape,
            spotColor = MoColors.shadow,
            ambientColor = MoColors.shadow,
        )
        .background(MoColors.surfaceCard, shape)
        .border(1.dp, MoColors.shadow.copy(alpha = 0.05f), shape)
