package com.navoodi.morimi.service

import android.util.Log
import com.navoodi.morimi.BuildConfig
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.pipeline.OnDeviceLlmPort
import com.navoodi.morimi.data.repository.FeedbackRepository
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.Tool
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ── 오케스트레이터 결과 ────────────────────────────────────────────────────────
sealed class OrchestratorResult {
    data class Success(val summary: MeetingSummary, val attempts: Int) : OrchestratorResult()
    data class Failed(val reason: String, val attempts: Int) : OrchestratorResult()
}

// ── 에이전트 생명주기 이벤트 ──────────────────────────────────────────────────
sealed class AgentEvent {
    data class OrchestrationStarted(val roomId: String, val messageCount: Int) : AgentEvent()
    data class GemmaSummaryCompleted(val summary: String) : AgentEvent()
    data class PromptGenerated(val attempt: Int, val prompt: String) : AgentEvent()
    data class ToolCalled(val name: String, val args: Map<String, Any?>, val result: String) : AgentEvent()
    data class JsonParsed(val rawJson: String) : AgentEvent()
    data class GuardrailEvaluated(
        val attempt: Int,
        val passed: Boolean,
        val feedback: String,
        val unknownCount: Int = 0,
    ) : AgentEvent()
    data class OrchestrationFinished(val success: Boolean, val attempts: Int, val reason: String? = null) : AgentEvent()
}

interface AgentEventTracker {
    fun onEvent(event: AgentEvent)
}

/**
 * 온디바이스-클라우드 하이브리드 하네스의 통제실.
 *
 * 프라이버시 방화벽 흐름:
 *   1) onDeviceLlm.summarizeForPrivacy() — 채팅 원문을 디바이스 내에서 익명화 요약
 *   2) 요약문만 Gemini에 전송 (채팅 원문은 절대 클라우드로 나가지 않음)
 *   3) Gemini Function Calling 처리 (getWeather / searchPlace)
 *   4) GuardrailService 팩트 체크
 *   5) 실패 시 피드백을 컨텍스트에 누적 후 최대 [MAX_ATTEMPTS]회 재시도
 *   6) 통과한 결과만 [OrchestratorResult.Success]로 반환
 */
