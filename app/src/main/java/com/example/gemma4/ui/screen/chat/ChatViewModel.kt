package com.example.gemma4.ui.screen.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.gemma4.MoimApp
import com.example.gemma4.data.SampleData
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.data.pipeline.StatusCompressionPipeline
import com.example.gemma4.data.repository.ChatRepository
import com.example.gemma4.service.AgentEvent
import com.example.gemma4.service.AgentEventTracker
import com.example.gemma4.service.AgentOrchestrator
import com.example.gemma4.service.OrchestratorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val compressionPipeline: StatusCompressionPipeline = (application as MoimApp).compressionPipeline
    private val agentOrchestrator: AgentOrchestrator = (application as MoimApp).agentOrchestrator
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    companion object {
        private const val TAG = "ChatViewModel"
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

    private val _agentProgress = MutableStateFlow("")
    val agentProgress: StateFlow<String> = _agentProgress.asStateFlow()

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

    // 10개마다 Dispatchers.IO 백그라운드에서 StatusCompressionPipeline에 위임
    private fun triggerStatusCompression(msgs: List<Message>) {
        compressionJob = viewModelScope.launch(Dispatchers.IO) {
            compressionPipeline.compress(roomId, msgs)
        }
    }

    /**
     * Phase 3: 자율 검증 하네스 기반 요약.
     * Guardrail 검증을 통과한 결과만 [_summaryState]에 emit된다.
     */
    fun summarize() {
        val msgs = messages.value
        if (msgs.isEmpty()) {
            _summaryState.value = SummaryState.Error("채팅 내역이 없습니다")
            return
        }
        _summaryState.value = SummaryState.Loading
        _agentProgress.value = ""
        val appScope = getApplication<MoimApp>().applicationScope
        appScope.launch {
            try {
                compressionJob?.join()  // 진행 중인 압축 완료 대기

                val userStatus = userStatusRepository.getStatus(roomId)
                Log.d(TAG, "orchestrate 시작 — roomId=$roomId userStatus=$userStatus")

                val tracker = object : AgentEventTracker {
                    override fun onEvent(event: AgentEvent) {
                        _agentProgress.value = when (event) {
                            is AgentEvent.OrchestrationStarted  -> "대화 내용을 분석하고 있습니다..."
                            is AgentEvent.GemmaSummaryCompleted -> "핵심 내용을 추출했습니다. 클라우드에 연결 중..."
                            is AgentEvent.PromptGenerated       -> "AI에게 질문을 전달하고 있습니다... (시도 ${event.attempt})"
                            is AgentEvent.ToolCalled            -> when (event.name) {
                                "getWeather"  -> "날씨를 확인하고 있습니다..."
                                "searchPlace" -> "주변 장소를 검색하고 있습니다..."
                                else          -> "정보를 수집하고 있습니다..."
                            }
                            is AgentEvent.JsonParsed            -> "추천 결과를 정리하고 있습니다..."
                            is AgentEvent.GuardrailEvaluated    -> if (event.passed)
                                "추천 결과를 검증했습니다."
                            else
                                "결과를 다듬고 있습니다... (시도 ${event.attempt})"
                            is AgentEvent.OrchestrationFinished -> if (event.success) "완료!" else "분석을 마쳤습니다."
                        }
                    }
                }

                when (val result = agentOrchestrator.orchestrate(
                    roomId = roomId,
                    messages = msgs,
                    userStatus = userStatus,
                    eventTracker = tracker
                )) {
                    is OrchestratorResult.Success -> {
                        ChatRepository.saveSummary(result.summary)
                        _summaryState.value = SummaryState.Success(result.summary)
                        Log.d(TAG, "요약 완료 ✓ — Guardrail 통과 시도: ${result.attempts}회")
                    }
                    is OrchestratorResult.Failed -> {
                        _summaryState.value = SummaryState.Error(result.reason)
                        Log.w(TAG, "요약 실패 — ${result.attempts}회 시도: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                _summaryState.value = SummaryState.Error(e.message ?: "알 수 없는 오류가 발생했습니다")
                Log.e(TAG, "summarize 예외", e)
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
