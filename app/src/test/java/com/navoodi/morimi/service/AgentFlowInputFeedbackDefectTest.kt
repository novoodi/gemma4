package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.pipeline.KeywordFallbackRetriever
import com.navoodi.morimi.data.repository.FeedbackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * 결함 탐색 — 시나리오 4(이상 입력) · 5(불만 피드백 반영).
 * 실패한 케이스는 @Ignore("결함: …")로 남겨 둔다(증거). 표: docs/eval/DEFECT_TEST_2026-10.md
 */
class AgentFlowInputFeedbackDefectTest {

    private val okGemini get() = ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당")))

    private val dinner = listOf(
        msg("김민수", "토요일 강남에서 저녁 먹자", 1),
        msg("이지영", "좋아 밥 먹자", 2),
    )

    // ═══ 시나리오 4 — 이상 입력 ═══════════════════════════════════════════════

    @Ignore("결함: 빈 대화에도 Gemini를 3회까지 호출하고 성공을 반환 — 오케스트레이터 입력 검증 없음(UI만 막음)")
    @Test
    fun `S4-1 이상 - 빈 대화는 클라우드 호출 없이 실패해야 한다`() {
        val r = AgentFlow.run(emptyList(), okGemini)
        assertTrue(r.result is OrchestratorResult.Failed)
        assertEquals(0, r.gemini.requests.size)
    }

    @Test
    fun `S4-2 이상 - 이모지만 있는 대화는 일시·지역을 미확정으로 표기하고 진행`() {
        val msgs = listOf(msg("김민수", "😀😀😀", 1), msg("이지영", "👍🔥", 2))
        val r = AgentFlow.run(msgs, okGemini)
        assertNotNull(r.success)
        assertEquals("미정", r.slot("일시"))
        assertEquals("미정", r.slot("지역"))
        assertTrue(r.frame.contains("[미확정 슬롯"))
    }

