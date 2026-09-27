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
import com.navoodi.morimi.data.repository.MetricsRepository.FeedbackTarget
import com.navoodi.morimi.service.AssistantEvent
import com.navoodi.morimi.service.AssistantEventTracker
import com.navoodi.morimi.service.AssistantOrchestrator
import com.navoodi.morimi.service.FcmService
import com.navoodi.morimi.service.OrchestratorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

sealed class LeaveState {
    data object Idle : LeaveState()
    data object Loading : LeaveState()
    data object Done : LeaveState()
    data class Error(val message: String) : LeaveState()
}

data class AssistantDebugEntry(val emoji: String, val label: String, val content: String)

class ChatViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()
    private val llmService = (application as MoimApp).llmService
    private val userStatusRepository = (application as MoimApp).userStatusRepository
    private val compressionPipeline: StatusCompressionPipeline = (application as MoimApp).compressionPipeline
    private val agentOrchestrator: AssistantOrchestrator = (application as MoimApp).agentOrchestrator
    private val feedbackRepository = (application as MoimApp).feedbackRepository
    private val summaryRepository = (application as MoimApp).summaryRepository
    private val metricsRepository = (application as MoimApp).metricsRepository
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    companion object {
        private const val TAG = "ChatViewModel"
        private const val COMPRESSION_TRIGGER_COUNT = 10

        // 증분 압축 델타 창 — 트리거마다 넘길 최근 메시지 수. 트리거 간격(10)보다 약간 크게 잡아
        // 스냅샷 지연으로 인한 누락을 겹침으로 방어(병합이 중복을 제거하므로 겹침은 무해).
        private const val COMPRESSION_WINDOW_SIZE = 15
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

    private val _debugLog = MutableStateFlow<List<AssistantDebugEntry>>(emptyList())
    val debugLog: StateFlow<List<AssistantDebugEntry>> = _debugLog.asStateFlow()

    private var compressionJob: Job? = null

    // 백그라운드 성향 압축 진행 여부 — "스피너 없는" 플로팅 상태배지 표시용
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    // 후기 팝업 — 추천받은 방 재진입 시, 아직 후기 미작성이면 노출 (RAG 데이터 확보)
    private val _showFeedbackPrompt = MutableStateFlow(false)
    val showFeedbackPrompt: StateFlow<Boolean> = _showFeedbackPrompt.asStateFlow()
    private var pendingFeedbackTarget: FeedbackTarget? = null
    private val _feedbackError = MutableStateFlow<String?>(null)
    val feedbackError: StateFlow<String?> = _feedbackError.asStateFlow()

    // 이 방에 저장된(영속 복원 포함) 지난 추천이 있는지 — "지난 추천 보기" 진입점 노출 근거
    val hasSavedSummary: StateFlow<Boolean> = ChatRepository.summaries
        .map { it.containsKey(roomId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            try {
                if (feedbackRepository.shouldPromptFeedback(roomId)) {
                    pendingFeedbackTarget = metricsRepository.feedbackTargetFor(roomId)
                    _showFeedbackPrompt.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "후기 대상 조회 실패", error)
                _feedbackError.value = "후기 대상을 확인하지 못했습니다. 방에 다시 들어와 주세요."
            }
        }
    }

    /**
     * 후기 팝업/입력에서 저장 — 온디바이스 임베딩 인덱싱 후 다음 추천 RAG에 반영.
     * 임베딩은 수십 초 걸리므로 **applicationScope**에서 실행한다 — 저장 직후 사용자가
     * 화면을 벗어나 ViewModel이 파괴돼도 임베딩이 취소되지 않고 끝까지 완료된다.
     */
    fun submitFeedback(text: String, rating: Int = 0) {
        if (text.isBlank() || rating !in 0..5 || !_showFeedbackPrompt.value) return
        val target = pendingFeedbackTarget
        _showFeedbackPrompt.value = false
        getApplication<MoimApp>().applicationScope.launch {
            try {
                feedbackRepository.append(text, roomId, rating)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "후기 저장 실패", error)
                _feedbackError.value = "후기 저장을 완료하지 못했습니다. 저장 상태를 확인해 주세요."
                return@launch
            }
            if (rating > 0 && target != null) {
                try {
                    // 임베딩 도중 새 추천이 생겨도 입력창을 열 때의 실행에만 연결한다.
                    val learned = metricsRepository.attachSatisfaction(target, rating)
                    if (learned != null) {
                        Log.d(TAG, "만족도 $rating/5 반영 — 재학습 보정 ${learned.appliedDeltas.size}건")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "만족도 학습 저장 실패", error)
                    _feedbackError.value = "후기는 저장됐지만 평점을 다음 추천에 반영하지 못했습니다."
                }
            }
        }
    }

    fun dismissFeedbackPrompt() {
        _showFeedbackPrompt.value = false
        pendingFeedbackTarget = null
    }

    fun dismissFeedbackError() { _feedbackError.value = null }

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
                // 누적 전체가 아닌 최근 델타만 압축에 넘긴다(컨텍스트 초과 방지) — 파이프라인이 직전 상태와 병합
                triggerStatusCompression(messages.value.takeLast(COMPRESSION_WINDOW_SIZE))
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

                _debugLog.value = _debugLog.value + AssistantDebugEntry(
                    emoji = "👤",
                    label = "저장된 사용자 성향 (Gemini에 전달됨)",
                    content = buildString {
                        appendLine("참가자: ${userStatus?.participants?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                        appendLine("선호/불호: ${userStatus?.preferences?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                        append("가능 일정: ${userStatus?.availability?.joinToString(", ").takeIf { !it.isNullOrBlank() } ?: "없음"}")
                    }
                )

                val tracker = object : AssistantEventTracker {
                    override fun onEvent(event: AssistantEvent) {
                        _agentProgress.value = when (event) {
                            is AssistantEvent.OrchestrationStarted  -> "대화 내용을 분석하고 있습니다..."
                            is AssistantEvent.ContextClassified     -> "모임 성격을 파악했습니다: ${event.label}"
                            is AssistantEvent.CriteriaWeighted      -> "이 상황에 맞는 판단 기준을 세웠습니다..."
                            is AssistantEvent.GemmaSummaryCompleted -> "핵심 내용을 추출했습니다. 클라우드에 연결 중..."
                            is AssistantEvent.SieveNormalized       -> "요청을 정해진 형식으로 정리했습니다..."
                            is AssistantEvent.PlacesRanked          -> "후보를 기준별로 비교하고 있습니다..."
                            is AssistantEvent.MetricsRecorded       -> "결과 품질을 기록했습니다."
                            is AssistantEvent.PromptGenerated       -> "AI에게 질문을 전달하고 있습니다... (시도 ${event.attempt})"
                            is AssistantEvent.ToolCalled            -> when (event.name) {
                                "getWeather"  -> "날씨를 확인하고 있습니다..."
                                "searchPlace" -> "주변 장소를 검색하고 있습니다..."
                                else          -> "정보를 수집하고 있습니다..."
                            }
                            is AssistantEvent.JsonParsed            -> "추천 결과를 정리하고 있습니다..."
                            is AssistantEvent.GuardrailEvaluated    -> if (event.passed)
                                "추천 결과를 검증했습니다."
                            else
                                "결과를 다듬고 있습니다... (시도 ${event.attempt})"
                            is AssistantEvent.ReflectionEvaluated   -> if (event.passed)
                                "취향 제약을 점검했습니다."
                            else
                                "싫어하시는 요소를 발견해 다시 추천합니다... (시도 ${event.attempt})"
                            is AssistantEvent.OrchestrationFinished -> if (event.success) "완료!" else "분석을 마쳤습니다."
                        }

                        val entry: AssistantDebugEntry? = when (event) {
                            is AssistantEvent.ContextClassified ->
                                AssistantDebugEntry("🧭", "상황 분류 (온디바이스 — 원문은 기기 안에서만 읽음)", buildString {
                                    appendLine("판정: ${event.label} (${event.context})")
                                    appendLine("신뢰도: ${"%.1f".format(event.confidence * 100)}%")
                                    if (event.signals.isNotEmpty()) appendLine("근거 키워드: ${event.signals.joinToString(", ")}")
                                    append("차순위 후보: ${event.runnerUp ?: "없음"}")
                                })
                            is AssistantEvent.CriteriaWeighted ->
                                AssistantDebugEntry("⚖️", "AHP 판단 기준 가중치 — ${event.context}", buildString {
                                    event.weights.forEachIndexed { i, (label, w) ->
                                        appendLine("${i + 1}. $label ${"%.1f".format(w * 100)}%")
                                    }
                                    appendLine("일관성 비율 CR=${"%.4f".format(event.cr)} (임계 0.10, ${if (event.consistent) "유효" else "기각"})")
                                    append(
                                        if (event.learnedDeltas > 0)
                                            "지난 피드백 학습 반영 ${event.learnedDeltas}건" +
                                                if (event.droppedDeltas > 0) " (일관성 위반으로 ${event.droppedDeltas}건 기각)" else ""
                                        else "학습 보정 없음 — 상황 기본 가중치"
                                    )
                                })
                            is AssistantEvent.SieveNormalized ->
                                AssistantDebugEntry("🧱", "거름망 — 정형화된 요청 블록 (실제 전송분)", buildString {
                                    append(event.frame)
                                    if (event.missingSlots.isNotEmpty()) {
                                        appendLine()
                                        appendLine()
                                        append("⚠ 미확정 슬롯 ${event.missingSlots.size}개: ${event.missingSlots.joinToString(", ")}")
                                    }
                                })
                            is AssistantEvent.PlacesRanked ->
                                AssistantDebugEntry("📐", "AHP 종합 랭킹 (시도 ${event.attempt})", buildString {
                                    event.ranking.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
                                    append(if (event.changed) "→ 기준 가중치에 따라 순서를 재배열함" else "→ 원래 순서 유지")
                                })
                            is AssistantEvent.MetricsRecorded ->
                                AssistantDebugEntry("📈", "하네스 품질 지표", event.summary)
                            is AssistantEvent.GemmaSummaryCompleted ->
                                AssistantDebugEntry("🤖", "Gemma 온디바이스 요약 (원문 미전송)", buildString {
                                    append(event.summary)
                                    if (event.redactions > 0) {
                                        appendLine()
                                        appendLine()
                                        val detail = event.redactionsByCategory.entries
                                            .joinToString(", ") { "${it.key} ${it.value}건" }
                                        append("🛡 PII 스크러버: 클라우드 전송 직전 ${event.redactions}건 마스킹 ($detail)")
                                    }
                                })
                            is AssistantEvent.PromptGenerated ->
                                AssistantDebugEntry("📝", "Gemini 전달 프롬프트 (시도 ${event.attempt})", event.prompt)
                            is AssistantEvent.ToolCalled ->
                                AssistantDebugEntry("🔧", "도구 호출: ${event.name}", buildString {
                                    event.args.entries.forEach { (k, v) -> appendLine("▸ $k: $v") }
                                    appendLine()
                                    append("결과:\n${event.result}")
                                })
                            is AssistantEvent.JsonParsed ->
                                AssistantDebugEntry("📊", "Gemini 응답 JSON", event.rawJson)
                            is AssistantEvent.GuardrailEvaluated ->
                                AssistantDebugEntry(
                                    if (event.passed) "✅" else "❌",
                                    "Guardrail 검증 (시도 ${event.attempt})",
                                    buildString {
                                        append(if (event.passed) "통과 — 검증 완료" else "실패\n${event.feedback}")
                                        if (event.unknownCount > 0) append("\n⚠ 검증 불가 ${event.unknownCount}건 (장소 API 응답 없음)")
                                    }
                                )
                            is AssistantEvent.ReflectionEvaluated ->
                                AssistantDebugEntry(
                                    if (event.passed) "🪞" else "🚫",
                                    "Reflection 자기비평 (시도 ${event.attempt})",
                                    if (event.passed) "통과 — 사용자 제약(싫어요) 위반 없음"
                                    else "제약 위반 감지:\n" + event.violations.joinToString("\n") { "• $it" }
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
                        // Room 영속 — 앱 재시작 후에도 "지난 추천 보기"로 재확인 가능
                        summaryRepository.save(result.summary)
                        // 이 방을 "추천받은 방"으로 표시 → 다음 재진입 시 후기 팝업 트리거 근거
                        feedbackRepository.markRecommended(roomId)
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
