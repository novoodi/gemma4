package com.example.gemma4.data.repository

import com.example.gemma4.data.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CalendarRepository {

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    fun addEvent(event: CalendarEvent): Boolean {
        if (event.roomId != null && _events.value.any { it.roomId == event.roomId }) return false
        _events.value = _events.value + event
        return true
    }

    fun removeEvent(id: String) {
        _events.value = _events.value.filter { it.id != id }
    }
}
