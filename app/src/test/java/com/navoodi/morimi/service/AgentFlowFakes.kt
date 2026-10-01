package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.MockOnDeviceLlm
import com.navoodi.morimi.data.pipeline.OnDeviceLlmPort
import com.navoodi.morimi.data.repository.FeedbackEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 에이전트 흐름 결함 탐색용 페이크 모음 (docs/eval/DEFECT_TEST_2026-10.md).
 *
 * AssistantOrchestrator 전체를 JVM에서 돌리기 위해 교체 가능한 포트만 바꾼다:
 *  - [GeminiGateway]   → [ScriptedGemini]   (응답 시나리오 재생 + 전송 본문 기록)
 *  - GuardrailService  → 검색 함수 주입      ([FakePlaceWorld])
 *  - [OnDeviceLlmPort] → [FakeOnDeviceLlm]   (Gemma 대신 Mock 요약 / 누출 요약 주입)
 *  - [FeedbackRetriever] → [FixedRetriever]  (RAG 회수분 고정)
 * metricsRepository는 null(Room 불필요). Gemini가 searchPlace 도구를 부르면
 * KakaoLocalService(실제 Firebase)로 빠지므로 대부분의 시나리오는 getWeather(date="미정",
 * 네트워크 없이 즉시 반환)만 쓰거나 도구를 부르지 않는다.
 */
internal object Gem {
    private fun content(parts: List<JSONObject>): JSONObject = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(parts)))
        ),
    )

    fun text(s: String): JSONObject = content(listOf(JSONObject().put("text", s)))

    fun final(
        places: List<String>,
        activities: List<String> = listOf("보드게임", "산책"),
        items: List<String> = listOf("우산", "보조배터리", "지갑"),
        summary: String = "모임 추천 요약",
    ): JSONObject = text(
        JSONObject()
            .put("summary", summary)
            .put("recommendedPlaces", JSONArray(places))
            .put("recommendedActivities", JSONArray(activities))
            .put("itemsToBring", JSONArray(items))
            .toString()
    )

    fun call(name: String, args: Map<String, Any>): JSONObject =
        content(listOf(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject(args)))))

    /** getWeather — date가 "미정"이면 WeatherService가 네트워크 없이 즉시 반환한다 */
    fun weather(city: String, date: String = "미정"): JSONObject =
        call("getWeather", mapOf("city" to city, "date" to date))

    fun noCandidates(): JSONObject = JSONObject().put("candidates", JSONArray())
}

/** 호출 순서대로 응답을 재생한다. 스크립트가 끝나면 마지막 응답을 반복. 전송 본문은 스냅샷으로 기록. */
internal class ScriptedGemini(private val steps: List<() -> JSONObject>) : GeminiGateway {
    val requests: MutableList<JSONObject> = CopyOnWriteArrayList()

    override suspend fun generateContent(model: String, body: JSONObject): JSONObject {
        requests += JSONObject(body.toString()) // 히스토리 배열이 뒤에서 변하므로 사본 보관
        return steps[minOf(requests.size - 1, steps.lastIndex)]()
    }

    /** 디바이스 경계를 넘은 모든 텍스트 */
    fun allSentText(): String = requests.joinToString("\n") { it.toString() }

    companion object {
        fun of(vararg responses: JSONObject) = ScriptedGemini(responses.map { r -> { r } })
        fun failing(message: String = "UNAVAILABLE") =
            ScriptedGemini(listOf { throw CloudProxy.ProxyException("geminiGenerate", message, "업스트림 장애") })
    }
}

internal class FakeOnDeviceLlm(private val summary: ((List<Message>) -> String)? = null) : OnDeviceLlmPort {
    val received: MutableList<List<Message>> = CopyOnWriteArrayList()
    private val mock = MockOnDeviceLlm()

    override suspend fun compress(messages: List<Message>): String = mock.compress(messages)

    override suspend fun summarizeForPrivacy(messages: List<Message>): String {
        received += messages
        return summary?.invoke(messages) ?: mock.summarizeForPrivacy(messages)
    }
}

internal class FixedRetriever(private val entries: List<FeedbackEntry> = emptyList()) : FeedbackRetriever {
    val queries: MutableList<String> = CopyOnWriteArrayList()
    override suspend fun retrieve(query: String, topK: Int): List<FeedbackEntry> {
        queries += query
        return entries.take(topK)
    }
}

