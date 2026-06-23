package com.example.gemma4.ui.screen.ailoading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val summaryReady: StateFlow<Boolean> = ChatRepository.summaries
        .map { it.containsKey(roomId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch {
            withTimeoutOrNull(180_000L) {
                summaryReady.filter { it }.first()
            }
            _done.value = true
        }
    }
}

@Composable
fun AILoadingScreen(
    navController: NavController,
    agentProgress: String,
    viewModel: AILoadingViewModel = viewModel()
) {
    val done by viewModel.done.collectAsState()

    LaunchedEffect(done) {
        if (done) {
            delay(400)
            navController.navigate(Screen.AIReport.createRoute(viewModel.roomId)) {
                popUpTo(Screen.Chat.createRoute(viewModel.roomId)) { inclusive = false }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
        Text(
            text = agentProgress.ifBlank { "분석을 시작하고 있습니다..." },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (done) Success600 else Blue600,
            modifier = Modifier.defaultMinSize(minHeight = 22.dp),
        )
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