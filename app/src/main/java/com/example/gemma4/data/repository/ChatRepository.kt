package com.example.gemma4.data.repository

import com.example.gemma4.data.model.ChatRoom
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
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

    fun createRoom(name: String, participants: List<Participant>): ChatRoom {
        val room = ChatRoom(
            name = name,
            participants = participants.ifEmpty { listOf(Participant(name = "나")) }
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

    fun loadSampleMessages(
        roomId: String,
        samples: List<Pair<String, String>>,
        participants: List<Participant>,
        meParticipant: Participant
    ) {
        val nameToParticipant = participants.associateBy { it.name }
        val current = _messages.value.toMutableMap()
        current[roomId] = samples.map { (senderName, content) ->
            val sender = nameToParticipant[senderName] ?: Participant(name = senderName)
            Message(
                roomId = roomId,
                senderId = sender.id,
                senderName = senderName,
                content = content,
                isMe = (sender.id == meParticipant.id)
            )
        }
        _messages.value = current
    }

    fun updateRoomParticipants(roomId: String, participants: List<Participant>) {
        _rooms.value = _rooms.value.map { room ->
            if (room.id == roomId) room.copy(participants = participants) else room
        }
    }
}