/** getAll()만 쓰는 KeywordFallbackRetriever용 DAO 페이크 */
internal class FakeFeedbackDao(private val rows: List<FeedbackEntity>) : FeedbackDao {
    override suspend fun insert(entity: FeedbackEntity): Long = 0
    override suspend fun updateEmbedding(id: Long, embedding: ByteArray?) {}
    override suspend fun updateRating(id: Long, rating: Int) {}
    override suspend fun getRated(): List<FeedbackEntity> = rows.filter { it.rating > 0 }
    override suspend fun getByRoom(roomId: String): List<FeedbackEntity> = rows.filter { it.roomId == roomId }
    override suspend fun latestFeedbackTimestamp(roomId: String): Long? = null
    override suspend fun countByRoom(roomId: String): Int = rows.count { it.roomId == roomId }
    override suspend fun getAll(): List<FeedbackEntity> = rows
    override suspend fun getMissingEmbeddings(): List<FeedbackEntity> = emptyList()
    override suspend fun deleteByRoom(roomId: String) {}
    override suspend fun clear() {}
}

/** 카카오 검색 흉내 — 어떤 검색어든 등록된 가게 목록(최대 10건)을 돌려주고 PlaceMatcher가 고른다. */
internal class FakePlaceWorld(private val places: List<KakaoPlace>) {
    val queries: MutableList<String> = CopyOnWriteArrayList()
    val search: suspend (String) -> PlaceSearchResult = { q ->
        queries += q
        PlaceSearchResult.Found(places.take(10))
    }

    companion object {
        fun kp(name: String, address: String) =
            KakaoPlace(name = name, category = "", phone = "", address = address, roadAddress = "", url = "")

        val DEFAULT = FakePlaceWorld(
            listOf(
                kp("미미식당", "서울 강남구 역삼동 1"),
                kp("소담식당", "서울 강남구 논현동 2"),
                kp("서울식당", "서울 종로구 관철동 3"),
                kp("달빛술집", "서울 마포구 서교동 4"),
                kp("조용한찻집", "서울 마포구 연남동 5"),
                kp("해운대횟집", "부산 해운대구 우동 6"),
            )
        )
    }
}

internal fun msg(sender: String, content: String, t: Long, senderId: String = sender, id: String = "$senderId@$t") =
    Message(id = id, roomId = AgentFlow.ROOM, senderId = senderId, senderName = sender, content = content, timestamp = t)

internal class FlowRun(
    val result: OrchestratorResult,
    val events: List<AssistantEvent>,
    val gemini: ScriptedGemini,
    val llm: FakeOnDeviceLlm,
    val world: FakePlaceWorld,
    val retriever: FeedbackRetriever,
) {
    val success: OrchestratorResult.Success? get() = result as? OrchestratorResult.Success
    val places: List<RecommendedPlace> get() = success?.summary?.places.orEmpty()
    val placeNames: List<String> get() = places.map { it.name }
    val frame: String get() = events.filterIsInstance<AssistantEvent.SieveNormalized>().single().frame
    val prompts: List<String> get() = events.filterIsInstance<AssistantEvent.PromptGenerated>().map { it.prompt }
    val guardrails: List<AssistantEvent.GuardrailEvaluated>
        get() = events.filterIsInstance<AssistantEvent.GuardrailEvaluated>()

    /** 정형 블록의 슬롯 값 ("인원: 3명" → "3명") */
    fun slot(label: String): String =
        frame.lines().first { it.startsWith("$label: ") }.removePrefix("$label: ").trim()
}

internal object AgentFlow {
    const val ROOM = "room-defect"
    /** 2026-10-01(목) — "토요일"=10-03, "일요일"=10-04 */
    val CHAT_DATE: LocalDate = LocalDate.of(2026, 10, 1)

    fun run(
        messages: List<Message>,
        gemini: ScriptedGemini,
        world: FakePlaceWorld = FakePlaceWorld.DEFAULT,
        userStatus: UserStatusEntity? = null,
        retriever: FeedbackRetriever = FixedRetriever(),
        llm: FakeOnDeviceLlm = FakeOnDeviceLlm(),
        search: (suspend (String) -> PlaceSearchResult)? = null,
        memberIds: Set<String>? = null,
    ): FlowRun = runBlocking {
        val events = CopyOnWriteArrayList<AssistantEvent>()
        val orchestrator = AssistantOrchestrator(
            guardrailService = GuardrailService(search ?: world.search),
            feedbackRetriever = retriever,
            onDeviceLlm = llm,
            metricsRepository = null,
            geminiGateway = gemini,
        )
        val result = orchestrator.orchestrate(
            roomId = ROOM,
            messages = messages,
            userStatus = userStatus,
            chatDate = CHAT_DATE,
            eventTracker = object : AssistantEventTracker {
                override fun onEvent(event: AssistantEvent) { events += event }
            },
            memberIds = memberIds,
        )
        FlowRun(result, events.toList(), gemini, llm, world, retriever)
    }
}
