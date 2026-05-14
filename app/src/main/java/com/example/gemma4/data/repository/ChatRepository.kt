package com.example.gemma4.data.repository

import com.example.gemma4.data.model.ChatRoom
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ChatRepository {

    private val _rooms = MutableStateFlow<List<ChatRoom>>(emptyList())
    val rooms: StateFlow<List<ChatRoom>> = _rooms.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _summaries = MutableStateFlow<Map<String, MeetingSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, MeetingSummary>> = _summaries.asStateFlow()

    fun createRoom(name: String, participants: List<String>): ChatRoom {
        val room = ChatRoom(
            name = name,
            participants = participants.ifEmpty { listOf("나") }
        )
        _rooms.value = _rooms.value + room
        return room
    }

    fun getRoomById(id: String): ChatRoom? = _rooms.value.find { it.id == id }

    fun sendMessage(message: Message) {
        val current = _messages.value.toMutableMap()
        current[message.roomId] = (current[message.roomId] ?: emptyList()) + message
        _messages.value = current
    }

    fun saveSummary(summary: MeetingSummary) {
        _summaries.value = _summaries.value + (summary.roomId to summary)
    }

    fun loadSampleMessages(roomId: String, samples: List<Pair<String, String>>, meParticipant: String) {
        val current = _messages.value.toMutableMap()
        current[roomId] = samples.map { (sender, content) ->
            com.example.gemma4.data.model.Message(
                roomId = roomId,
                senderName = sender,
                content = content,
                isMe = (sender == meParticipant)
            )
        }
        _messages.value = current
    }

    fun updateRoomParticipants(roomId: String, participants: List<String>) {
        _rooms.value = _rooms.value.map { room ->
            if (room.id == roomId) room.copy(participants = participants) else room
        }
    }
}