    @Ignore("결함: 영어 대화의 요일·지역 미인식 — PromptSieve 추출기가 한국어 전용")
    @Test
    fun `S4-3 이상 - 영어만 있는 대화도 요일·지역을 인식해야 한다`() {
        val msgs = listOf(
            msg("Minsu", "Let's meet on Saturday in Gangnam for dinner", 1),
            msg("Jiyoung", "sounds good", 2),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertTrue(r.slot("일시"), r.slot("일시").startsWith("2026-10-03"))
    }

    @Test
    fun `S4-4 경계 - 장소 언급이 없으면 지역 미정, 검색어에 미정·null이 섞이지 않는다`() {
        val msgs = listOf(msg("김민수", "토요일 저녁 먹자", 1), msg("이지영", "좋아", 2))
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("미정", r.slot("지역"))
        val queryLine = r.prompts.first().lines().first { it.startsWith("3. searchPlace") }
        assertFalse(queryLine, queryLine.contains("null") || queryLine.contains("미정 "))
        assertNotNull(r.success)
    }

    @Test
    fun `S4-5 정상 - 지역 미정이면 Guardrail 지역 검사를 생략한다`() {
        val msgs = listOf(msg("김민수", "토요일 저녁 먹자, 장소는 미정", 1), msg("이지영", "좋아", 2))
        val gemini = ScriptedGemini.of(Gem.weather("미정"), Gem.final(listOf("해운대횟집")))
        val r = AgentFlow.run(msgs, gemini)
        assertEquals(VerificationStatus.VERIFIED, r.places.single().verification)
    }

    @Test
    fun `S4-6 이상 - 아주 긴 대화(2000건)도 끝까지 처리된다`() {
        val msgs = (1..2000).map { i -> msg(if (i % 2 == 0) "김민수" else "이지영", "토요일 강남 저녁 얘기 $i", i.toLong()) }
        val r = AgentFlow.run(msgs, okGemini)
        assertNotNull(r.success)
    }

    @Ignore("결함: 긴 대화 전체(약 3.5만 자)를 온디바이스 요약에 그대로 전달 — 윈도우·청크 처리 없음(Gemma 컨텍스트 초과 위험, 실기기 확인 필요)")
    @Test
    fun `S4-7 이상 - 아주 긴 대화는 온디바이스 요약 입력이 컨텍스트 한도 안으로 잘려야 한다`() {
        val msgs = (1..2000).map { i -> msg(if (i % 2 == 0) "김민수" else "이지영", "토요일 강남 저녁 얘기 $i", i.toLong()) }
        val r = AgentFlow.run(msgs, okGemini)
        val chars = r.llm.received.single().sumOf { it.content.length + 1 }
        // Gemma E2B 컨텍스트 ~8k 토큰(StatusCompressionPipeline 주석). 한국어 1자≈1토큰 이상으로 보수 추정
        assertTrue("요약 입력 ${chars}자", chars <= 8_000)
    }

    @Test
    fun `S4-8 정상 - 대화 중 말 바꾸기(지역·날짜)는 마지막 결정이 반영된다`() {
        val msgs = listOf(
            msg("김민수", "토요일 홍대에서 보자", 1),
            msg("이지영", "홍대 말고 강남으로 하자. 일요일로 바꾸자", 2),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("강남", r.slot("지역"))
        assertTrue(r.slot("일시").startsWith("2026-10-04"))
    }

    @Ignore("결함: 지역 철회('강남은 좀 별로야')가 대안 없이 끝나면 이전 메시지의 강남으로 되돌아감 — lastMention이 부정된 메시지를 건너뜀")
    @Test
    fun `S4-9 근접 오류 - 지역을 철회만 하고 대안이 없으면 지역은 미정이어야 한다`() {
        val msgs = listOf(
            msg("김민수", "토요일 강남 가자", 1),
            msg("이지영", "강남은 좀 별로야", 2),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("미정", r.slot("지역"))
    }

    // ═══ 시나리오 5 — 추천 후 불만 피드백 ════════════════════════════════════

    private val complaint = FeedbackEntry(date = "2026-09-20", feedback = "미미식당 너무 시끄러웠어요", roomId = "old-room", rating = 1)

    @Test
    fun `S5-1 정상 - 불만 후기가 다음 추천 프롬프트에 전달된다`() {
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(complaint)))
        assertTrue(r.prompts.first().contains("미미식당 너무 시끄러웠어요"))
        assertTrue(r.prompts.first().contains("만족도 1/5"))
    }

    @Ignore("결함: 불만 후기 장소를 모델이 다시 추천하면 그대로 최종 결과에 포함 — 피드백은 프롬프트 텍스트일 뿐 결정론적 배제 게이트 없음")
    @Test
    fun `S5-2 비정상 - 불만 장소를 모델이 다시 추천하면 최종 결과에서 빠져야 한다`() {
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(complaint)))
        assertFalse(r.placeNames.toString(), r.placeNames.contains("미미식당"))
    }

    @Ignore("결함: 불만 장소의 다른 지점(미미식당 역삼점)도 그대로 포함 — 유사 장소 배제 없음")
    @Test
    fun `S5-3 비정상 - 불만 장소의 다른 지점(비슷한 곳)도 빠져야 한다`() {
        val c = complaint.copy(feedback = "미미식당 강남점 너무 시끄러웠어요")
        val gemini = ScriptedGemini.of(Gem.final(listOf("미미식당 역삼점", "소담식당")))
        val r = AgentFlow.run(dinner, gemini, retriever = FixedRetriever(listOf(c)))
        assertFalse(r.placeNames.toString(), r.placeNames.any { it.startsWith("미미식당") })
    }

    @Ignore("결함: 조사 붙은 이름('미미식당은')의 불만 후기가 감점되지 않음 — PlaceRanker.pastSatisfaction 토큰 매칭이 조사 미처리")
    @Test
    fun `S5-4 근접 오류 - 별점 1 불만 후기(조사 붙은 이름)가 랭킹에서 감점된다`() {
        val c = complaint.copy(feedback = "미미식당은 너무 시끄러웠어요")
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(c)))
        assertEquals(listOf("소담식당", "미미식당"), r.placeNames)
    }

    @Test
    fun `S5-5 정상 - 별점 1 불만 후기(띄어쓴 이름)는 랭킹에서 감점된다`() {
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(complaint)))
        assertEquals(listOf("소담식당", "미미식당"), r.placeNames)
    }

    @Ignore("결함: 별점 없는(0점) 불만 후기는 감점 안 됨 — 설계상 중립 처리(논의 필요)")
    @Test
    fun `S5-6 근접 오류 - 별점 없는 불만 후기도 감점돼야 한다`() {
        val c = complaint.copy(rating = 0, feedback = "미미식당 최악이었음 다신 안 가")
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(c)))
        assertEquals(listOf("소담식당", "미미식당"), r.placeNames)
    }

    @Ignore("결함: 다른 방 후기 속 제3자 실명(지훈이랑)이 Gemini로 누출 — knownNames가 현재 방 기준이라 후기 속 이름을 모름")
    @Test
    fun `S5-7 이상 - 다른 방 후기 속 제3자 실명은 경계에서 마스킹돼야 한다`() {
        val c = complaint.copy(feedback = "지훈이랑 갔던 미미식당 너무 시끄러웠어요")
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(c)))
        assertFalse("후기 속 실명이 Gemini로 전송됨", r.gemini.allSentText().contains("지훈"))
    }

    @Ignore("결함: 키워드 폴백 검색이 요약문(장소명 없음)을 쿼리로 써서 장소 불만 후기를 회수 못 함")
    @Test
    fun `S5-8 비정상 - 키워드 폴백 검색에서도 장소 불만 후기가 회수돼야 한다`() {
        val dao = FakeFeedbackDao(
            listOf(FeedbackEntity(id = 1, roomId = "old-room", date = "2026-09-20", feedback = "미미식당 너무 시끄러웠어요", rating = 1))
        )
        val r = AgentFlow.run(dinner, okGemini, retriever = KeywordFallbackRetriever(dao))
        assertTrue("불만 후기가 프롬프트에 없음", r.prompts.first().contains("미미식당 너무 시끄러웠어요"))
    }

    @Test
    fun `S5-9 정상 - 만족 후기(별점 5)의 장소는 랭킹 상단으로 올라간다`() {
        val good = FeedbackEntry(date = "2026-09-20", feedback = "소담식당 최고였어요", roomId = "old-room", rating = 5)
        val r = AgentFlow.run(dinner, okGemini, retriever = FixedRetriever(listOf(good)))
        assertEquals("소담식당", r.placeNames.first())
    }
}
