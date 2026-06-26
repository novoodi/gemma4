package com.navoodi.morimi.data.repository

import com.navoodi.morimi.data.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CalendarRepository {

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    // placeUrl이 있으면 placeUrl, 없으면 placeName을 식별 키로 사용
    private fun placeKey(placeUrl: String, placeName: String) = placeUrl.ifBlank { placeName }

    fun addEvent(event: CalendarEvent): Boolean {
        val duplicate = _events.value.any { e ->
            e.roomId == event.roomId &&
            if (event.placeName.isNotBlank()) e.placeName == event.placeName
            else e.placeName.isBlank()
        }
        if (duplicate) return false
        _events.value = _events.value + event
        return true
    }

    fun isPlaceAdded(roomId: String, placeUrl: String, placeName: String): Boolean {
        val key = placeKey(placeUrl, placeName)
        return _events.value.any { e ->
            e.roomId == roomId && placeKey(e.placeUrl, e.placeName) == key
        }
    }

    fun removePlaceEvent(roomId: String, placeUrl: String, placeName: String) {
        val key = placeKey(placeUrl, placeName)
        val event = _events.value.firstOrNull { e ->
            e.roomId == roomId && placeKey(e.placeUrl, e.placeName) == key
        }
        event?.let { removeEvent(it.id) }
    }

    fun removeEvent(id: String) {
        _events.value = _events.value.filter { it.id != id }
    }
}