class AgentOrchestrator(
    private val guardrailService: GuardrailService,
    private val feedbackRepository: FeedbackRepository,
    private val onDeviceLlm: OnDeviceLlmPort,
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

    private val responseSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "summary" to Schema.builder()
                    .type("STRING")
                    .description("모임 전체 요약 (날씨·장소·분위기 포함)")
                    .build(),
                "recommendedPlaces" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("추천 장소 목록 (2~3곳)")
                    .build(),
                "recommendedActivities" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("추천 활동 목록 (2~3가지)")
                    .build(),
                "itemsToBring" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("챙겨갈 것 목록 (3~5가지)")
                    .build()
            )
        )
        .required(listOf("summary", "recommendedPlaces", "recommendedActivities", "itemsToBring"))
        .build()

    private val genConfig: GenerateContentConfig = GenerateContentConfig.builder()
        .tools(
            listOf(
                Tool.builder()
                    .functionDeclarations(listOf(getWeatherDecl, searchPlaceDecl))
                    .build()
            )
        )
        .responseMimeType("application/json")
        .responseSchema(responseSchema)
        .build()

    // ── 내부 전송 결과 래퍼 ──────────────────────────────────────────────────
    private data class GeminiCallResult(
        val summary: MeetingSummary,
        val city: String
    )

    private data class FcCallResult(
        val name: String,
        val args: Map<String, Any?>,
        val result: String,
        val kakaoPlaces: List<KakaoPlace> = emptyList()
    )

    // ── 공개 진입점 ──────────────────────────────────────────────────────────

    suspend fun orchestrate(
        roomId: String,
        messages: List<Message>,
        userStatus: UserStatusEntity?,
        chatDate: LocalDate = LocalDate.now(),
        eventTracker: AgentEventTracker? = null
    ): OrchestratorResult = withContext(Dispatchers.IO) {

        eventTracker?.onEvent(AgentEvent.OrchestrationStarted(roomId, messages.size))

        // 채팅 원문은 온디바이스에서 익명화 — 이 결과만 클라우드로 전송
        Log.d(TAG, "Gemma 1차 요약 시작 (온디바이스)")
        val gemmaSum = onDeviceLlm.summarizeForPrivacy(messages)
        Log.d(TAG, "Gemma 요약 완료: ${gemmaSum.take(80)}")
        eventTracker?.onEvent(AgentEvent.GemmaSummaryCompleted(gemmaSum))

        val ragContext = feedbackRepository.buildRagContext()
        val basePrompt = buildSystemPrompt(gemmaSum, chatDate, userStatus, ragContext)

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
            eventTracker?.onEvent(AgentEvent.PromptGenerated(attempt, prompt))

            try {
                val callResult = callGeminiWithTools(prompt, roomId, eventTracker)
                val guardrail = guardrailService.verify(
                    placeNames = callResult.summary.places.map { it.name },
                    city = callResult.city
                )
                val unknownCount = guardrail.verifiedPlaces.count { it.status == PlaceStatus.UNKNOWN }
                Log.d(TAG, "시도 $attempt Guardrail: passed=${guardrail.passed} unknown=$unknownCount")
                eventTracker?.onEvent(
                    AgentEvent.GuardrailEvaluated(attempt, guardrail.passed, guardrail.feedbackForRetry, unknownCount)
                )

                if (guardrail.passed) {
                    Log.d(TAG, "Guardrail 통과 ✓ — 총 $attempt 회")
                    eventTracker?.onEvent(AgentEvent.OrchestrationFinished(true, attempt))
                    val enriched = applyVerification(callResult.summary, guardrail.verifiedPlaces)
                    return@withContext OrchestratorResult.Success(enriched, attempt)
                }

                accumulatedFeedback = if (accumulatedFeedback.isBlank()) guardrail.feedbackForRetry
                else "$accumulatedFeedback\n${guardrail.feedbackForRetry}"
                Log.w(TAG, "시도 $attempt Guardrail 실패 → 자가 수정 피드백 누적 후 재시도")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "시도 $attempt 예외", e)
                if (attempt >= MAX_ATTEMPTS) {
                    val reason = "예외: ${e.message}"
                    eventTracker?.onEvent(AgentEvent.OrchestrationFinished(false, attempt, reason))
                    return@withContext OrchestratorResult.Failed(reason, attempt)
                }
            }
        }

        val reason = "최대 재시도 횟수($MAX_ATTEMPTS) 초과"
        eventTracker?.onEvent(AgentEvent.OrchestrationFinished(false, MAX_ATTEMPTS, reason))
        OrchestratorResult.Failed(reason, MAX_ATTEMPTS)
    }

    // ── Gemini Function Calling 실행 ─────────────────────────────────────────

    private suspend fun callGeminiWithTools(
        prompt: String,
        roomId: String,
        eventTracker: AgentEventTracker?
    ): GeminiCallResult {
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
        val collectedKakaoPlaces = mutableListOf<KakaoPlace>()

        while (true) {
            @Suppress("UNCHECKED_CAST")
            val fcList: List<FunctionCall> =
                (response.functionCalls() as? List<FunctionCall>) ?: emptyList()
            if (fcList.isEmpty()) break

            // 모델 응답을 히스토리에 추가
            response.candidates()?.orElse(null)?.firstOrNull()?.content()?.orElse(null)
                ?.let { history.add(it) }

            // 1단계: 모든 함수 호출을 병렬 실행 — 스냅샷으로 공유 상태 격리
            val callResults: List<FcCallResult> = coroutineScope {
                fcList.mapNotNull { fc ->
                    val fcName = fc.name().orElse(null) ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val args = fc.args().orElse(null) as? Map<String, Any?> ?: emptyMap()
                    val snapCity = city
                    val snapDate = meetingDate
                    async {
                        val (resultText, kPlaces) = dispatchFunctionCall(fcName, args, snapCity, snapDate)
                        Log.d(TAG, "함수 실행: $fcName → ${resultText.take(80)}")
                        FcCallResult(fcName, args, resultText, kPlaces)
                    }
                }.awaitAll()
            }

            // 2단계: 단일 스레드에서 순차적으로 상태 업데이트 → Race Condition 없음
            val funcParts = mutableListOf<Part>()
            for (r in callResults) {
                eventTracker?.onEvent(AgentEvent.ToolCalled(r.name, r.args, r.result))
                when (r.name) {
                    "getWeather" -> {
                        city = r.args["city"]?.toString()?.removeSurrounding("\"") ?: city
                        meetingDate = r.args["date"]?.toString()?.removeSurrounding("\"") ?: meetingDate
                        weatherResult = r.result
                    }
                    "searchPlace" -> {
                        city = r.args["city"]?.toString()?.removeSurrounding("\"") ?: city
                        collectedKakaoPlaces.addAll(r.kakaoPlaces)
                    }
                }
                funcParts.add(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .name(r.name)
                                .response(mapOf("result" to r.result))
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

        val jsonText = response.text()
            ?: throw IllegalStateException("Gemini 응답이 비어 있습니다")

        eventTracker?.onEvent(AgentEvent.JsonParsed(jsonText))

        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "JSON 파싱 실패: $jsonText", e)
            throw IllegalStateException("구조화된 응답 파싱 실패: ${e.message}")
        }

        fun JSONObject.stringList(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }

        val summaryText       = json.optString("summary", "요약 없음")
        val places            = json.stringList("recommendedPlaces")
        val activities        = json.stringList("recommendedActivities")
        val items             = json.stringList("itemsToBring")

        val recommendation = buildString {
            appendLine("장소 추천")
            places.forEach { appendLine("• $it") }
            appendLine("\n활동 추천")
            activities.forEach { appendLine("• $it") }
            appendLine("\n챙겨갈 것")
            items.forEach { appendLine("• $it") }
        }.trim()

        val placesStructured = places.map { geminiStr ->
            val (pName, reason) = parseGeminiPlaceEntry(geminiStr)
            val matched = findKakaoMatch(pName, collectedKakaoPlaces)
            RecommendedPlace(
                name = pName.ifBlank { geminiStr },
                address = matched?.let { it.roadAddress.ifBlank { it.address } } ?: "",
                reason = reason,
                placeUrl = matched?.url ?: ""
            )
        }

        Log.d(TAG, "JSON 파싱 완료 — 장소 ${places.size}곳(매칭 ${placesStructured.count { it.placeUrl.isNotBlank() }}개), 활동 ${activities.size}개, 준비물 ${items.size}개")

        return GeminiCallResult(
            summary = MeetingSummary(
                roomId = roomId,
                summary = summaryText,
                location = city,
                meetingDate = meetingDate,
                recommendation = recommendation,
                weather = weatherResult,
                directions = "",
                places = placesStructured,
                activities = activities,
                itemsToBring = items
            ),
            city = city
        )
    }

    private suspend fun dispatchFunctionCall(
        name: String,
        args: Map<String, Any?>,
        currentCity: String,
        currentDate: String
    ): Pair<String, List<KakaoPlace>> = when (name) {
        "getWeather" -> {
            val c = args["city"]?.toString()?.removeSurrounding("\"")?.ifBlank { currentCity } ?: currentCity
            val d = args["date"]?.toString()?.removeSurrounding("\"")?.ifBlank { currentDate } ?: currentDate
            WeatherService.getWeather(c, d) to emptyList()
        }
        "searchPlace" -> {
            val query = args["query"]?.toString()?.removeSurrounding("\"") ?: ""
            val kakaoPlaces = KakaoLocalService.searchKeyword(query)
            val text = if (kakaoPlaces.isEmpty()) {
                "검색 결과 없음"
            } else {
                kakaoPlaces.joinToString("\n") { p ->
                    val addr = p.roadAddress.ifBlank { p.address }
                    buildString {
                        append("• ${p.name}")
                        if (addr.isNotBlank()) append(" ($addr)")
                        if (p.phone.isNotBlank()) append(", ☎ ${p.phone}")
                        if (p.url.isNotBlank()) append(", 지도: ${p.url}")
                    }
                }
            }
            text to kakaoPlaces
        }
        else -> {
            Log.w(TAG, "알 수 없는 함수 호출: $name")
            "함수 미지원: $name" to emptyList()
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun parseGeminiPlaceEntry(s: String): Pair<String, String> {
        // 장소명 = 주소 괄호'(' 또는 이유 구분 대시(—/–) 중 가장 먼저 나오는 지점 이전.
        // ASCII '-'는 주소("상계로1길 14-11")·전화번호에 흔하므로 구분자로 쓰지 않는다.
        val nameEnd = listOf(s.indexOf('('), s.indexOf('—'), s.indexOf('–'))
            .filter { it >= 0 }
            .minOrNull() ?: s.length
        val name = s.substring(0, nameEnd).trim()
        // 이유 = em/en 대시 뒤 (없으면 빈 문자열)
        val dashIdx = s.indexOfFirst { it == '—' || it == '–' }
        val reason = if (dashIdx >= 0) s.substring(dashIdx + 1).trim() else ""
        return (name.ifBlank { s.trim() }) to reason
    }

    /**
     * Guardrail 검증 결과(PlaceVerification 상태)를 요약 결과의 장소 목록에 병합한다.
     * 검증 후보에서 제외된 장소(길이 필터 등)는 기본값 UNVERIFIED로 남는다.
     */
    private fun applyVerification(
        summary: MeetingSummary,
        verified: List<PlaceVerification>,
    ): MeetingSummary {
        if (summary.places.isEmpty()) return summary
        val statusByName = verified.associate { it.name to it.status }
        val updatedPlaces = summary.places.map { place ->
            val status = statusByName[place.name] ?: return@map place
            val v = when (status) {
                PlaceStatus.OPEN -> VerificationStatus.VERIFIED
                PlaceStatus.CLOSED -> VerificationStatus.NOT_FOUND
                PlaceStatus.UNKNOWN -> VerificationStatus.UNVERIFIED
            }
            place.copy(verification = v)
        }
        return summary.copy(places = updatedPlaces)
    }

    private fun findKakaoMatch(name: String, candidates: List<KakaoPlace>): KakaoPlace? {
        if (name.isBlank() || candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name == name }
            ?: candidates.firstOrNull { it.name.contains(name) || name.contains(it.name) }
    }

    private fun buildSystemPrompt(
        gemmaSum: String,
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
당신은 모임 AI 비서입니다. 아래 Gemma 1차 요약문(개인정보 제거됨)을 바탕으로 장소·활동·날씨 기반 추천을 제공하세요.
필요하다면 getWeather, searchPlace 도구를 호출해 실시간 정보를 수집하세요.

[대화 날짜] $chatDate
$profileSection
$ragSection

[Gemma 1차 요약]
$gemmaSum

[지침]
1. 요약문에서 모임 날짜를 파악하고 반드시 YYYY-MM-DD 형식으로 변환하세요. 대화 날짜($chatDate)를 기준으로 상대적 표현("이번 토요일" 등)을 절대 날짜로 계산하세요.
2. getWeather 도구로 모임 날짜와 도시의 날씨를 반드시 조회하세요.
3. searchPlace 도구로 후보 장소를 검색하세요.
4. 장소 2~3곳, 활동 2~3가지, 챙겨갈 것 3~5가지를 추천하세요.
5. 반드시 제공된 JSON 스키마에 맞춰 응답하세요.
6. 참가자 프로필의 선호/불호와 일정 제약을 엄격히 반영하세요.
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
