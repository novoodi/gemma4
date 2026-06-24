package com.navoodi.morimi.ui.screen.ailoading

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.data.repository.ChatRepository
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.ui.screen.chat.AgentDebugEntry
import com.navoodi.morimi.ui.theme.*
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
    debugLog: List<AgentDebugEntry> = emptyList(),
    viewModel: AILoadingViewModel = viewModel()
) {
    val done by viewModel.done.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(done) {
        if (done) {
            delay(400)
            navController.navigate(Screen.AIReport.createRoute(viewModel.roomId)) {
                popUpTo(Screen.Chat.createRoute(viewModel.roomId)) { inclusive = false }
            }
        }
    }

    LaunchedEffect(debugLog.size) {
        if (debugLog.isNotEmpty()) listState.animateScrollToItem(debugLog.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(White).statusBarsPadding()) {
        // 상단 스피너 + 상태 텍스트
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
            Spacer(Modifier.height(24.dp))
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

        // 디버그 로그 패널
        if (debugLog.isNotEmpty()) {
            HorizontalDivider(color = Gray100)
            Text(
                text = "AI 파이프라인 내부 로그",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Gray400,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFF8F9FC)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(debugLog) { _, entry ->
                    DebugEntryCard(entry)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun DebugEntryCard(entry: AgentDebugEntry) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = White,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.emoji, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = entry.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Gray900,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▲" else "▼",
                    fontSize = 10.sp,
                    color = Gray400,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Gray100)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.content,
                    fontSize = 11.sp,
                    color = Gray700,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
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