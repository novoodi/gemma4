package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 남은 결함 수정 보강 테스트(DEFECT_TEST_2026-10) — S4-1 · S6-18 · S6-17 · S6-10 · S6-4 · S4-9 · S4-7.
 * 결함마다 정상 / 비정상 / 경계 / 이상 입력을 함께 둔다.
 */
class RemainingDefectFixTest {

    private val okGemini get() = ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당")))
    private val dinner = listOf(msg("김민수", "토요일 강남에서 저녁 먹자", 1), msg("이지영", "좋아 밥 먹자", 2))

    private fun failedReason(r: FlowRun) = (r.result as OrchestratorResult.Failed).reason

    // ══ S4-1 빈 대화 → 클라우드 호출 없이 실패 ═══════════════════════════════

    @Test
    fun `S4-1 비정상 - 빈 목록은 Gemma·Gemini 호출 없이 실패`() {
        val r = AgentFlow.run(emptyList(), okGemini)
        assertEquals("대화 내용이 없습니다", failedReason(r))
        assertEquals(0, r.gemini.requests.size)
        assertTrue(r.llm.received.isEmpty())
    }

    @Test
    fun `S4-1 이상 입력 - 공백·줄바꿈뿐인 메시지만 있으면 빈 대화와 같다`() {
        val r = AgentFlow.run(listOf(msg("김민수", "   ", 1), msg("이지영", "\n\t", 2)), okGemini)
        assertTrue(r.result is OrchestratorResult.Failed)
        assertEquals(0, r.gemini.requests.size)
    }

    @Test
    fun `S4-1 경계 - 공백 사이에 내용 있는 메시지가 하나라도 있으면 진행`() {
        val r = AgentFlow.run(listOf(msg("김민수", " ", 1), msg("이지영", "토요일 강남 저녁", 2)), okGemini)
        assertNotNull(r.success)
    }

    @Test
    fun `S4-1 경계 - 남은 멤버의 메시지가 공백뿐이면 실패(나간 사람 말은 내용이 있어도)`() {
        val msgs = listOf(msg("정하늘", "강남 저녁 가자", 1), msg("김민수", "  ", 2))
        val r = AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수"))
        assertTrue(r.result is OrchestratorResult.Failed)
        assertEquals(0, r.gemini.requests.size)
    }

    @Test
    fun `S4-1 정상 - 실패 이벤트로 끝나고 시도 횟수는 0`() {
        val r = AgentFlow.run(emptyList(), okGemini)
        val end = r.events.filterIsInstance<AssistantEvent.OrchestrationFinished>().single()
        assertFalse(end.success)
        assertEquals(0, end.attempts)
        assertEquals(0, (r.result as OrchestratorResult.Failed).attempts)
    }

    @Test
    fun `S4-1 정상 - 이모지만 있어도 내용이 있으면 진행(빈 대화가 아니다)`() {
        assertNotNull(AgentFlow.run(listOf(msg("김민수", "😀", 1)), okGemini).success)
    }

    // ══ S6-18 장소 배열의 비문자열 원소 ════════════════════════════════════════

    private fun body(places: org.json.JSONArray, activities: org.json.JSONArray = org.json.JSONArray().put("산책")) =
        Gem.text(org.json.JSONObject().put("summary", "요약").put("recommendedPlaces", places)
            .put("recommendedActivities", activities).put("itemsToBring", org.json.JSONArray().put("우산")).toString())

    @Test
    fun `S6-18 비정상 - 숫자·객체·null이 섞여도 문자열 장소는 살려 1회에 성공`() {
        val places = org.json.JSONArray().put("미미식당").put(123).put(org.json.JSONObject().put("x", 1)).put(org.json.JSONObject.NULL).put("소담식당")
        val r = AgentFlow.run(dinner, ScriptedGemini.of(body(places)))
        assertEquals(1, r.success!!.attempts)
        assertEquals(setOf("미미식당", "소담식당"), r.placeNames.toSet())
    }

