package com.example.gemma4.service

import android.util.Log
import com.example.gemma4.BuildConfig
import com.example.gemma4.data.local.UserStatusEntity
import com.example.gemma4.data.model.MeetingSummary
import com.example.gemma4.data.model.Message
import com.example.gemma4.data.model.Participant
import com.example.gemma4.data.repository.FeedbackRepository
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ── 오케스트레이터 결과 ────────────────────────────────────────────────────────
sealed class OrchestratorResult {
    data class Success(val summary: MeetingSummary, val attempts: Int) : OrchestratorResult()
    data class Failed(val reason: String, val attempts: Int) : OrchestratorResult()
}

/**
 * 온디바이스-클라우드 하이브리드 하네스의 통제실.
 *
 * 루프 흐름:
 *   1) Gemini에 시스템 프롬프트 전송
 *   2) Function Calling 처리 (getWeather / searchPlace)
 *   3) GuardrailService 팩트 체크
 *   4) 실패 시 피드백을 컨텍스트에 누적 후 최대 [MAX_ATTEMPTS]회 재시도
 *   5) 통과한 결과만 [OrchestratorResult.Success]로 반환
 */
class AgentOrchestrator(
    private val guardrailService: GuardrailService,
    private val feedbackRepository: FeedbackRepository,
    private val apiKey: String = BuildConfig.GEMINI_API_KEY
) {
    companion object {
        private const val TAG = "AgentOrchestrator"
        private const val MAX_ATTEMPTS = 3
        private const val MODEL_NAME = "gemini-3.5-flash"
    }

    // ── Gemini Tool 스키마 선언 ───────────────────────────────────────────────

    private val getWeatherDecl: FunctionDeclaration = FunctionDeclaration.builder()
        .name("getWeather")
        .description("기상청 API를 통해 특정 도시의 날씨 예보를 조회합니다")
        .parameters(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    mapOf(
                        "city" to Schema.builder()
                            .type("STRING")
                            .description("도시명 (한국어, 예: 서울, 부산, 홍대)")
                            .build(),
                        "date" to Schema.builder()
                            .type("STRING")
                            .description("날짜 YYYY-MM-DD 형식. 미확정이면 '미정'")
                            .build()
                    )
                )
                .required(listOf("city", "date"))
                .build()
        )
        .build()

    private val searchPlaceDecl: FunctionDeclaration = FunctionDeclaration.builder()
        .name("searchPlace")
        .description("카카오맵 API로 모임 장소 후보를 검색합니다")
        .parameters(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    mapOf(
                        "query" to Schema.builder()
                            .type("STRING")
                            .description("검색어 (예: 강남 이탈리안 레스토랑, 홍대 조용한 카페)")
                            .build(),
                        "city" to Schema.builder()
                            .type("STRING")
                            .description("도시명 (한국어)")
                            .build()
                    )
                )
                .required(listOf("query", "city"))
                .build()
        )
        .build()

    private val genConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .tools(
            listOf(
                Tool.builder()
                    .functionDeclarations(listOf(getWeatherDecl, searchPlaceDecl))
                    .build()
            )
        )
        .build()

    // ── 내부 전송 결과 래퍼 ──────────────────────────────────────────────────
    private data class GeminiCallResult(
        val summary: MeetingSummary,
        val city: String
    )

    // ── 공개 진입점 ──────────────────────────────────────────────────────────

    suspend fun orchestrate(
        roomId: String,
        messages: List<Message>,
        roomParticipants: List<Participant>,
        userStatus: UserStatusEntity?,
        chatDate: LocalDate = LocalDate.now()
    ): OrchestratorResult = withContext(Dispatchers.IO) {

        val transcript = buildTranscript(messages, roomParticipants)
        val ragContext = feedbackRepository.buildRagContext()
        val basePrompt = buildSystemPrompt(transcript, chatDate, userStatus, ragContext)

        var attempt = 0
        var accumulatedFeedback = ""

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            Log.d(TAG, "하네스 루프 $attempt/$MAX_ATTEMPTS 시작")

            val prompt = if (accumulatedFeedback.isBlank()) basePrompt
            else buildString {
                append(basePrompt)
                append("\n\n[시스템 검증 피드백 — 반드시 반영할 것]\n")
                append(accumulatedFeedback)
            }

            try {
                val callResult = callGeminiWithTools(prompt, roomId)
                val guardrail = guardrailService.verify(
                    text = callResult.summary.recommendation,
                    city = callResult.city
                )
                Log.d(TAG, "시도 $attempt Guardrail: passed=${guardrail.passed}")

                if (guardrail.passed) {
                    Log.d(TAG, "Guardrail 통과 ✓ — 총 $attempt 회")
                    return@withContext OrchestratorResult.Success(callResult.summary, attempt)
                }

                accumulatedFeedback = if (accumulatedFeedback.isBlank()) guardrail.feedbackForRetry
                else "$accumulatedFeedback\n${guardrail.feedbackForRetry}"
                Log.w(TAG, "시도 $attempt Guardrail 실패 → 자가 수정 피드백 누적 후 재시도")

            } catch (e: Exception) {
                Log.e(TAG, "시도 $attempt 예외", e)
                if (attempt >= MAX_ATTEMPTS) {
                    return@withContext OrchestratorResult.Failed("예외: ${e.message}", attempt)
                }
            }
        }

        OrchestratorResult.Failed("최대 재시도 횟수($MAX_ATTEMPTS) 초과", MAX_ATTEMPTS)
    }

    // ── Gemini Function Calling 실행 ─────────────────────────────────────────

    private suspend fun callGeminiWithTools(prompt: String, roomId: String): GeminiCallResult {
        if (apiKey.isBlank() || apiKey.startsWith("여기에")) {
            Log.w(TAG, "GEMINI_API_KEY 미설정 → Mock 결과 반환")
            return GeminiCallResult(buildMockSummary(roomId), "미정")
        }

        val client = Client.builder().apiKey(apiKey).build()

        // 대화 히스토리 직접 관리 (chats 모듈 없음 → generateContent에 Content 리스트 전달)
        val history = mutableListOf<Content>()
        history.add(
            Content.builder()
                .role("user")
                .parts(listOf(Part.builder().text(prompt).build()))
                .build()
        )

        var response = withContext(Dispatchers.IO) {
            client.models.generateContent(MODEL_NAME, history, genConfig)
        }

        var weatherResult = "날씨 정보 없음"
        var city = "미정"
        var meetingDate = "미정"

        while (true) {
            @Suppress("UNCHECKED_CAST")
            val fcList: List<FunctionCall> =
                (response.functionCalls() as? List<FunctionCall>) ?: emptyList()
            if (fcList.isEmpty()) break

            // 모델 응답을 히스토리에 추가
            response.candidates()?.orElse(null)?.firstOrNull()?.content()?.orElse(null)
                ?.let { history.add(it) }

            val funcParts = mutableListOf<Part>()
            for (fc in fcList) {
                val fcName = fc.name().orElse(null) ?: continue
                @Suppress("UNCHECKED_CAST")
                val args = fc.args().orElse(null) as? Map<String, Any?> ?: emptyMap()
                val result = dispatchFunctionCall(fcName, args, city, meetingDate)

                when (fcName) {
                    "getWeather" -> {
                        city = args["city"]?.toString()?.removeSurrounding("\"") ?: city
                        meetingDate = args["date"]?.toString()?.removeSurrounding("\"") ?: meetingDate
                        weatherResult = result
                    }
                    "searchPlace" -> city = args["city"]?.toString()?.removeSurrounding("\"") ?: city
                }
                Log.d(TAG, "함수 실행: $fcName → ${result.take(80)}")

                funcParts.add(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .name(fcName)
                                .response(mapOf("result" to result))
                                .build()
                        )
                        .build()
                )
            }

            // 함수 응답을 히스토리에 추가 후 재전송
            history.add(
                Content.builder()
                    .role("user")
                    .parts(funcParts)
                    .build()
            )
            response = withContext(Dispatchers.IO) {
                client.models.generateContent(MODEL_NAME, history, genConfig)
            }
        }

        val recommendation = response.text() ?: "추천 정보를 생성하지 못했습니다."

        return GeminiCallResult(
            summary = MeetingSummary(
                roomId = roomId,
                summary = recommendation.lines().take(5).joinToString("\n"),
                location = city,
                meetingDate = meetingDate,
                recommendation = recommendation,
                weather = weatherResult,
                directions = ""
            ),
            city = city
        )
    }

    private suspend fun dispatchFunctionCall(
        name: String,
        args: Map<String, Any?>,
        currentCity: String,
        currentDate: String
    ): String = when (name) {
        "getWeather" -> {
            val c = args["city"]?.toString()?.removeSurrounding("\"")?.ifBlank { currentCity } ?: currentCity
            val d = args["date"]?.toString()?.removeSurrounding("\"")?.ifBlank { currentDate } ?: currentDate
            WeatherService.getWeather(c, d)
        }
        "searchPlace" -> {
            val query = args["query"]?.toString()?.removeSurrounding("\"") ?: ""
            val c = args["city"]?.toString()?.removeSurrounding("\"")?.ifBlank { currentCity } ?: currentCity
            mockPlaceSearch(query, c)
        }
        else -> {
            Log.w(TAG, "알 수 없는 함수 호출: $name")
            "함수 미지원: $name"
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun buildTranscript(messages: List<Message>, participants: List<Participant>): String {
        val idToName = participants.associateBy { it.id }
        return messages.joinToString("\n") { msg ->
            val name = idToName[msg.senderId]?.name ?: msg.senderName
            "[$name]: ${msg.content}"
        }
    }

    private fun buildSystemPrompt(
        transcript: String,
        chatDate: LocalDate,
        userStatus: UserStatusEntity?,
        ragContext: String
    ): String {
        val profileSection = if (userStatus != null) buildString {
            appendLine("\n[참가자 프로필 — 최우선 반영]")
            appendLine("참가자: ${userStatus.participants.joinToString(", ").ifBlank { "정보 없음" }}")
            appendLine("선호/불호: ${userStatus.preferences.joinToString(", ").ifBlank { "없음" }}")
            append("가능 일정: ${userStatus.availability.joinToString(", ").ifBlank { "미정" }}")
        } else "\n[참가자 프로필]\n없음 (메시지 10개 이상 후 자동 생성됩니다)"

        val ragSection = if (ragContext.isNotBlank()) "\n[과거 피드백 이력]\n$ragContext" else ""

        return """
당신은 모임 AI 비서입니다. 아래 채팅 로그를 분석해 장소·활동·날씨 기반 추천을 제공하세요.
필요하다면 getWeather, searchPlace 도구를 호출해 실시간 정보를 수집하세요.

[대화 날짜] $chatDate
$profileSection
$ragSection

[채팅 로그]
$transcript

[지침]
1. getWeather 도구로 모임 날짜와 도시의 날씨를 반드시 조회하세요
2. searchPlace 도구로 후보 장소를 검색하세요
3. 장소 2~3곳, 활동 2~3가지, 챙겨갈 것 3~5가지를 추천하세요
4. 별표·샵·대괄호 등 마크다운 기호 없이 일반 텍스트로만 답하세요
5. 참가자 프로필의 선호/불호와 일정 제약을 엄격히 반영하세요
""".trimIndent()
    }

    private fun mockPlaceSearch(query: String, city: String): String {
        val type = query.split(" ").lastOrNull() ?: "장소"
        return """
[$city 카카오맵 검색 결과 — Mock]
1. $city $type 추천 A점 — 영업 중, 평점 4.5, 주차 가능
2. $city $type 추천 B점 — 영업 중, 평점 4.3, 웨이팅 있음
3. $city ${query.split(" ").firstOrNull() ?: "근처"} 분위기 맛집 — 영업 중, 평점 4.1
""".trimIndent()
    }

    private fun buildMockSummary(roomId: String) = MeetingSummary(
        roomId = roomId,
        summary = "[Mock] GEMINI_API_KEY 미설정 — local.properties에 키를 추가하세요",
        location = "미정",
        meetingDate = "미정",
        recommendation = "[Mock] API 키를 설정하면 Gemini Function Calling 기반 실제 추천이 제공됩니다.",
        weather = "날씨 정보 없음",
        directions = ""
    )
}
