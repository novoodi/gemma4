package com.navoodi.morimi.service

import android.util.Log
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.MeetingSummary
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.MemberScope
import com.navoodi.morimi.data.pipeline.MessageOrder
import com.navoodi.morimi.data.pipeline.OnDeviceLlmPort
import com.navoodi.morimi.data.repository.MetricsRepository
import org.json.JSONArray
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
sealed class AssistantEvent {
    data class OrchestrationStarted(val roomId: String, val messageCount: Int) : AssistantEvent()
    /**
     * 현재 멤버 기준 범위 결정(2026-10, DEFECT_TEST S2). 나간 사람 메시지 [droppedMessages]건을 뺐고,
     * 프로필 선호·불호를 썼는지([profileUsed]) — 나간 사람이 섞였을 수 있는 프로필은 쓰지 않는다.
     */
    data class MembersScoped(
        val memberCount: Int,
        val droppedMessages: Int,
        val departedSenders: Int,
        val profileUsed: Boolean,
    ) : AssistantEvent()
    data class GemmaSummaryCompleted(
        val summary: String,
        val redactions: Int = 0,
        val redactionsByCategory: Map<String, Int> = emptyMap(),
    ) : AssistantEvent()
    data class PromptGenerated(val attempt: Int, val prompt: String) : AssistantEvent()
    data class ToolCalled(val name: String, val args: Map<String, Any?>, val result: String) : AssistantEvent()
    data class JsonParsed(val rawJson: String) : AssistantEvent()
    data class GuardrailEvaluated(
        val attempt: Int,
        val passed: Boolean,
        val feedback: String,
        val unknownCount: Int = 0,
    ) : AssistantEvent()
    data class ReflectionEvaluated(
        val attempt: Int,
        val passed: Boolean,
        val violations: List<String> = emptyList(),
    ) : AssistantEvent()
    /** 온디바이스 상황 분류 결과 — 경계를 넘는 것은 이 라벨 하나뿐이다. */
    data class ContextClassified(
        val context: String,
        val label: String,
        val confidence: Double,
        val signals: List<String> = emptyList(),
        val runnerUp: String? = null,
    ) : AssistantEvent()
    /** 거름망(정형화 게이트) 통과 결과 — 실제로 클라우드에 나가는 블록. */
    data class SieveNormalized(
        val frame: String,
        val missingSlots: List<String> = emptyList(),
    ) : AssistantEvent()
    /** 이 상황에 적용된 AHP 기준 가중치와 일관성 비율. */
    data class CriteriaWeighted(
        val context: String,
        val weights: List<Pair<String, Double>>,
        val cr: Double,
        val consistent: Boolean,
        val learnedDeltas: Int = 0,
        val droppedDeltas: Int = 0,
    ) : AssistantEvent()
    /** AHP 종합점수로 후보를 재정렬한 결과. */
    data class PlacesRanked(
        val attempt: Int,
        val ranking: List<String> = emptyList(),
        val changed: Boolean = false,
    ) : AssistantEvent()
    /** 이번 실행의 정확도·할루시네이션·적합도 측정치. */
    data class MetricsRecorded(val summary: String) : AssistantEvent()
    /** 불만 후기 게이트(2026-10, DEFECT_TEST S5) — 사용자가 불만을 남긴 장소를 결과에서 뺐다 */
    data class ComplaintsApplied(val attempt: Int, val blocked: List<String>, val keptCount: Int) : AssistantEvent()
    data class OrchestrationFinished(val success: Boolean, val attempts: Int, val reason: String? = null) : AssistantEvent()
}

interface AssistantEventTracker {
    fun onEvent(event: AssistantEvent)
}

/**
 * 온디바이스-클라우드 하이브리드 하네스의 통제실.
 *
 * 프라이버시 방화벽 흐름:
 *   1) onDeviceLlm.summarizeForPrivacy() — 채팅 원문을 디바이스 내에서 익명화 요약
 *   2) 요약문만 Gemini에 전송 (채팅 원문은 절대 클라우드로 나가지 않음).
 *      전송은 [GeminiGateway] → [CloudProxy](Functions 프록시) 경유 — API 키는 앱에 없다
 *   3) Gemini Function Calling 처리 (getWeather / searchPlace)
 *   4) GuardrailService 팩트 체크
 *   5) 실패 시 피드백을 컨텍스트에 누적 후 최대 [MAX_ATTEMPTS]회 재시도
 *   6) 통과한 결과만 [OrchestratorResult.Success]로 반환
 */
