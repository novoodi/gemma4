package com.navoodi.morimi.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navoodi.morimi.data.model.CalendarEvent
import com.navoodi.morimi.data.model.ChatRoom
import com.navoodi.morimi.data.repository.CalendarRepository
import com.navoodi.morimi.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeScheduleItem(
    val id: String,
    val title: String,
    val date: LocalDate,
    val location: String,
    val memberCount: Int,
    val memberInitials: List<String>,
    val chatId: String?,
    val isUpcoming: Boolean,
)

data class HomeUiState(
    val today: LocalDate = LocalDate.now(),
    val todayEvents: List<HomeScheduleItem> = emptyList(),
    val tomorrowEvents: List<HomeScheduleItem> = emptyList(),
)

class HomeViewModel : ViewModel() {

    val rooms = ChatRepository.getRoomsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val roomReads = ChatRepository.getRoomReadsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        CalendarRepository.events,
        ChatRepository.getRoomsFlow(),
    ) { events, rooms ->
        val today = LocalDate.now()
        val roomMap: Map<String, ChatRoom> = rooms.associateBy { it.id }

        fun CalendarEvent.toItem(isUpcoming: Boolean): HomeScheduleItem {
            val room = roomId?.let { roomMap[it] }
            val uids = room?.participantUids ?: emptyList()
            val initials = uids.take(4).map { uid ->
                uid.last().uppercaseChar().toString()
            }
            return HomeScheduleItem(
                id = id,
                title = title,
                date = LocalDate.parse(date),
                location = location.ifBlank { note },
                memberCount = uids.size,
                memberInitials = initials,
                chatId = roomId,
                isUpcoming = isUpcoming,
            )
        }

        val todayStr = today.toString()
        val tomorrowStr = today.plusDays(1).toString()

        val todayItems = events
            .filter { it.date == todayStr }
            .sortedBy { it.title }
            .mapIndexed { idx, ev -> ev.toItem(isUpcoming = idx == 0) }

        val tomorrowItems = events
            .filter { it.date == tomorrowStr }
            .sortedBy { it.title }
            .map { ev -> ev.toItem(isUpcoming = false) }

        HomeUiState(today = today, todayEvents = todayItems, tomorrowEvents = tomorrowItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun showCreateDialog() { _showCreateDialog.value = true }
    fun hideCreateDialog() { _showCreateDialog.value = false }

    fun createRoom(name: String) {
        if (name.isNotBlank()) {
            viewModelScope.launch {
                ChatRepository.createRoom(name.trim())
            }
        }
        hideCreateDialog()
    }
}
