package com.example.gemma4.ui.screen.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.gemma4.MoimApp
import com.example.gemma4.data.SampleData
import com.example.gemma4.data.local.UserStatusEntity
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class SummaryState {
    data object Idle : SummaryState()
    data object Loading : SummaryState()
    data class Success(val summary: MeetingSummary) : SummaryState()
    data class Error(val message: String) : SummaryState()
}

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val llmService = (application as MoimApp).llmService
    private val userStatusRepository = (application as MoimApp).userStatusRepository
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    companion object {
        private const val COMPRESSION_TRIGGER_COUNT = 10
    }

    val room = ChatRepository.rooms
        .map { rooms -> rooms.find { it.id == roomId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val participants: StateFlow<List<Participant>> = ChatRepository.rooms
        .map { rooms -> rooms.find { it.id == roomId }?.participants ?: listOf(Participant(name = "나")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(Participant(name = "나")))

    val messages: StateFlow<List<Message>> = ChatRepository.messages
        .map { it[roomId] ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _currentSender = MutableStateFlow(Participant(name = "나"))
    val currentSender: StateFlow<Participant> = _currentSender.asStateFlow()

    private val _summaryState = MutableStateFlow<SummaryState>(SummaryState.Idle)
    val summaryState: StateFlow<SummaryState> = _summaryState.asStateFlow()

    private var compressionJob: Job? = null

    init {
        viewModelScope.launch {
            val first = ChatRepository.getRoomById(roomId)?.participants?.firstOrNull()
            if (first != null) _currentSender.value = first
        }
    }

    fun onInputChange(text: String) { _inputText.value = text }

    fun setSender(participant: Participant) { _currentSender.value = participant }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        val sender = _currentSender.value
        val firstParticipant = participants.value.firstOrNull()
        ChatRepository.sendMessage(
            Message(
                roomId = roomId,
                senderId = sender.id,
                senderName = sender.name,
                content = text,
                isMe = (sender.id == firstParticipant?.id)
            )
        )
        _inputText.value = ""

        val updatedMessages = ChatRepository.messages.value[roomId] ?: return
        if (updatedMessages.size % COMPRESSION_TRIGGER_COUNT == 0) {
            triggerStatusCompression(updatedMessages.toList())
        }
    }

    private fun triggerStatusCompression(msgs: List<Message>) {
        compressionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!llmService.isModelAvailable) return@launch
                llmService.initialize()
                val json = llmService.compressChatToStatus(msgs)
                val entity = parseUserStatus(json)
                if (entity != null) {
                    userStatusRepository.upsert(entity)
                    Log.d("ChatViewModel", "UserStatus 업데이트 완료 roomId=$roomId")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "상태 압축 실패", e)
            }
        }
    }

    private fun parseUserStatus(json: String): UserStatusEntity? {
        return try {
            val raw = Regex("""\{[\s\S]*\}""").find(json)?.value ?: json
            val jsonStr = raw
                .replace(Regex(""",\s*]"""), "]")
                .replace(Regex(""",\s*\}"""), "}")
            val obj = JSONObject(jsonStr)

            fun arr(key: String): List<String> {
                val a = obj.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }

            UserStatusEntity(
                roomId = roomId,
                participants = arr("participants"),
                preferences = arr("preferences"),
                availability = arr("availability"),
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("ChatViewModel", "UserStatus JSON 파싱 실패: $json", e)
            null
        }
    }

    fun summarize() {
        val msgs = messages.value
        if (msgs.isEmpty()) return
        viewModelScope.launch {
            _summaryState.value = SummaryState.Loading
            try {
                compressionJob?.join()  // 압축이 진행 중이면 완료까지 대기
                if (!llmService.isModelAvailable) {
                    _summaryState.value = SummaryState.Error(
                        "모델 파일을 찾을 수 없습니다.\n\n다음 경로에 파일을 넣어주세요:\n${llmService.modelPath}"
                    )
                    return@launch
                }
                llmService.initialize()
                val currentRoom = ChatRepository.getRoomById(roomId)
                val userStatus = userStatusRepository.getStatus(roomId)
                Log.d("ChatViewModel", "summarize 시작 — userStatus: $userStatus")
                val result = llmService.runPipeline(
                    roomId, msgs,
                    currentRoom?.participants ?: emptyList(),
                    userStatus
                )
                ChatRepository.saveSummary(result)
                _summaryState.value = SummaryState.Success(result)
            } catch (e: Exception) {
                _summaryState.value = SummaryState.Error(e.message ?: "알 수 없는 오류가 발생했습니다")
            }
        }
    }

    fun resetSummaryState() { _summaryState.value = SummaryState.Idle }

    fun loadSampleData(index: Int) {
        val dataset = SampleData.datasets[index]
        ChatRepository.updateRoomParticipants(roomId, dataset.participants)
        ChatRepository.loadSampleMessages(roomId, dataset.messages, dataset.participants, dataset.participants.first())
        _currentSender.value = dataset.participants.first()

        val loadedMessages = ChatRepository.messages.value[roomId] ?: return
        if (loadedMessages.isNotEmpty()) {
            triggerStatusCompression(loadedMessages.toList())
        }
    }
}
