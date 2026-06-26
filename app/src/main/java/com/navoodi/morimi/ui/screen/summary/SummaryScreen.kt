package com.navoodi.morimi.ui.screen.summary

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.model.CalendarEvent
import com.navoodi.morimi.navigation.Screen
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.repository.CalendarRepository
import com.navoodi.morimi.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SummaryViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val roomId: String = checkNotNull(savedStateHandle["roomId"])
    private val feedbackRepository = (application as MoimApp).feedbackRepository
    val isModelAvailable: Boolean = (application as MoimApp).llmService.isModelAvailable

    val summary = ChatRepository.summaries
        .map { it[roomId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isAddedToCalendar = CalendarRepository.events
        .map { events -> events.any { it.roomId == roomId && it.placeName.isBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addToCalendar() {
        val s = summary.value ?: return
        viewModelScope.launch {
            val roomName = ChatRepository.getRoomById(roomId)?.name ?: "모임"
            CalendarRepository.addEvent(
                CalendarEvent(
                    title = roomName,
                    date = s.meetingDate,
                    location = s.location,
                    note = s.recommendation,
                    roomId = roomId
                )
            )
        }
    }

    fun submitFeedback(feedback: String) {
        if (feedback.isBlank()) return
        feedbackRepository.append(feedback = feedback, roomId = roomId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    navController: NavController,
    viewModel: SummaryViewModel = viewModel()
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val isAddedToCalendar by viewModel.isAddedToCalendar.collectAsStateWithLifecycle()
    var showFeedbackDialog by remember { mutableStateOf(false) }

    val canAddToCalendar = summary?.meetingDate?.let { it.isNotEmpty() && it != "미정" } ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("모임 정리") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::addToCalendar,
                        enabled = canAddToCalendar && !isAddedToCalendar
                    ) {
                        Icon(
                            imageVector = if (isAddedToCalendar) Icons.Default.Check
                                          else Icons.Default.CalendarMonth,
                            contentDescription = if (isAddedToCalendar) "캘린더에 추가됨" else "캘린더에 추가",
                            tint = if (isAddedToCalendar) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Mock AI 배너: 모델 미설치 시 사용자에게 샘플 결과임을 표시
            if (!viewModel.isModelAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3CD))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⚠️ AI 모델 미설치 · 샘플 결과",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF856404),
                    )
                    TextButton(
                        onClick = { navController.navigate(Screen.ModelDownload.route) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("다운로드", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (summary == null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                SummaryContent(
                    summary = summary!!,
                    modifier = Modifier.weight(1f),
                    onFeedbackClick = { showFeedbackDialog = true }
                )
            }
        }
    }

    if (showFeedbackDialog) {
        FeedbackDialog(
            onDismiss = { showFeedbackDialog = false },
            onSubmit = { feedback ->
                viewModel.submitFeedback(feedback)
                showFeedbackDialog = false
            }
        )
    }
}

@Composable
private fun SummaryContent(
    summary: MeetingSummary,
    modifier: Modifier = Modifier,
    onFeedbackClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(title = "대화 내용 요약", content = summary.summary)
        SummaryCard(title = "모임 날짜", content = summary.meetingDate)
        SummaryCard(title = "모임 장소", content = summary.location)
        SummaryCard(title = "당일 날씨", content = summary.weather)
        SummaryCard(title = "AI 추천 (장소 / 활동 / 준비물)", content = summary.recommendation)
        SummaryCard(title = "가는 방법", content = summary.directions)

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onFeedbackClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("모임 후기 남기기")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FeedbackDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모임 후기") },
        text = {
            Column {
                Text(
                    text = "이번 모임은 어떠셨나요? 다음 추천에 반영됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("예: 조용하고 분위기 좋았어 / 주차가 너무 불편했어") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank()
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun SummaryCard(title: String, content: String) {
    if (content.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}