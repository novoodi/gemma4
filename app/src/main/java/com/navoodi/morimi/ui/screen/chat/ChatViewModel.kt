package com.navoodi.morimi.ui.screen.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.pipeline.StatusCompressionPipeline
import com.navoodi.morimi.data.repository.ChatRepository
import com.navoodi.morimi.service.AgentEvent
import com.navoodi.morimi.service.AgentEventTracker
import com.navoodi.morimi.service.AgentOrchestrator
import com.navoodi.morimi.service.FcmService
import com.navoodi.morimi.service.OrchestratorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SummaryState {
    data object Idle : SummaryState()
    data object Loading : SummaryState()
    data class Success(val summary: MeetingSummary) : SummaryState()
    data class Error(val message: String) : SummaryState()
}

sealed class LeaveState {
    data object Idle : LeaveState()
    data object Loading : LeaveState()
    data object Done : LeaveState()
    data class Error(val message: String) : LeaveState()
}

data class AgentDebugEntry(val emoji: String, val label: String, val content: String)

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()
    private val llmService = (application as MoimApp).llmService
    private val userStatusRepository = (application as MoimApp).userStatusRepository
    private val compressionPipeline: StatusCompressionPipeline = (application as MoimApp).compressionPipeline
    private val agentOrchestrator: AgentOrchestrator = (application as MoimApp).agentOrchestrator
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    companion object {
        private const val TAG = "ChatViewModel"
        private const val COMPRESSION_TRIGGER_COUNT = 10
    }

    // 현재 로그인 uid — UI에서 isMe 판단에 사용
    val currentUid: String? get() = ChatRepository.currentUid

    val room = ChatRepository.getRoomFlow(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<Message>> = ChatRepository.getMessagesFlow(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _summaryState = MutableStateFlow<SummaryState>(SummaryState.Idle)
    val summaryState: StateFlow<SummaryState> = _summaryState.asStateFlow()

    private val _leaveState = MutableStateFlow<LeaveState>(LeaveState.Idle)
    val leaveState: StateFlow<LeaveState> = _leaveState.asStateFlow()

    private val _agentProgress = MutableStateFlow("")
    val agentProgress: StateFlow<String> = _agentProgress.asStateFlow()

    private val _debugLog = MutableStateFlow<List<AgentDebugEntry>>(emptyList())
    val debugLog: StateFlow<List<AgentDebugEntry>> = _debugLog.asStateFlow()

    private var compressionJob: Job? = null

    // 백그라운드 성향 압축 진행 여부 — "스피너 없는" 플로팅 상태배지 표시용
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    fun onInputChange(text: String) { _inputText.value = text }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""
        val senderName = auth.currentUser?.displayName ?: "나"
        val preCount = messages.value.size
        viewModelScope.launch {
            ChatRepository.sendMessage(roomId, text, senderName)
            if ((preCount + 1) % COMPRESSION_TRIGGER_COUNT == 0) {
                triggerStatusCompression(messages.value)
            }
        }
    }

    private fun triggerStatusCompression(msgs: List<Message>) {
        compressionJob = viewModelScope.launch(Dispatchers.IO) {
            _isCompressing.value = true
            try {
                compressionPipeline.compress(roomId, msgs)
            } finally {
                _isCompressing.value = false
            }
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
        _debugLog.value = emptyList()
        val appScope = getApplication<MoimApp>().applicationScope
        appScope.launch {
            try {
                compressionJob?.join()

                val userStatus = userStatusRepository.getStatus(roomId)
                Log.d(TAG, "orchestrate 시작 — roomId=$roomId userStatus=$userStatus")

                _debugLog.value = _debugLog.value + AgentDebugEntry(
                    emoji = "👤",
                    label = "저장된 사용자 성향 (Gemini에 전달됨)",
                    content = buildString {
                        appendLine("참가자: ${userStatus?.participants?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                        appendLine("선호/불호: ${userStatus?.preferences?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                        append("가능 일정: ${userStatus?.availability?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                    }
                )

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

                        val entry: AgentDebugEntry? = when (event) {
                            is AgentEvent.GemmaSummaryCompleted ->
                                AgentDebugEntry("🤖", "Gemma 온디바이스 요약 (원문 미전송)", event.summary)
                            is AgentEvent.PromptGenerated ->
                                AgentDebugEntry("📝", "Gemini 전달 프롬프트 (시도 ${event.attempt})", event.prompt)
                            is AgentEvent.ToolCalled ->
                                AgentDebugEntry("🔧", "도구 호출: ${event.name}", buildString {
                                    event.args.entries.forEach { (k, v) -> appendLine("▸ $k: $v") }
                                    appendLine()
                                    append("결과:\n${event.result}")
                                })
                            is AgentEvent.JsonParsed ->
                                AgentDebugEntry("📊", "Gemini 응답 JSON", event.rawJson)
                            is AgentEvent.GuardrailEvaluated ->
                                AgentDebugEntry(
                                    if (event.passed) "✅" else "❌",
                                    "Guardrail 검증 (시도 ${event.attempt})",
                                    buildString {
                                        append(if (event.passed) "통과 — 검증 완료" else "실패\n${event.feedback}")
                                        if (event.unknownCount > 0) append("\n⚠ 검증 불가 ${event.unknownCount}건 (장소 API 응답 없음)")
                                    }
                                )
                            else -> null
                        }
                        if (entry != null) _debugLog.value = _debugLog.value + entry
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
                        FcmService.showAnalysisDoneNotification(
                            getApplication<MoimApp>().applicationContext, roomId, success = true
                        )
                        Log.d(TAG, "요약 완료 ✓ — Guardrail 통과 시도: ${result.attempts}회")
                    }
                    is OrchestratorResult.Failed -> {
                        _summaryState.value = SummaryState.Error(result.reason)
                        FcmService.showAnalysisDoneNotification(
                            getApplication<MoimApp>().applicationContext, roomId, success = false
                        )
                        Log.w(TAG, "요약 실패 — ${result.attempts}회 시도: ${result.reason}")
                    }
                }
            } catch (e: Exception) {
                _summaryState.value = SummaryState.Error(e.message ?: "알 수 없는 오류가 발생했습니다")
                FcmService.showAnalysisDoneNotification(
                    getApplication<MoimApp>().applicationContext, roomId, success = false
                )
                Log.e(TAG, "summarize 예외", e)
            }
        }
    }

    fun markAsRead() {
        viewModelScope.launch { ChatRepository.updateLastReadTime(roomId) }
    }

    fun resetSummaryState() { _summaryState.value = SummaryState.Idle }

    fun leaveRoom() {
        viewModelScope.launch {
            _leaveState.value = LeaveState.Loading
            try {
                ChatRepository.leaveRoom(roomId)
                _leaveState.value = LeaveState.Done
            } catch (e: Exception) {
                Log.e(TAG, "leaveRoom 실패", e)
                _leaveState.value = LeaveState.Error(e.message ?: "방 나가기 실패")
            }
        }
    }

    fun resetLeaveError() { _leaveState.value = LeaveState.Idle }
}