    @Test
    fun `S6-18 이상 입력 - 빈 문자열·공백 원소도 버린다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(body(org.json.JSONArray().put("").put("  ").put("미미식당"))))
        assertEquals(listOf("미미식당"), r.placeNames)
    }

    @Test
    fun `S6-18 경계 - 문자열 원소가 하나도 없으면 0곳 — Guardrail이 실패 처리하고 재시도`() {
        val gemini = ScriptedGemini.of(body(org.json.JSONArray().put(1).put(2)), Gem.final(listOf("미미식당")))
        val r = AgentFlow.run(dinner, gemini)
        assertEquals(2, r.success!!.attempts)
    }

    @Test
    fun `S6-18 정상 - 활동 목록의 이상한 원소도 같은 규칙`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(body(org.json.JSONArray().put("미미식당"), org.json.JSONArray().put(true).put("보드게임"))))
        assertEquals(listOf("보드게임"), r.success!!.summary.activities)
    }

    @Test
    fun `S6-18 정상 - 문자열 앞뒤 공백은 정리된다`() {
        assertEquals(listOf("미미식당"), AgentFlow.run(dinner, ScriptedGemini.of(body(org.json.JSONArray().put("  미미식당  ")))).placeNames)
    }

    // ══ S6-17 "이름 - 이유" 형태의 장소명 ══════════════════════════════════════

    private fun parse(x: String) = AssistantOrchestrator.parseGeminiPlaceEntry(x)

    @Test
    fun `S6-17 비정상 - 공백 둘러싼 하이픈·콜론·세로선 뒤는 이유로 분리`() {
        assertEquals("미미식당" to "조용해서 좋음", parse("미미식당 - 조용해서 좋음"))
        assertEquals("미미식당" to "가성비", parse("미미식당: 가성비"))
        assertEquals("미미식당" to "분위기", parse("미미식당 | 분위기"))
    }

    @Test
    fun `S6-17 정상 - 기존 규칙(엠대시·괄호)은 그대로`() {
        assertEquals("미미식당" to "조용함", parse("미미식당 — 조용함"))
        assertEquals("미미식당" to "", parse("미미식당(본점)"))
        assertEquals("미미식당" to "", parse("미미식당 (서울 강남구 역삼동 14-11)"))
    }

    @Test
    fun `S6-17 경계 - 붙여 쓴 하이픈은 이름의 일부(W-카페), 하이픈 뒤 괄호 주소도 처리`() {
        assertEquals("W-카페" to "", parse("W-카페"))
        assertEquals("미미식당" to "조용함", parse("미미식당 - 조용함 (서울 강남구 14-11)"))
    }

    @Test
    fun `S6-17 이상 입력 - 구분자만 있거나 앞이 비면 원문을 이름으로`() {
        assertEquals("- 이유만", parse("- 이유만").first)
        assertEquals("미미식당", parse("  미미식당  ").first)
    }

    @Test
    fun `S6-17 흐름 - 이유가 붙어도 Guardrail 검증 결과가 같은 이름에 매칭된다(VERIFIED)`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당 - 조용하고 맛있음", "소담식당: 가성비"))))
        assertEquals(setOf("미미식당", "소담식당"), r.placeNames.toSet())
        assertTrue(r.places.all { it.verification == com.navoodi.morimi.data.model.VerificationStatus.VERIFIED })
        assertEquals("조용하고 맛있음", r.places.first { it.name == "미미식당" }.reason)
    }

    @Test
    fun `S6-17 흐름 - 주소·좌표 매칭도 같은 이름으로(검색 결과 재사용)`() {
        val kakao = listOf(KakaoPlace("미미식당", "", "", "서울 강남구 1", "", "https://place.map.kakao.com/1", 37.5, 127.0))
        val p = AssistantOrchestrator.toRecommendedPlace("미미식당 - 조용함", kakao)
        assertEquals("미미식당", p.name)
        assertEquals("https://place.map.kakao.com/1", p.placeUrl)
    }

    // ══ S6-10 마지막 시도 예외 시 보존한 결과 ════════════════════════════════════

    private val dislikeNoisy = com.navoodi.morimi.data.local.UserStatusEntity(AgentFlow.ROOM, preferences = listOf("싫어요: 시끄러운 곳"))
    private val noisy = Gem.final(listOf("미미식당 — 시끄러운 곳이지만 맛있음"))

    @Test
    fun `S6-10 비정상 - 첫 시도만 쓸 만하고 이후 두 번 깨진 JSON이어도 그 결과로 성공`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(noisy, Gem.text("broken"), Gem.text("broken")), userStatus = dislikeNoisy)
        assertEquals(listOf("미미식당"), r.placeNames)
        assertEquals(3, r.success!!.attempts)
    }

    @Test
    fun `S6-10 비정상 - 마지막 시도가 프록시 장애(예외)여도 보존한 결과로 성공`() {
        val gemini = ScriptedGemini(listOf({ noisy }, { noisy }, { throw CloudProxy.ProxyException("geminiGenerate", "UNAVAILABLE", "장애") }))
        val r = AgentFlow.run(dinner, gemini, userStatus = dislikeNoisy)
        assertNotNull(r.success)
    }

    @Test
    fun `S6-10 경계 - 보존한 결과가 없으면(전부 예외) 예전처럼 예외로 실패`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.text("broken")), userStatus = dislikeNoisy)
        assertTrue(failedReason(r).startsWith("예외"))
    }

    @Test
    fun `S6-10 경계 - Guardrail에 걸린 결과는 보존 대상이 아니다(지어낸 장소 + 마지막 예외 → 실패)`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("유령식당")), Gem.final(listOf("유령식당")), Gem.text("broken")))
        assertTrue(r.result is OrchestratorResult.Failed)
        assertTrue(r.placeNames.isEmpty())
    }

    @Test
    fun `S6-10 정상 - 폴백 성공도 지표 기록·완료 이벤트(success=true)를 남긴다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(noisy, noisy, Gem.text("broken")), userStatus = dislikeNoisy)
        assertTrue(r.events.any { it is AssistantEvent.MetricsRecorded })
        assertTrue(r.events.filterIsInstance<AssistantEvent.OrchestrationFinished>().single().success)
    }

    @Test
    fun `S6-10 이상 입력 - 중간 시도가 빈 응답(candidates 없음)이어도 마지막이 정상이면 그 결과`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.noCandidates(), Gem.noCandidates(), Gem.final(listOf("소담식당"))))
        assertEquals(3, r.success!!.attempts)
        assertEquals(listOf("소담식당"), r.placeNames)
    }

    // ══ S6-4 도구 미사용 시 Guardrail 지역 ══════════════════════════════════════

    @Test
    fun `S6-4 비정상 - 도구 없이 다른 시도 가게를 내면 대화 지역(강남→서울)으로 걸러 재시도`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("해운대횟집")), Gem.final(listOf("미미식당"))))
        assertFalse(r.guardrails.first().passed)
        assertTrue(r.guardrails.first().feedback.contains("모임 지역(강남)"))
        assertEquals(2, r.success!!.attempts)
    }

    @Test
    fun `S6-4 정상 - 도구가 도시를 알려 주면 그쪽이 우선(기존 동작)`() {
        assertEquals("부산", AssistantOrchestrator.guardrailCity("부산", "강남"))
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.weather("부산"), Gem.final(listOf("해운대횟집"))))
        assertTrue(r.guardrails.first().passed)
    }

    @Test
    fun `S6-4 경계 - 대화에도 지역이 없으면 미정(지역 검사 생략, 기존 동작)`() {
        val msgs = listOf(msg("김민수", "토요일 저녁 먹자", 1), msg("이지영", "좋아", 2))
        val r = AgentFlow.run(msgs, ScriptedGemini.of(Gem.final(listOf("해운대횟집"))))
        assertTrue(r.guardrails.first().passed)
        assertEquals("미정", AssistantOrchestrator.guardrailCity("미정", "미정"))
    }

    @Test
    fun `S6-4 이상 입력 - 도구 도시가 빈 문자열·공백이면 대화 지역으로`() {
        assertEquals("강남", AssistantOrchestrator.guardrailCity("", "강남"))
        assertEquals("강남", AssistantOrchestrator.guardrailCity("  ", " 강남 "))
    }

    @Test
    fun `S6-4 경계 - 같은 시도의 다른 동네 가게는 통과(망원 모임 + 강남 가게 = 서울)`() {
        val msgs = listOf(msg("김민수", "토요일 망원동 저녁", 1), msg("이지영", "좋아", 2))
        assertTrue(AgentFlow.run(msgs, ScriptedGemini.of(Gem.final(listOf("미미식당")))).guardrails.first().passed)
    }

    @Test
    fun `S6-4 정상 - 화면에 보이는 모임 장소(location)는 바꾸지 않는다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당"))))
        assertEquals("미정", r.success!!.summary.location)
    }

    // ══ S4-9 지역 철회 ═══════════════════════════════════════════════════════

    private fun where(vararg texts: String, summary: String = ""): String? =
        PromptSieve.lastArea(texts.mapIndexed { i, t -> msg("u$i", t, i + 1L) }, summary, emptySet())

    @Test
    fun `S4-9 비정상 - 철회만 하고 대안이 없으면 지역 미정`() {
        assertEquals(null, where("토요일 강남 가자", "강남은 좀 별로야"))
        assertEquals("미정", AgentFlow.run(listOf(msg("김민수", "토요일 강남 가자", 1), msg("이지영", "강남은 좀 별로야", 2)), okGemini).slot("지역"))
    }

    @Test
    fun `S4-9 정상 - 다른 지역을 철회하면 앞의 지역은 유지(기존 동작)`() {
        assertEquals("강남", where("토요일 강남 가자", "홍대는 별로야"))
    }

    @Test
    fun `S4-9 정상 - 철회 뒤 다시 제안하면 그 지역`() {
        assertEquals("강남", where("강남 가자", "강남은 별로", "그래도 강남 가자"))
        assertEquals("홍대", where("강남 가자", "강남은 별로", "홍대 어때"))
    }

    @Test
    fun `S4-9 경계 - 같은 문장 안에서 뒤에 철회해도 미정, '말고' 대안은 대안 채택`() {
        assertEquals(null, where("강남 가자… 아 강남은 별로"))
        assertEquals("강남", where("홍대 말고 강남"))
    }

    @Test
    fun `S4-9 경계 - 철회된 지역이 요약문에 다시 나와도 쓰지 않는다`() {
        assertEquals(null, where("강남 가자", "강남은 좀 별로야", summary = "강남에서 저녁 모임"))
        assertEquals("홍대", where("강남 가자", "강남은 좀 별로야", summary = "홍대 인근 저녁 모임"))
    }

    @Test
    fun `S4-9 이상 입력 - 여러 지역을 차례로 철회하면 모두 빠진다`() {
        assertEquals(null, where("홍대 가자", "강남도 좋고", "홍대는 별로", "강남도 별로야"))
        assertEquals("성수", where("성수 가자", "홍대 가자", "홍대는 별로"))
    }

    // ══ S4-7 긴 대화의 요약 입력 ═════════════════════════════════════════════

    private fun longChat(n: Int, purpose: String = "토요일 강남에서 생일 축하 모임 하자") =
        listOf(msg("김민수", purpose, 1)) + (2..n).map { i -> msg(if (i % 2 == 0) "이지영" else "김민수", "잡담 메시지 번호 $i 입니다", i.toLong()) }

    private fun chars(list: List<com.navoodi.morimi.data.model.Message>) = list.sumOf { it.content.length + 1 }

    @Test
    fun `S4-7 비정상 - 2000건은 예산(6000자) 안으로, 가운데 중략 표시`() {
        val w = com.navoodi.morimi.data.pipeline.SummaryInputWindow.fit(longChat(2000))
        assertTrue(w.truncated)
        assertTrue(chars(w.messages) <= com.navoodi.morimi.data.pipeline.SummaryInputWindow.MAX_CHARS)
        assertEquals(1, w.messages.count { it.content == com.navoodi.morimi.data.pipeline.SummaryInputWindow.GAP_MARKER })
        assertTrue(w.originalChars > 30_000)
    }

    @Test
    fun `S4-7 정상 - 앞부분(모임 목적)과 가장 최근 메시지는 남는다`() {
        val chat = longChat(2000)
        val w = com.navoodi.morimi.data.pipeline.SummaryInputWindow.fit(chat)
        assertEquals(chat.first().id, w.messages.first().id)
        assertEquals(chat.last().id, w.messages.last().id)
        val head = w.messages.takeWhile { it.content != com.navoodi.morimi.data.pipeline.SummaryInputWindow.GAP_MARKER }
        assertTrue(chars(head) <= com.navoodi.morimi.data.pipeline.SummaryInputWindow.HEAD_CHARS)
    }

    @Test
    fun `S4-7 경계 - 예산 이하면 그대로(중략 없음), 순서 유지`() {
        val chat = longChat(50)
        val w = com.navoodi.morimi.data.pipeline.SummaryInputWindow.fit(chat)
        assertFalse(w.truncated)
        assertEquals(chat, w.messages)
    }

    @Test
    fun `S4-7 이상 입력 - 한 건이 예산보다 길어도 잘라 넣는다(앞부분은 앞, 최근은 끝)`() {
        val huge = "가".repeat(20_000)
        val chat = listOf(msg("김민수", huge, 1), msg("이지영", "끝", 2))
        val w = com.navoodi.morimi.data.pipeline.SummaryInputWindow.fit(chat)
        assertTrue(chars(w.messages) <= com.navoodi.morimi.data.pipeline.SummaryInputWindow.MAX_CHARS)
        assertEquals("끝", w.messages.last().content)
    }

    @Test
    fun `S4-7 흐름 - Gemma 요약에는 잘린 입력, 날짜·지역 추출은 전체 대화`() {
        val chat = longChat(2000) + msg("이지영", "아 그냥 홍대로 바꾸자", 3000)
        val r = AgentFlow.run(chat, okGemini)
        assertTrue(chars(r.llm.received.single()) <= 6_000)
        assertEquals("홍대", r.slot("지역"))
        assertTrue(r.slot("일시").startsWith("2026-10-03"))
    }

    @Test
    fun `S4-7 정상 - 짧은 대화는 요약 입력이 이전과 같다(동작 변화 없음)`() {
        val r = AgentFlow.run(dinner, okGemini)
        assertEquals(dinner.map { it.id }, r.llm.received.single().map { it.id })
    }
}