class AssistantOrchestrator(
    private val guardrailService: GuardrailService,
    private val feedbackRetriever: FeedbackRetriever,
    private val onDeviceLlm: OnDeviceLlmPort,
    /** 지표 기록·AHP 학습 저장소. null이면 프리셋 가중치로만 동작한다(테스트·DI 미구성 시). */
    private val metricsRepository: MetricsRepository? = null,
    private val geminiGateway: GeminiGateway = CloudGeminiGateway(),
) {
    companion object {
        private const val TAG = "AssistantOrchestrator"
        private const val MAX_ATTEMPTS = 3
        // 단일 시도 내 Function Calling 왕복 상한 — 모델이 계속 도구만 호출하며
        // 최종 응답을 내지 않는 상황에서 무한 루프를 방지(바깥 MAX_ATTEMPTS와 별개).
        private const val MAX_TOOL_ROUNDS = 8
        private const val MODEL_NAME = "gemini-3.5-flash"
        private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /**
         * Gemini 장소 문자열 1건 → [RecommendedPlace]. searchPlace 도구로 모은 카카오 결과와
         * 이름이 맞으면 주소·지도 링크·좌표를 채운다(좌표는 지도 핀용 — 깨진 값은 KakaoPlace에서 이미 null).
         * internal — JVM 단위 테스트에서 직접 검증.
         */
        internal fun toRecommendedPlace(
            geminiStr: String,
            kakaoPlaces: List<KakaoPlace>,
            city: String = "미정",
        ): RecommendedPlace {
            val (pName, reason) = parseGeminiPlaceEntry(geminiStr)
            val matched = findKakaoMatch(pName, kakaoPlaces, city)
            return RecommendedPlace(name = pName.ifBlank { geminiStr }, reason = reason).withKakao(matched)
        }

        /**
         * searchPlace 결과로 채우지 못한 장소를 Guardrail이 이미 찾아 둔 가게로 채운다(추가 카카오 호출 없음).
         *
         * 어느 쪽 매칭이든 같은 규칙([PlaceMatcher.bestMatch])으로 고른 가게다. 이미 searchPlace 매칭이
         * 있는 장소는 덮어쓰지 않되, 같은 가게(같은 placeUrl)인데 좌표만 빠졌다면 좌표를 보충한다 —
         * 다른 가게의 좌표를 섞지 않기 위해 링크로 동일성을 확인한다.
         */
        internal fun fillFromGuardrail(place: RecommendedPlace, matched: Map<String, KakaoPlace>): RecommendedPlace {
            val g = matched[place.name.trim()] ?: return place
            val hasSearchMatch = place.placeUrl.isNotBlank() || place.address.isNotBlank()
            return when {
                !hasSearchMatch -> place.withKakao(g)
                place.geoPoint == null && place.placeUrl.isNotBlank() && place.placeUrl == g.url ->
                    place.copy(latitude = g.latitude, longitude = g.longitude)
                else -> place
            }
        }

        private fun RecommendedPlace.withKakao(k: KakaoPlace?): RecommendedPlace = if (k == null) this else copy(
            address = k.roadAddress.ifBlank { k.address },
            placeUrl = k.url,
            latitude = k.latitude,
            longitude = k.longitude,
        )

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
         * Guardrail과 같은 규칙([PlaceMatcher.bestMatch] — 정규화 이름 일치 + 모임 시·도)으로 고른다.
         * 예전 단순 포함 비교는 "카페"에 "카페 모모"를, 서울 모임의 "미미식당"에 부산 지점을 붙였다.
         */
        private fun findKakaoMatch(name: String, candidates: List<KakaoPlace>, city: String): KakaoPlace? {
            if (name.isBlank() || candidates.isEmpty()) return null
            return PlaceMatcher.bestMatch(name, candidates, city)
        }
    }

    // ── Gemini Tool 스키마 선언 (REST 와이어 포맷 — GeminiWire) ────────────────

    private val getWeatherDecl: JSONObject = GeminiWire.functionDeclaration(
        name = "getWeather",
        description = "기상청 API를 통해 특정 도시의 날씨 예보를 조회합니다",
        parameters = GeminiWire.schema(
            type = "OBJECT",
            properties = mapOf(
                "city" to GeminiWire.schema("STRING", "도시명 (한국어, 예: 서울, 부산, 홍대)"),
                "date" to GeminiWire.schema("STRING", "날짜 YYYY-MM-DD 형식. 미확정이면 '미정'"),
            ),
            required = listOf("city", "date"),
        ),
    )

    private val searchPlaceDecl: JSONObject = GeminiWire.functionDeclaration(
        name = "searchPlace",
        description = "카카오맵 API로 모임 장소 후보를 검색합니다",
        parameters = GeminiWire.schema(
            type = "OBJECT",
            properties = mapOf(
                "query" to GeminiWire.schema("STRING", "검색어 (예: 강남 이탈리안 레스토랑, 홍대 조용한 카페)"),
                "city" to GeminiWire.schema("STRING", "도시명 (한국어)"),
            ),
            required = listOf("query", "city"),
        ),
    )

    private val functionDeclarations: JSONArray = JSONArray().put(getWeatherDecl).put(searchPlaceDecl)

    private val responseSchema: JSONObject = GeminiWire.schema(
        type = "OBJECT",
        properties = mapOf(
            "summary" to GeminiWire.schema("STRING", "모임 전체 요약 (날씨·장소·분위기 포함)"),
            "recommendedPlaces" to GeminiWire.schema(
                "ARRAY", "추천 장소 목록 (2~3곳)", items = GeminiWire.schema("STRING")
            ),
            "recommendedActivities" to GeminiWire.schema(
                "ARRAY", "추천 활동 목록 (2~3가지)", items = GeminiWire.schema("STRING")
            ),
            "itemsToBring" to GeminiWire.schema(
                "ARRAY", "챙겨갈 것 목록 (3~5가지)", items = GeminiWire.schema("STRING")
            ),
        ),
        required = listOf("summary", "recommendedPlaces", "recommendedActivities", "itemsToBring"),
    )

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
        eventTracker: AssistantEventTracker? = null,
        /**
         * 현재 방 멤버 uid(Firestore participantUids). null이면 멤버 정보 없음 — 이전 동작(전원 반영).
         * 주어지면 나간 사람의 메시지는 요약·분류·인원·지역·선호에서 빠지고, 그 사람이 섞였을 수 있는
         * 프로필의 선호·불호는 쓰지 않는다. 이름 마스킹 명단에는 나간 사람도 남긴다(프라이버시 우선).
         */
        memberIds: Set<String>? = null,
    ): OrchestratorResult = withContext(Dispatchers.IO) {

        eventTracker?.onEvent(AssistantEvent.OrchestrationStarted(roomId, messages.size))

        // ⓪ 멤버 범위 — 나간 사람의 말은 기기 안에서 먼저 걸러낸다(동조 맥락은 인용으로 보존)
        val historySenders = messages.map { it.senderId }.filter { it.isNotBlank() }.toSet()
        // 정본 순서(서버 시각 → 문서 id)로 먼저 세운다 — 리스트가 섞여 와도 "마지막 결정"이 바뀌지 않게(S3)
        val scoped = MemberScope.scope(MessageOrder.canonical(messages), memberIds)
        val profileUsable = MemberScope.profileUsable(userStatus, memberIds, historySenders)
        // 이 아래에서 선호·불호·참가자 판단은 profile만 쓴다(나간 사람이 섞였을 수 있으면 null)
        val profile = if (profileUsable) userStatus else null
        if (memberIds != null) {
            val departed = messages.filter { it.senderId !in memberIds }
            if (!profileUsable) Log.w(TAG, "나간 멤버가 섞였을 수 있는 프로필 — 선호·불호 미사용(재구성 대기)")
            eventTracker?.onEvent(
                AssistantEvent.MembersScoped(
                    memberCount = memberIds.size,
                    droppedMessages = departed.size,
                    departedSenders = departed.map { it.senderId }.toSet().size,
                    profileUsed = profileUsable && userStatus != null,
                )
            )
            if (scoped.isEmpty()) {
                // 전원 이탈·남은 멤버가 말한 적 없음 — 추천할 대화가 없으니 클라우드를 부르지 않는다
                val reason = "현재 멤버의 대화가 없습니다"
                eventTracker?.onEvent(AssistantEvent.OrchestrationFinished(false, 0, reason))
                return@withContext OrchestratorResult.Failed(reason, 0)
            }
        }

        // ① 상황 판정 — 원문을 읽지만 **기기 안에서만** 읽는다. 밖으로 나가는 건 상황 라벨 하나.
        //    밥 약속과 술 약속을 같은 요청으로 취급하지 않기 위한 첫 분기점이다.
        val classification = ContextClassifier.classify(scoped)
        val meetingContext = classification.context
        Log.d(TAG, "상황 분류: ${meetingContext.name} conf=${classification.confidence} 근거=${classification.signals}")
        eventTracker?.onEvent(
            AssistantEvent.ContextClassified(
                context = meetingContext.name,
                label = meetingContext.label,
                confidence = classification.confidence,
                signals = classification.signals,
                runnerUp = classification.runnerUp?.label,
            )
        )

        // ② 이 상황의 판단 기준 가중치 — 지난 모임 피드백으로 학습된 보정이 반영된다.
        //    CR ≥ 0.10인 보정은 resolve 단계에서 이미 기각돼 있다(일관성 게이트).
        val learned = metricsRepository?.weightsFor(meetingContext)
            ?: AhpJudgmentLearner.resolve(meetingContext, emptyList())
        Log.d(TAG, "AHP 가중치(${meetingContext.name}): ${AhpEngine.formatWeights(learned.result)} CR=${learned.result.cr}")
        eventTracker?.onEvent(
            AssistantEvent.CriteriaWeighted(
                context = meetingContext.label,
                weights = learned.result.ranked().map { (c, w) -> c.label to w },
                cr = learned.result.cr,
                consistent = learned.result.consistent,
                learnedDeltas = learned.appliedDeltas.size,
                droppedDeltas = learned.droppedDeltas.size,
            )
        )

        // 채팅 원문은 온디바이스에서 익명화 — 이 결과만 클라우드로 전송
        Log.d(TAG, "Gemma 1차 요약 시작 (온디바이스)")
        val gemmaSum = onDeviceLlm.summarizeForPrivacy(scoped)
        Log.d(TAG, "Gemma 요약 완료: ${gemmaSum.take(80)}")

        // 프라이버시 방화벽 최종 게이트 — Gemma가 지시를 어기고 이름/연락처를 흘리더라도
        // 클라우드(Gemini) 전송 직전 결정론적 스크러버가 마스킹한다 (belt-and-suspenders).
        // 마스킹 명단은 나간 사람까지 — 요약·후기에 그 이름이 남아 있을 수 있다
        val knownNames = buildKnownNames(messages, userStatus)
        val scrub = PiiScrubber.scrub(gemmaSum, knownNames)
        if (scrub.hadPii) {
            Log.w(TAG, "PII 스크러버 마스킹 ${scrub.redactions}건: ${scrub.byCategory}")
        }
        val safeSummary = scrub.text
        eventTracker?.onEvent(
            AssistantEvent.GemmaSummaryCompleted(safeSummary, scrub.redactions, scrub.byCategory)
        )

        // RAG: 이번 모임 요약과 의미적으로 유사한 과거 후기를 온디바이스 시맨틱 검색으로 회수.
        // 방 무관 — 이 사용자가 지난 모임들에서 남긴 취향이 새 톡방 추천에도 반영된다.
        // 피드백은 사용자 자유 텍스트(실명 가능) → 경계 통과 전 동일 PII 게이트 적용.
        val retrieved = feedbackRetriever.retrieve(query = safeSummary, topK = 3)
        // 불만 후기(별점 1~2)는 검색 점수와 무관하게 늘 회수한다 — 쿼리(익명 요약)에 장소명이 없어
        // 특정 장소에 대한 불만은 유사도 검색에 잘 안 걸린다(S5-8). 나간 멤버가 쓴 후기는 반영하지 않는다.
        val complaintEntries = runCatching { feedbackRetriever.complaints() }
            .onFailure { Log.w(TAG, "불만 후기 회수 실패 — 검색 결과만 사용", it) }
            .getOrDefault(emptyList())
        val feedbackPool = (retrieved + complaintEntries)
            .distinctBy { Triple(it.date, it.feedback, it.roomId) }
            .filter { ComplaintGate.authorIsMember(it, memberIds) }
        val complaints = ComplaintGate.complaintsOf(feedbackPool, memberIds)
        val avoid = ComplaintGate.avoidList(complaints)
        val ragRaw = buildString {
            if (avoid.isNotEmpty()) appendLine("피해야 할 장소(사용자 불만): ${avoid.joinToString(", ")}")
            feedbackPool.forEach { entry ->
                val stars = if (entry.rating > 0) " (만족도 ${entry.rating}/5)" else ""
                appendLine("- [${entry.date}]$stars ${entry.feedback}")
            }
        }.trim()
        val ragContext = PiiScrubber.scrub(ragRaw, knownNames).text
        // 평점이 달린 후기만 AHP "과거 만족" 기준의 증거가 된다(미평가는 중립)
        val impressions = feedbackPool.map { PastImpression(it.feedback, it.rating) }

        // ④ 거름망 — 자유 텍스트를 고정 스키마 블록으로 찍어낸다.
        //    "이번 주 토요일" 같은 상대 표현은 여기서 절대 날짜로 환산된다(모델에게 산수를 맡기지 않는다).
        val sieved = PromptSieve.sieve(
            messages = scoped,
            safeSummary = safeSummary,
            userStatus = profile,
            chatDate = chatDate,
            ahp = learned.result,
            classification = classification,
        )
        // ⑤ 경계 게이트 — 슬롯 값은 닫힌 패턴에서만 나오지만, 집행은 스크러버가 한다
        //    (CLAUDE.md 불변 원칙: 경계를 넘는 모든 자유 텍스트는 PiiScrubber를 통과한다)
        val frameScrub = PiiScrubber.scrub(sieved.frame, knownNames)
        if (frameScrub.hadPii) {
            Log.w(TAG, "정형 블록에서 PII ${frameScrub.redactions}건 마스킹: ${frameScrub.byCategory}")
        }
        val safeFrame = frameScrub.text
        eventTracker?.onEvent(
            AssistantEvent.SieveNormalized(safeFrame, sieved.missing.map { it.label })
        )

        var attempt = 0
        var accumulatedFeedback = ""
        // 장소가 사실상 유효한(Guardrail 통과) 결과는 소프트 제약(Reflection)만 못 맞춰도
        // 버리지 않는다 — 재시도로 개선을 노리되, 재시도 소진 시 최선의 결과로 폴백.
        var fallbackSuccess: MeetingSummary? = null
        var fallbackViolations = 0
        // 전 시도 누적 검증 집계 — 최종 결과에는 NOT_FOUND가 구조적으로 남지 않으므로
        // (guardrail.passed인 시도만 반환) 모델이 실제로 없는 가게를 몇 번 지어냈는지는
        // 폐기된 시도까지 세어야 보인다. 이 값이 학습기의 "없는 가게" 원인 판단 근거가 된다.
        var rawTally = VerificationTally()

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            Log.d(TAG, "하네스 루프 $attempt/$MAX_ATTEMPTS 시작")

            val prompt = RecommendationPrompt.build(
                sieved, chatDate, ragContext, knownNames, accumulatedFeedback,
            )
            eventTracker?.onEvent(AssistantEvent.PromptGenerated(attempt, prompt))

            try {
                val callResult = callGeminiWithTools(prompt, roomId, eventTracker)

                // 검증 0 — 불만 게이트: 사용자가 불만을 남긴 장소는 모델이 다시 내도 결과에서 뺀다(결정론)
                val gate = ComplaintGate.apply(callResult.summary.places, complaints)
                if (gate.blocked.isNotEmpty()) {
                    Log.w(TAG, "시도 $attempt 불만 장소 제외: ${gate.blocked.map { it.name }}")
                    eventTracker?.onEvent(
                        AssistantEvent.ComplaintsApplied(attempt, gate.blocked.map { it.name }, gate.kept.size)
                    )
                    val note = ComplaintGate.feedbackFor(gate.blocked)
                    accumulatedFeedback = if (accumulatedFeedback.isBlank()) note else "$accumulatedFeedback\n$note"
                    // 남는 장소가 없으면 이번 시도는 실패 — 피드백을 안고 다시 추천받는다
                    if (gate.kept.isEmpty()) continue
                }
                val proposed = callResult.summary.copy(places = gate.kept)

                // 검증 1 — Guardrail: 추천 장소가 실제로 존재/영업하는가 (하드 게이트)
                val guardrail = guardrailService.verify(
                    placeNames = proposed.places.map { it.name },
                    city = callResult.city
                )
                val unknownCount = guardrail.verifiedPlaces.count { it.status == PlaceStatus.UNKNOWN }
                Log.d(TAG, "시도 $attempt Guardrail: passed=${guardrail.passed} unknown=$unknownCount")
                eventTracker?.onEvent(
                    AssistantEvent.GuardrailEvaluated(attempt, guardrail.passed, guardrail.feedbackForRetry, unknownCount)
                )

                // 검증 상태 병합 + searchPlace로 못 채운 주소·좌표를 Guardrail 매칭 가게로 보충(추가 호출 없음)
                val verifiedSummary = applyVerification(proposed, guardrail.verifiedPlaces).let { s ->
                    s.copy(places = s.places.map { fillFromGuardrail(it, guardrail.matchedPlaces) })
                }

                // 검증 2 — Reflection: 랭킹·지표와 같은 장소 정보를 검사한다 (소프트 게이트)
                val reflection = ReflectionService.reflect(
                    places = verifiedSummary.places,
                    activities = verifiedSummary.activities,
                    preferences = profile?.preferences ?: emptyList(),
                )
                Log.d(TAG, "시도 $attempt Reflection: passed=${reflection.passed} 위반=${reflection.violations.size}건")
                eventTracker?.onEvent(
                    AssistantEvent.ReflectionEvaluated(
                        attempt, reflection.passed, reflection.violations.map { "'${it.constraint}' → ${it.matchedIn}" }
                    )
                )

                rawTally += VerificationTally.of(verifiedSummary.places)

                // ⑧ AHP 종합 — 검증 상태가 붙은 뒤에 재정렬해야 "검증 신뢰" 기준이 실제 값을 갖는다.
                //    서로 취향이 갈리는 참가자들 사이에서 순서를 정하는 건 평균이 아니라 상황 기준이다.
                val ranked = PlaceRanker.rank(
                    places = verifiedSummary.places,
                    context = meetingContext,
                    ahp = learned.result,
                    preferences = profile?.preferences ?: emptyList(),
                    pastImpressions = impressions,
                )
                val enriched = applyRanking(verifiedSummary, ranked)
                val orderChanged = verifiedSummary.places.map { it.name } != enriched.places.map { it.name }
                if (ranked.isNotEmpty()) {
                    Log.d(TAG, "AHP 랭킹: " + ranked.joinToString(", ") { "${it.place.name}=${"%.3f".format(it.score)}" })
                    eventTracker?.onEvent(
                        AssistantEvent.PlacesRanked(
                            attempt = attempt,
                            ranking = ranked.map {
                                "${it.place.name} ${"%.3f".format(it.score)}${if (!it.feasible) " (실존 미확인 — 제외 대상)" else ""}"
                            },
                            changed = orderChanged,
                        )
                    )
                }

                if (guardrail.passed && reflection.passed) {
                    Log.d(TAG, "Guardrail+Reflection 통과 ✓ — 총 $attempt 회")
                    recordMetrics(
                        roomId, meetingContext, enriched,
                        profile?.preferences ?: emptyList(),
                        attempt, learned.result.cr, rawTally, eventTracker,
                    )
                    eventTracker?.onEvent(AssistantEvent.OrchestrationFinished(true, attempt))
                    return@withContext OrchestratorResult.Success(enriched, attempt)
                }

                // Guardrail은 통과했으나 Reflection만 실패 — 사실 오류는 아니므로 폴백 후보로 보존
                if (guardrail.passed) {
                    fallbackSuccess = enriched
                    fallbackViolations = reflection.violations.size
                }

                val combined = listOf(guardrail.feedbackForRetry, reflection.feedbackForRetry)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                accumulatedFeedback = if (accumulatedFeedback.isBlank()) combined
                else "$accumulatedFeedback\n$combined"
                Log.w(TAG, "시도 $attempt 검증 미통과 → 자가 수정 피드백 누적 후 재시도")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "시도 $attempt 예외", e)
                if (attempt >= MAX_ATTEMPTS) {
                    val reason = "예외: ${e.message}"
                    eventTracker?.onEvent(AssistantEvent.OrchestrationFinished(false, attempt, reason))
                    return@withContext OrchestratorResult.Failed(reason, attempt)
                }
            }
        }

        // 재시도 소진 — 장소가 유효한 폴백이 있으면 그것으로 성공 처리(소프트 제약만 미충족).
        fallbackSuccess?.let {
            Log.w(TAG, "Reflection 제약 미충족이나 장소 유효 — 최선의 결과로 폴백 반환")
            recordMetrics(
                roomId, meetingContext, it,
                profile?.preferences ?: emptyList(),
                MAX_ATTEMPTS, learned.result.cr, rawTally, eventTracker,
            )
            eventTracker?.onEvent(AssistantEvent.OrchestrationFinished(true, MAX_ATTEMPTS))
            return@withContext OrchestratorResult.Success(it, MAX_ATTEMPTS)
        }

        val reason = "최대 재시도 횟수($MAX_ATTEMPTS) 초과"
        eventTracker?.onEvent(AssistantEvent.OrchestrationFinished(false, MAX_ATTEMPTS, reason))
        OrchestratorResult.Failed(reason, MAX_ATTEMPTS)
    }

    // ── Gemini Function Calling 실행 ─────────────────────────────────────────

    private suspend fun callGeminiWithTools(
        prompt: String,
        roomId: String,
        eventTracker: AssistantEventTracker?
    ): GeminiCallResult {
        // 대화 히스토리는 REST contents 배열로 직접 관리.
        // 전송은 GeminiGateway(서버 프록시) 경유 — 앱에는 API 키가 없다.
        val history = JSONArray().put(GeminiWire.userText(prompt))

        suspend fun generate(): JSONObject = geminiGateway.generateContent(
            MODEL_NAME,
            GeminiWire.request(history, functionDeclarations, responseSchema),
        )

        var response = generate()

        var weatherResult = "날씨 정보 없음"
        var city = "미정"
        var meetingDate = "미정"
        val collectedKakaoPlaces = mutableListOf<KakaoPlace>()

        // Function Calling 왕복 — 모델이 계속 도구만 호출하며 최종 응답을 내지 않는 상황에
        // 대비해 MAX_TOOL_ROUNDS 로 상한을 둔다(바깥 MAX_ATTEMPTS와 별개).
        var toolRounds = 0
        var pendingCalls = GeminiWire.functionCalls(response)
        while (pendingCalls.isNotEmpty() && toolRounds < MAX_TOOL_ROUNDS) {
            toolRounds++

            // 모델 응답을 히스토리에 추가
            GeminiWire.modelContent(response)?.let { history.put(it) }

            // 1단계: 모든 함수 호출을 병렬 실행 — 스냅샷으로 공유 상태 격리
            val callResults: List<FcCallResult> = coroutineScope {
                pendingCalls.map { fc ->
                    val snapCity = city
                    val snapDate = meetingDate
                    async {
                        val (resultText, kPlaces) = dispatchFunctionCall(fc.name, fc.args, snapCity, snapDate)
                        Log.d(TAG, "함수 실행: ${fc.name} → ${resultText.take(80)}")
                        FcCallResult(fc.name, fc.args, resultText, kPlaces)
                    }
                }.awaitAll()
            }

            // 2단계: 단일 스레드에서 순차적으로 상태 업데이트 → Race Condition 없음
            for (r in callResults) {
                eventTracker?.onEvent(AssistantEvent.ToolCalled(r.name, r.args, r.result))
                when (r.name) {
                    "getWeather" -> {
                        city = r.args["city"]?.toString()?.removeSurrounding("\"")?.ifBlank { null } ?: city
                        // 모델이 날씨 조회 실패 후 date="미정"으로 재호출하는 경우가 있다(평가 실측). 이미 확보한
                        // 유효한 날짜(YYYY-MM-DD)를 "미정"으로 덮어쓰지 않는다 — 마지막 호출이 아니라 유효값 우선.
                        val d = r.args["date"]?.toString()?.removeSurrounding("\"")
                        if (d != null && ISO_DATE.matches(d)) meetingDate = d
                        else if (!ISO_DATE.matches(meetingDate) && !d.isNullOrBlank()) meetingDate = d
                        weatherResult = r.result
                    }
                    "searchPlace" -> {
                        city = r.args["city"]?.toString()?.removeSurrounding("\"") ?: city
                        collectedKakaoPlaces.addAll(r.kakaoPlaces)
                    }
                }
            }

            // 함수 응답을 히스토리에 추가 후 재전송
            history.put(GeminiWire.functionResponses(callResults.map { it.name to it.result }))
            response = generate()
            pendingCalls = GeminiWire.functionCalls(response)
        }
        if (pendingCalls.isNotEmpty()) {
            Log.w(TAG, "Function Calling 왕복 상한($MAX_TOOL_ROUNDS) 도달 — 마지막 응답으로 진행")
        }

        val jsonText = GeminiWire.text(response)
            ?: throw IllegalStateException("Gemini 응답이 비어 있습니다")

        eventTracker?.onEvent(AssistantEvent.JsonParsed(jsonText))

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

        val placesStructured = places.map { geminiStr -> toRecommendedPlace(geminiStr, collectedKakaoPlaces, city) }

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

    /**
     * 스크러버가 대조할 확정 이름 목록 — 발신자명 + 프로필 참가자 + 대화 본문에서 찾은 명단 밖 이름
     * (중복·공백 제거). 본문 이름은 기기 안의 원문에서만 찾는다: "지훈이도 온대"가 원문에 있으면
     * 요약·정형 블록에 "지훈"이 맨 이름으로 옮겨 적혀도 지울 수 있다(DEFECT_TEST S1-8).
     */
    private fun buildKnownNames(messages: List<Message>, userStatus: UserStatusEntity?): List<String> {
        val fromMessages = messages.map { it.senderName }
        val fromStatus = userStatus?.participants ?: emptyList()
        val mentioned = messages.flatMap { PiiScrubber.detectNames(it.content) }
        return (fromMessages + fromStatus + mentioned)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Guardrail 검증 결과(PlaceVerification 상태)를 요약 결과의 장소 목록에 병합한다.
     * 검증 후보에서 제외된 장소(길이 필터 등)는 기본값 UNVERIFIED로 남는다.
     */
    private suspend fun recordMetrics(
        roomId: String,
        context: MeetingContext,
        summary: MeetingSummary,
        preferences: List<String>,
        attempts: Int,
        consistencyRatio: Double,
        rawTally: VerificationTally,
        eventTracker: AssistantEventTracker?,
    ) {
        // 제약 준수율의 분자·분모를 모두 '항목' 단위로 맞춘다(2026-09-23 C2).
        // reflection.violations는 '불호 구절' 단위라 분모(항목 수)와 단위가 달랐다.
        val violationItems = ReflectionService.violatingItemCount(
            places = summary.places,
            activities = summary.activities,
            preferences = preferences,
        )
        val metrics = HarnessMetrics.of(
            places = summary.places,
            violationItemCount = violationItems,
            itemCount = summary.places.size + summary.activities.size,
            attempts = attempts,
            maxAttempts = MAX_ATTEMPTS,
            rawCounts = rawTally,
        )
        Log.d(TAG, "하네스 지표 — ${metrics.summary()}")
        eventTracker?.onEvent(AssistantEvent.MetricsRecorded(metrics.summary()))
        runCatching { metricsRepository?.recordRun(roomId, context, metrics, consistencyRatio) }
            .onFailure { Log.e(TAG, "지표 기록 실패(추천 결과에는 영향 없음)", it) }
    }

    /**
     * AHP 랭킹 순서를 요약 결과에 반영한다.
     *
     * `places`(카드 UI가 쓰는 구조화 목록)와 `recommendation`(캘린더 메모·요약 화면이 쓰는
     * 통문자열)이 서로 다른 순서를 말하면 안 되므로 통문자열도 같이 다시 만든다.
     */
    private fun applyRanking(summary: MeetingSummary, ranked: List<RankedPlace>): MeetingSummary {
        if (ranked.isEmpty()) return summary
        val ordered = ranked.map { it.place }
        return summary.copy(
            places = ordered,
            recommendation = buildRecommendationText(ordered, summary.activities, summary.itemsToBring),
        )
    }

    private fun buildRecommendationText(
        places: List<RecommendedPlace>,
        activities: List<String>,
        items: List<String>,
    ): String = buildString {
        appendLine("장소 추천")
        places.forEach { p ->
            val reason = if (p.reason.isNotBlank()) " — ${p.reason}" else ""
            appendLine("• ${p.name}$reason")
        }
        appendLine("\n활동 추천")
        activities.forEach { appendLine("• $it") }
        appendLine("\n챙겨갈 것")
        items.forEach { appendLine("• $it") }
    }.trim()

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

}
