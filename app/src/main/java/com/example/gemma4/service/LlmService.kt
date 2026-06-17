package com.example.gemma4.service

import android.content.Context
import android.util.Log
import com.example.gemma4.data.local.UserStatusEntity
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.data.repository.FeedbackRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LlmService(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        private const val NO_MARKDOWN = "별표, 샵, 대괄호 같은 특수기호 없이 일반 텍스트로만 답해줘."
    }

    private var engine: Engine? = null
    private var activeConversation: Any? = null
    private val engineMutex = Mutex()
    val feedbackRepository = FeedbackRepository(context)
    private val geminiService = GeminiService()

    val modelPath: String
        get() = "${context.getExternalFilesDir("models")?.absolutePath}/$MODEL_FILENAME"

    val isModelAvailable: Boolean
        get() = File(modelPath).exists()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        val config = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
        engine = Engine(config)
        engine!!.initialize()
    }

    private fun closeConversation(conv: Any) {
        try {
            (conv as? AutoCloseable)?.close()
                ?: conv::class.java.getMethod("close").invoke(conv)
        } catch (_: Exception) {}
    }

    private fun closeActiveConversation() {
        val conv = activeConversation ?: return
        activeConversation = null
        closeConversation(conv)
    }

    suspend fun compressChatToStatus(messages: List<Message>): String = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }
            val transcript = messages.joinToString("\n") { "[${it.senderName}]: ${it.content}" }

            val prompt = """
아래 채팅 로그를 분석하여 반드시 아래 JSON 포맷으로만 출력하라.
설명, 마크다운 기호, JSON 외 추가 텍스트를 절대 포함하지 마라.

출력 포맷:
{
  "participants": ["참석자 이름1", "참석자 이름2"],
  "preferences": ["좋아요: 조용한 카페", "싫어요: 시끄러운 술집", "선호: 이탈리안 음식"],
  "availability": ["토요일 오후 가능", "주중 저녁 불가", "다음 주 금요일 확정"]
}

규칙:
- participants: 대화에 등장하는 모든 참석자 이름 목록
- preferences: 음식, 장소, 분위기 등 선호/불호 항목. "좋아요:", "싫어요:" 접두사를 붙여라.
- availability: 가능/불가능한 날짜, 요일, 시간대

채팅 로그:
$transcript
""".trimIndent()

            val conv = eng.createConversation()
            try {
                val result = conv.sendMessage(prompt).toString()
                Log.d("LlmService", "압축 결과 raw: $result")
                result
            } finally {
                closeConversation(conv)
            }
        }
    }

    suspend fun runPipeline(
        roomId: String,
        messages: List<Message>,
        roomParticipants: List<Participant> = emptyList(),
        userStatus: UserStatusEntity? = null
    ): MeetingSummary = engineMutex.withLock {
        val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }

        closeActiveConversation()

        val senderIdToParticipant = roomParticipants.associateBy { it.id }
        val transcript = messages.joinToString("\n") { msg ->
            val p = senderIdToParticipant[msg.senderId]
            val tag = if (p != null) "[${p.name}]" else "[${msg.senderName}]"
            "$tag: ${msg.content}"
        }
        Log.d("LlmService", "트랜스크립트 앞 3줄:\n${transcript.lines().take(3).joinToString("\n")}")

        val chatDate = messages.firstOrNull()?.let {
            Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now()

        // ── Conv A: 요약/장소/날짜/도시 추출 ────────────────────────────────
        val convA = eng.createConversation()
        activeConversation = convA

        val summary: String
        val location: String
        val meetingDate: String
        val city: String
        try {
            summary = withContext(Dispatchers.IO) {
                convA.sendMessage(
                    "아래 모임 대화를 읽고 핵심 내용을 3~5문장으로 정리해줘. $NO_MARKDOWN\n\n$transcript"
                ).toString()
            }
            location = withContext(Dispatchers.IO) {
                convA.sendMessage(
                    "위 대화에서 모임 장소 이름만 짧게 알려줘. $NO_MARKDOWN"
                ).toString()
            }
            meetingDate = withContext(Dispatchers.IO) {
                val raw = convA.sendMessage(
                    "대화가 이루어진 날짜는 $chatDate 입니다. " +
                    "위 대화에서 참석자들이 최종 확정한 모임 당일 날짜를 YYYY-MM-DD 형식으로만 답해줘. " +
                    "투표 마감일, 제안된 날짜, 취소된 날짜는 제외하고 최종 결정된 날짜만 추출해. " +
                    "'N일'처럼 일만 있으면 $chatDate 의 연도와 월을 그대로 사용해. " +
                    "날짜를 특정할 수 없으면 미정 이라고만 해. $NO_MARKDOWN"
                ).toString()
                Log.d("LlmService", "날짜 raw: $raw")
                Regex("\\d{4}-\\d{2}-\\d{2}").find(raw)?.value ?: "미정"
            }
            city = withContext(Dispatchers.IO) {
                val raw = convA.sendMessage(
                    "위 모임 장소가 있는 도시나 지역 이름을 한국어로만 알려줘. 예: 서울, 부산, 인천, 대구, 홍대, 강남. $NO_MARKDOWN"
                ).toString().trim()
                Log.d("LlmService", "도시 raw: $raw")
                raw
            }
        } finally {
            closeActiveConversation()
        }

        // ── Gemini: RAG 기반 장소/활동 추천 ────────────────────────────────
        val ragContext = feedbackRepository.buildRagContext()
        Log.d("LlmService", "RAG context (${ragContext.length}자):\n$ragContext")
        Log.d("LlmService", "UserStatus 주입: $userStatus")
        val recommendation = geminiService.recommend(
            summary = summary,
            location = location,
            date = meetingDate,
            city = city,
            ragContext = ragContext,
            userStatus = userStatus
        )

        val weather = WeatherService.getWeather(city, meetingDate)

        return MeetingSummary(
            roomId = roomId,
            summary = summary,
            location = location,
            meetingDate = meetingDate,
            recommendation = recommendation,
            weather = weather,
            directions = ""
        )
    }

    fun release() {
        closeActiveConversation()
        engine = null
    }
}