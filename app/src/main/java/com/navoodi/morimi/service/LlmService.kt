package com.navoodi.morimi.service

import android.content.Context
import android.util.Log
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.Participant
import com.navoodi.morimi.data.repository.FeedbackRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    suspend fun initialize() = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            if (engine != null) return@withContext          // 락 안에서 이중 확인 — 중복 엔진 생성 방지
            val config = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
            val local = Engine(config)
            try {
                local.initialize()
                engine = local                              // 초기화 성공 후에만 필드에 대입
            } catch (e: Throwable) {
                // 실패 시 필드를 null로 유지 → 다음 호출에서 재시도 가능 (미초기화 엔진 오염 방지)
                runCatching { (local as? AutoCloseable)?.close() }
                throw e
            }
        }
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

    /**
     * 채팅을 성향 상태 JSON으로 압축한다.
     *
     * 1순위 — **constrained decoding**(툴 스키마): `record_status` 툴의 JSON 스키마로 툴콜
     * 페이로드를 문법 제약해, 깨진 JSON이 구조적으로 불가능하게 만든다(B② 스파이크 실증).
     * 툴콜 args(Map)를 스키마 JSON 문자열로 직렬화해 반환 — [StatusCompressionPipeline]이
     * 기존 그대로 파싱한다(포트/폴백/방어층 무변경).
     *
     * 2순위 — **자유텍스트 폴백**: 툴콜이 안 나오거나 예외 시 기존 프롬프트 유도 방식으로
     * 되돌아간다(정규식 repair가 방어). constrained가 조용히 품질만 떨어뜨리지 않게 안전망 유지.
     */
    suspend fun compressChatToStatus(messages: List<Message>): String = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }
            val transcript = messages.joinToString("\n") { "[${it.senderName}]: ${it.content}" }

            runCatching { compressViaConstrainedTool(eng, transcript) }
                .onFailure { Log.w("LlmService", "constrained 압축 실패 → 자유텍스트 폴백", it) }
                .getOrNull()
                ?.let {
                    Log.d("LlmService", "압축(constrained) 결과: $it")
                    return@withContext it
                }

            compressViaFreeText(eng, transcript)
        }
    }

    suspend fun summarizeForPrivacy(messages: List<Message>): String = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }
            val contentOnly = messages.joinToString("\n") { it.content }

            val prompt = """
다음 채팅 내용을 읽고, 개인정보(이름, 연락처 등)가 포함되지 않은 2~3문장의 요약문을 작성하라.
요약문에는 날짜(또는 요일), 대략적인 장소, 모임 목적을 반드시 포함해야 한다.
참가자 이름은 절대 언급하지 말고, 별표·샵·대괄호 같은 마크다운 기호 없이 일반 텍스트로만 출력하라.

채팅 내용:
$contentOnly
""".trimIndent()

            val conv = eng.createConversation()
            try {
                conv.sendMessage(prompt).toString()
            } finally {
                closeConversation(conv)
            }
        }
    }

    // ── constrained decoding (툴 스키마) 압축 경로 — B② 채택 (DEVLOG 2026-07-14) ──
    //
    // litertlm 0.11: ExperimentalFlags.enableConversationConstrainedDecoding + 툴 스키마로
    // 툴콜 페이로드가 문법 제약된다. getToolDescriptionJsonString()는 공식 Kotlin 가이드
    // 포맷(name/description/parameters). ExperimentalFlags는 전역 상태라 호출 구간에서만 켜고 원복.

    /** 성향 추출 스키마를 실은 제약 디코딩용 툴 — execute는 automaticToolCalling=false라 미사용. */
    private class StatusRecorderTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = """
        {
          "name": "record_status",
          "description": "채팅에서 추출한 참석자·선호/불호·가능 일정을 기록한다",
          "parameters": {
            "type": "object",
            "properties": {
              "participants": {
                "type": "array",
                "items": { "type": "string" },
                "description": "대화에 등장하는 참석자 이름 목록"
              },
              "preferences": {
                "type": "array",
                "items": { "type": "string" },
                "description": "선호/불호 항목. 좋아요:/싫어요: 접두사를 붙인다"
              },
              "availability": {
                "type": "array",
                "items": { "type": "string" },
                "description": "가능/불가능한 날짜·요일·시간대"
              }
            },
            "required": ["participants", "preferences", "availability"]
          }
        }
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = "{}"
    }

    /**
     * constrained decoding으로 압축 — record_status 툴콜을 유도하고 args(Map)를 스키마 JSON
     * 문자열로 직렬화해 반환한다. 툴콜이 없으면 null(호출측이 자유텍스트로 폴백).
     * engineMutex가 잡힌 상태에서 호출된다(재잠금 없음).
     */
    @OptIn(ExperimentalApi::class)
    private fun compressViaConstrainedTool(eng: Engine, transcript: String): String? {
        val prompt = """
            다음 채팅에서 참석자, 선호/불호, 가능 일정을 추출해 record_status 도구를 호출하라.
            선호/불호에는 "좋아요:", "싫어요:" 접두사를 붙여라.

            채팅:
            $transcript
        """.trimIndent()

        val prev = ExperimentalFlags.enableConversationConstrainedDecoding
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        return try {
            val conv = eng.createConversation(
                ConversationConfig(
                    tools = listOf(tool(StatusRecorderTool())),
                    automaticToolCalling = false,
                )
            )
            try {
                val call = conv.sendMessage(prompt).toolCalls.firstOrNull() ?: return null
                StatusToolJson.encode(call.arguments)
            } finally {
                closeConversation(conv)
            }
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = prev
        }
    }

    /** 폴백 — 자유텍스트 프롬프트로 JSON을 유도(기존 동작). 정규식 repair가 방어한다. */
    private fun compressViaFreeText(eng: Engine, transcript: String): String {
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
        return try {
            conv.sendMessage(prompt).toString().also { Log.d("LlmService", "압축(freetext) 결과 raw: $it") }
        } finally {
            closeConversation(conv)
        }
    }

    // [Phase 1] 단방향 LLM 파이프라인 비활성화 — Phase 2 자율 검증 하네스 에이전트로 교체 예정
    @Suppress("UNUSED_PARAMETER")
    suspend fun runPipeline(
        roomId: String,
        messages: List<Message>,
        roomParticipants: List<Participant> = emptyList(),
        userStatus: UserStatusEntity? = null
    ): MeetingSummary {
        throw UnsupportedOperationException(
            "[Phase 1] 단방향 runPipeline 비활성화됨 — Phase 2 하네스 에이전트로 교체 예정"
        )

        /* ── 레거시 단방향 파이프라인 (비활성화) ────────────────────────────
        val eng = checkNotNull(engine) { "엔진이 초기화되지 않았습니다" }
        closeActiveConversation()

        val senderIdToParticipant = roomParticipants.associateBy { it.id }
        val transcript = messages.joinToString("\n") { msg ->
            val p = senderIdToParticipant[msg.senderId]
            val tag = if (p != null) "[${p.name}]" else "[${msg.senderName}]"
            "$tag: ${msg.content}"
        }

        val chatDate = messages.firstOrNull()?.let {
            Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now()

        // Conv A: 요약/장소/날짜/도시 순차 추출 (단방향 4-shot)
        val convA = eng.createConversation()
        activeConversation = convA
        val summary: String; val location: String; val meetingDate: String; val city: String
        try {
            summary = withContext(Dispatchers.IO) {
                convA.sendMessage("아래 모임 대화를 읽고 핵심 내용을 3~5문장으로 정리해줘. $NO_MARKDOWN\n\n$transcript").toString()
            }
            location = withContext(Dispatchers.IO) {
                convA.sendMessage("위 대화에서 모임 장소 이름만 짧게 알려줘. $NO_MARKDOWN").toString()
            }
            meetingDate = withContext(Dispatchers.IO) {
                val raw = convA.sendMessage(
                    "대화가 이루어진 날짜는 $chatDate 입니다. 위 대화에서 최종 확정한 날짜를 YYYY-MM-DD 형식으로만. $NO_MARKDOWN"
                ).toString()
                Regex("\\d{4}-\\d{2}-\\d{2}").find(raw)?.value ?: "미정"
            }
            city = withContext(Dispatchers.IO) {
                convA.sendMessage("위 모임 장소 도시를 한국어로만. $NO_MARKDOWN").toString().trim()
            }
        } finally { closeActiveConversation() }

        val ragContext = feedbackRepository.buildRagContext()
        val recommendation = geminiService.recommend(summary, location, meetingDate, city, ragContext, userStatus)
        val weather = WeatherService.getWeather(city, meetingDate)

        return MeetingSummary(roomId, summary, location, meetingDate, recommendation, weather, "")
        ── 레거시 단방향 파이프라인 끝 ─────────────────────────────────────── */
    }

    fun release() {
        closeActiveConversation()
        engine = null
    }
}