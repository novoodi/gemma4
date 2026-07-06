package com.navoodi.morimi.ui.screen.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navoodi.morimi.ui.theme.*

private data class Slide(val title: String, val sub: String)

private val slides = listOf(
    Slide("모임 조율,\n한 번에 끝",   "흩어진 대화 속 조건들을 모아\n최적의 스케줄을 제안합니다."),
    Slide("대화 유출 없는\n안심 AI",   "기기 내부에서만 처리되는 '온디바이스 시스템'으로\n사적인 대화 원문은 서버 전송 없이 즉시 파기합니다."),
    Slide("내 조건을\n기억하는 비서",  "대화 중에 툭 던진 내 제약 조건들을\nAI가 소실 없이 기억합니다."),
    Slide("팩트체크로\n확실한 일정",   "AI가 짠 초안을 실시간 지도와\n날씨 데이터로 즉각 팩트체크합니다."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val total = slides.size
    val isLast = idx == total - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(White)
            .statusBarsPadding()
            .pointerInput(idx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccum < -60 && idx < total - 1) idx++
                        if (dragAccum > 60 && idx > 0) idx--
                        dragAccum = 0f
                    },
                    onHorizontalDrag = { _, delta -> dragAccum += delta }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(56.dp))

            // Skip
            Box(modifier = Modifier.fillMaxWidth().padding(end = 28.dp), contentAlignment = Alignment.CenterEnd) {
                Text("건너뛰기", color = MoColors.textTertiary, fontSize = 14.sp,
                    modifier = Modifier.clickable { onDone() })
            }

            Spacer(Modifier.height(120.dp))

            // Title
            Text(
                text = slides[idx].title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MoColors.textPrimary,
                lineHeight = 34.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Sub
            Text(
                text = slides[idx].sub,
                fontSize = 14.sp,
                color = MoColors.textSecondary,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.weight(1f))

            // Dots
            Row(
                modifier = Modifier.padding(start = 32.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(total) { i ->
                    val w by animateDpAsState(
                        targetValue = if (i == idx) 24.dp else 8.dp,
                        animationSpec = tween(300), label = "dot"
                    )
                    Box(
                        modifier = Modifier
                            .width(w).height(8.dp)
                            .clip(CircleShape)
                            .background(if (i == idx) MoColors.brand else MoColors.borderStrong)
                            .clickable { idx = i }
                    )
                }
            }

            // CTA
            Button(
                onClick = { if (isLast) onDone() else idx++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 40.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoColors.brand)
            ) {
                Text(
                    if (isLast) "시작하기" else "다음",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}