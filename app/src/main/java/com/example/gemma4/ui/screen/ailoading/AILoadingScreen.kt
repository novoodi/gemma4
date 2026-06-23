package com.example.gemma4.ui.screen.ailoading

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.data.repository.ChatRepository
import com.example.gemma4.navigation.Screen
import com.example.gemma4.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AILoadingViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    val summaryReady: StateFlow<Boolean> = ChatRepository.summaries
        .map { it.containsKey(roomId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // UI 애니메이션이 끝난 뒤 실제 AI 완료(or 타임아웃)되면 true
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        // UI 단계 애니메이션
        viewModelScope.launch {
            delay(600);  _step.value = 1
            delay(800);  _step.value = 2
            delay(800);  _step.value = 3
        }
        // 실제 AI 완료 대기 — step 3 이후 최대 90초
        viewModelScope.launch {
            _step.filter { it >= 3 }.first()
            withTimeoutOrNull(90_000L) {
                summaryReady.filter { it }.first()
            }
            _done.value = true
        }
    }
}

@Composable
fun AILoadingScreen(navController: NavController, viewModel: AILoadingViewModel = viewModel()) {
    val step by viewModel.step.collectAsState()
    val done by viewModel.done.collectAsState()

    LaunchedEffect(done) {
        if (done) {
            delay(400)
            navController.navigate(Screen.AIReport.createRoute(viewModel.roomId)) {
                popUpTo(Screen.Chat.createRoute(viewModel.roomId)) { inclusive = false }
            }
        }
    }

    val steps = listOf("대화 내용 분석 중...", "조건 추출 중...", "장소 검증 중...", "AI 추천 생성 중...", "리포트 생성 완료!")

    Column(
        modifier = Modifier.fillMaxSize().background(White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Spinner + gemini icon
        Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                color = Blue600,
                trackColor = Blue100,
                strokeWidth = 3.dp,
            )
            androidx.compose.material3.Icon(
                imageVector = GeminiIcon,
                contentDescription = null,
                tint = Blue600,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Text("AI가 분석 중입니다", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Gray900)
        Spacer(Modifier.height(8.dp))
        val displayStep = if (done) steps.lastIndex else step
        Text(
            text = steps.getOrElse(displayStep) { "" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (done) Success600 else Blue600,
            modifier = Modifier.defaultMinSize(minHeight = 22.dp),
        )

        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (done || i < step) Blue600 else Gray200)
                )
            }
        }
    }
}

private val GeminiIcon: ImageVector = ImageVector.Builder(
    name = "Gemini",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(11.5f, 7.8f, 7.8f, 11.5f, 2f, 12f)
    curveTo(7.8f, 12.5f, 11.5f, 16.2f, 12f, 22f)
    curveTo(12.5f, 16.2f, 16.2f, 12.5f, 22f, 12f)
    curveTo(16.2f, 11.5f, 12.5f, 7.8f, 12f, 2f)
    close()
}.build()