package com.example.gemma4.ui.screen.aireport

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.MoimApp
import com.example.gemma4.data.model.CalendarEvent
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.repository.CalendarRepository
import com.example.gemma4.data.repository.ChatRepository
import com.example.gemma4.navigation.Screen
import com.example.gemma4.ui.theme.*
import kotlinx.coroutines.flow.*

class AIReportViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    val summary: StateFlow<MeetingSummary?> = ChatRepository.summaries
        .map { it[roomId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isAddedToCalendar: StateFlow<Boolean> = CalendarRepository.events
        .map { events -> events.any { it.roomId == roomId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun addToCalendar() {
        val s = summary.value ?: return
        val name = ChatRepository.getRoomById(roomId)?.name ?: "모임"
        CalendarRepository.addEvent(CalendarEvent(title = name, date = s.meetingDate, location = s.location, note = s.recommendation, roomId = roomId))
    }
}


@Composable
fun AIReportScreen(navController: NavController, viewModel: AIReportViewModel = viewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val isAddedToCalendar by viewModel.isAddedToCalendar.collectAsStateWithLifecycle()

    val hasRealData = summary != null

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FC))) {
        // Nav bar
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp).background(White).padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Gray900)
            }
            Text("AI 요약 리포트", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Gray900, modifier = Modifier.align(Alignment.Center))
        }
        HorizontalDivider(color = Gray100)

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Meeting summary card
            SummaryCard(summary)

            // Weather card — real data from Gemini
            val weatherText = summary?.weather
                ?.takeIf { it.isNotBlank() && it != "날씨 정보 없음" }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFEFF8FF), Color(0xFFDBEAFE))))
                    .border(1.dp, Blue100, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(if (weatherText != null) "🌤️" else "🌡️", fontSize = 36.sp)
                Column {
                    Text(
                        text = weatherText ?: "날씨 정보를 가져오는 중...",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gray900,
                    )
                    if (weatherText != null) {
                        Text("모임 날짜 날씨 정보입니다", fontSize = 12.sp, color = Blue700)
                    }
                }
            }

            // Recommendation — real Gemini result
            if (hasRealData) {
                Text("AI 추천", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gray900, modifier = Modifier.padding(start = 2.dp))
                RecommendationCard(summary!!.recommendation)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(White)
                        .border(1.dp, Gray100, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "채팅방에서 AI 요약 버튼을 눌러\n분석을 시작해주세요",
                        fontSize = 14.sp,
                        color = Gray400,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                }
            }
        }

        // Bottom CTAs
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Calendar add button
            OutlinedButton(
                onClick = { viewModel.addToCalendar() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isAddedToCalendar,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isAddedToCalendar) Success600 else Blue600,
                ),
            ) {
                Text(
                    text = if (isAddedToCalendar) "✓ 캘린더에 추가됨" else "📅 캘린더에 추가",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = { navController.navigate(Screen.Vote.createRoute(viewModel.roomId)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue600),
            ) {
                Text("👍 그룹 투표 시작하기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SummaryCard(realSummary: com.example.gemma4.data.model.MeetingSummary?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(White)
            .border(1.dp, Gray100, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Icon(imageVector = GeminiIcon, contentDescription = null, tint = Blue600, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("AI가 분석한 모임 정보", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue600)
        }

        val rows = listOf(
            Triple("📅", "날짜", realSummary?.meetingDate?.ifBlank { "미정" } ?: "미정"),
            Triple("📍", "장소", realSummary?.location?.ifBlank { "미정" } ?: "미정"),
            Triple("🎯", "요약", realSummary?.summary?.take(80) ?: "분석 결과 없음"),
        )

        rows.forEachIndexed { i, (icon, label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (i < rows.size - 1) Modifier.padding(bottom = 10.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(icon, fontSize = 16.sp)
                Column {
                    Text(label, fontSize = 11.sp, color = Gray400, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(value, fontSize = 14.sp, color = Gray900, fontWeight = FontWeight.Medium)
                }
            }
            if (i < rows.size - 1) HorizontalDivider(color = Gray100, modifier = Modifier.padding(vertical = 5.dp))
        }
    }
}

@Composable
private fun RecommendationCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(White)
            .border(1.dp, Gray100, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(imageVector = GeminiIcon, contentDescription = null, tint = Blue600, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Gemini 추천", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue600)
        }
        Text(text, fontSize = 14.sp, color = Gray700, lineHeight = 22.sp)
    }
}


private val GeminiIcon: ImageVector = ImageVector.Builder(
    name = "Gemini", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(11.5f, 7.8f, 7.8f, 11.5f, 2f, 12f)
    curveTo(7.8f, 12.5f, 11.5f, 16.2f, 12f, 22f)
    curveTo(12.5f, 16.2f, 16.2f, 12.5f, 22f, 12f)
    curveTo(16.2f, 11.5f, 12.5f, 7.8f, 12f, 2f)
    close()
}.build()