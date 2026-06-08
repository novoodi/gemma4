package com.example.gemma4.ui.screen.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.gemma4.data.model.CalendarEvent
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.repository.CalendarRepository
import com.example.gemma4.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SummaryViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val roomId: String = checkNotNull(savedStateHandle["roomId"])

    val summary = ChatRepository.summaries
        .map { it[roomId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isAddedToCalendar = CalendarRepository.events
        .map { events -> events.any { it.roomId == roomId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addToCalendar() {
        val s = summary.value ?: return
        val roomName = ChatRepository.getRoomById(roomId)?.name ?: "모임"
        CalendarRepository.addEvent(
            CalendarEvent(
                title = roomName,
                date = s.meetingDate,
                location = s.location,
                note = s.activities,
                roomId = roomId
            )
        )
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
        if (summary == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            SummaryContent(summary = summary!!, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun SummaryContent(summary: MeetingSummary, modifier: Modifier = Modifier) {
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
        SummaryCard(title = "참여자 프로필", content = summary.participantProfiles)
        SummaryCard(title = "할 것들", content = summary.activities)
        SummaryCard(title = "챙겨갈 것들", content = summary.whatToBring)
        SummaryCard(title = "가는 방법", content = summary.directions)
    }
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
