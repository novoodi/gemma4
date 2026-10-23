package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.pipeline.KeywordFallbackRetriever
import com.navoodi.morimi.data.repository.FeedbackEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 불만 피드백 미반영 결함(DEFECT_TEST_2026-10 S5-2·S5-3·S5-4·S5-8).
 * 교수님 시나리오: 추천 → 결정 → 한 명이 "별로" → 다음 추천에서 그 장소(와 같은 상호)는 빠지고 비슷한 곳은 감점.
 */
class ComplaintGateTest {

    private val dinner = listOf(msg("김민수", "토요일 강남에서 저녁 먹자", 1), msg("이지영", "좋아 밥 먹자", 2))
    private val members = setOf("김민수", "이지영")

    private fun fb(text: String, rating: Int = 1, author: String = "", targets: List<String> = emptyList()) =
        FeedbackEntry(date = "2026-09-20", feedback = text, roomId = "old-room", rating = rating, authorUid = author, targetPlaces = targets)

    private fun gate(names: List<String>, vararg entries: FeedbackEntry, memberIds: Set<String>? = null) =
        ComplaintGate.apply(names.map { RecommendedPlace(name = it) }, ComplaintGate.complaintsOf(entries.toList(), memberIds))

    // ══ S5-2 같은 장소 재추천 → 결과에서 제외 ══════════════════════════════════

    @Test
    fun `S5-2 정상 - 본문에 이름이 나온 불만 장소는 제외, 나머지 유지`() {
        val r = gate(listOf("미미식당", "소담식당"), fb("미미식당 너무 시끄러웠어요"))
        assertEquals(listOf("소담식당"), r.kept.map { it.name })
        assertEquals(listOf("미미식당"), r.blocked.map { it.name })
    }

    @Test
    fun `S5-2 정상 - 별점만 남긴 불만도 결정한 장소(대상 기록)면 제외`() {
        val r = gate(listOf("미미식당", "소담식당"), fb("별로였어요", targets = listOf("미미식당")))
        assertEquals(listOf("소담식당"), r.kept.map { it.name })
    }

    @Test
    fun `S5-2 경계 - 별점 3(보통)·0(미평가)·4 이상은 불만이 아니다`() {
        listOf(0, 3, 4, 5).forEach { rating ->
            assertTrue("rating=$rating", gate(listOf("미미식당"), fb("미미식당 별로", rating = rating)).blocked.isEmpty())
        }
    }

    @Test
    fun `S5-2 흐름 - 모델이 불만 장소만 계속 내면 재시도 후 다른 장소로 성공`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("미미식당")), Gem.final(listOf("소담식당")))
        val r = AgentFlow.run(dinner, gemini, retriever = FixedRetriever(listOf(fb("미미식당 너무 시끄러웠어요"))))
        assertEquals(2, r.success!!.attempts)
        assertEquals(listOf("소담식당"), r.placeNames)
        assertTrue(r.prompts[1].contains("불만을 남긴 곳이라 제외"))
        assertEquals(listOf("미미식당"), r.events.filterIsInstance<AssistantEvent.ComplaintsApplied>().single().blocked)
    }

    @Test
    fun `S5-2 흐름 - 세 번 모두 불만 장소만 내면 실패(불만 장소를 결과로 내보내지 않는다)`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당"))), retriever = FixedRetriever(listOf(fb("미미식당 별로"))))
        assertTrue(r.result is OrchestratorResult.Failed)
        assertTrue(r.placeNames.isEmpty())
    }

    @Test
    fun `S5-2 흐름 - 기록된 대상 장소는 프롬프트에 피할 장소로 먼저 알린다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("소담식당"))),
            retriever = FixedRetriever(listOf(fb("별로였어요", targets = listOf("미미식당")))))
        assertTrue(r.prompts.first().contains("피해야 할 장소(사용자 불만): 미미식당"))
    }

    // ══ S5-3 같은 상호의 다른 지점·비슷한 곳 ══════════════════════════════════

    @Test
    fun `S5-3 정상 - 같은 상호의 다른 지점도 제외(PlaceMatcher 규칙)`() {
        val r = gate(listOf("미미식당 역삼점", "소담식당"), fb("미미식당 강남점 너무 시끄러웠어요"))
        assertEquals(listOf("미미식당 역삼점"), r.blocked.map { it.name })
    }

    @Test
    fun `S5-3 정상 - 지역 토큰·이유가 붙은 추천명도 같은 장소로 본다`() {
        val r = gate(listOf("강남 미미식당", "미미식당 — 조용한 곳", "소담식당"), fb("별로", targets = listOf("미미식당")))
        assertEquals(listOf("소담식당"), r.kept.map { it.name })
    }

    @Test
    fun `S5-3 오탐 방지 - 업종명뿐인 불만("카페 별로")은 모든 카페를 막지 않는다`() {
        val r = gate(listOf("카페 모모", "블루보틀 카페"), fb("카페 별로였어요"))
        assertTrue(r.blocked.isEmpty())
    }

    @Test
    fun `S5-3 오탐 방지 - 이름 일부만 겹치는 다른 가게는 제외하지 않는다(미미식당 ↔ 미소식당)`() {
        assertTrue(gate(listOf("미소식당", "미미분식"), fb("미미식당 별로")).blocked.isEmpty())
    }

    @Test
    fun `S5-3 감점 - 비슷한 곳(같은 업종·분위기)은 빼지 않고 랭킹에서 뒤로`() {
        val noisy = fb("시끄러운 술집은 별로였어요", rating = 2)
        val gemini = ScriptedGemini.of(Gem.final(listOf("달빛술집 — 시끄러운 술집 분위기", "소담식당 — 조용한 식당")))
        val r = AgentFlow.run(dinner, gemini, retriever = FixedRetriever(listOf(noisy)))
        assertEquals(listOf("소담식당", "달빛술집"), r.placeNames)
    }

    // ══ S5-4 조사가 붙은 이름 ═══════════════════════════════════════════════

    @Test
    fun `S5-4 정상 - '미미식당은·이랑·에서'처럼 조사가 붙어도 언급으로 본다`() {
        listOf("미미식당은 별로", "미미식당이랑 비교하면 최악", "미미식당에서 먹었는데 별로").forEach {
            assertEquals(it, listOf("미미식당"), gate(listOf("미미식당", "소담식당"), fb(it)).blocked.map { p -> p.name })
        }
    }

    @Test
    fun `S5-4 랭킹 - 조사 붙은 후기도 이름 언급이면 과거만족 최저(감점)`() {
        val bad = listOf(PastImpression("미미식당은 너무 시끄러웠어요", 1))
        assertEquals(0.0, PlaceRanker.pastSatisfaction("미미식당", bad, placeName = "미미식당"), 1e-9)
    }

    @Test
    fun `S5-4 랭킹 - 조사 붙은 만족 후기('소담식당이 최고')는 가점`() {
        val good = listOf(PastImpression("소담식당이 최고였어요", 5))
        assertEquals(1.0, PlaceRanker.pastSatisfaction("소담식당", good, placeName = "소담식당"), 1e-9)
    }

    @Test
    fun `S5-4 랭킹 - 조사 붙은 업종어('술집은')도 비슷한 곳 감점에 쓰인다`() {
        val bad = listOf(PastImpression("시끄러운 술집은 별로", 2))
        assertTrue(PlaceRanker.pastSatisfaction("달빛술집 시끄러운 술집", bad, placeName = "달빛술집") < 0.5)
        assertEquals(0.5, PlaceRanker.pastSatisfaction("소담식당 조용한 식당", bad, placeName = "소담식당"), 1e-9)
    }

    @Test
    fun `S5-4 오탐 방지 - 조사 떼기가 2글자 미만을 만들지 않는다(수도·정도)`() {
        val bad = listOf(PastImpression("정도가 심했어요", 1))
        assertEquals(0.5, PlaceRanker.pastSatisfaction("정원 카페", bad, placeName = "정원 카페"), 1e-9)
    }

    // ══ S5-8 키워드 검색이 불만 후기를 못 찾음 ════════════════════════════════

    private fun row(id: Long, text: String, rating: Int, author: String = "") =
        FeedbackEntity(id = id, roomId = "old-room", date = "2026-09-2$id", feedback = text, rating = rating, authorUid = author)

    @Test
    fun `S5-8 정상 - 불만 후기는 검색 점수와 무관하게 늘 회수(최근 순)`() = runBlocking {
        val dao = FakeFeedbackDao(listOf(row(1, "미미식당 별로", 1), row(2, "소담식당 최고", 5), row(3, "달빛술집 시끄러움", 2)))
        val c = KeywordFallbackRetriever(dao).complaints()
        assertEquals(listOf("달빛술집 시끄러움", "미미식당 별로"), c.map { it.feedback })
    }

    @Test
    fun `S5-8 경계 - 별점 3·0 후기는 불만 채널로 회수하지 않는다, 개수 상한`() = runBlocking {
        val rows = listOf(row(1, "보통", 3), row(2, "미평가", 0)) + (3L..9L).map { row(it, "별로 $it", 1) }
        val c = KeywordFallbackRetriever(FakeFeedbackDao(rows)).complaints(limit = 5)
        assertEquals(5, c.size)
        assertTrue(c.all { it.rating in 1..2 })
    }

    @Test
    fun `S5-8 흐름 - 키워드 검색에 안 걸려도 불만 후기가 프롬프트에 들어가고 그 장소가 빠진다`() {
        val dao = FakeFeedbackDao(listOf(row(1, "미미식당 너무 시끄러웠어요", 1)))
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당"))), retriever = KeywordFallbackRetriever(dao))
        assertTrue(r.prompts.first().contains("미미식당 너무 시끄러웠어요"))
        assertEquals(listOf("소담식당"), r.placeNames)
    }

    @Test
    fun `S5-8 흐름 - 검색 결과와 불만 채널에 같은 후기가 있어도 한 번만 들어간다`() {
        val c = fb("미미식당 너무 시끄러웠어요")
        val retriever = object : com.navoodi.morimi.data.pipeline.FeedbackRetriever {
            override suspend fun retrieve(query: String, topK: Int) = listOf(c)
            override suspend fun complaints(limit: Int) = listOf(c)
        }
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("소담식당"))), retriever = retriever)
        assertEquals(1, Regex("미미식당 너무 시끄러웠어요").findAll(r.prompts.first()).count())
    }

    @Test
    fun `S5-8 서버 장애 - 불만 채널이 예외를 던져도 검색 결과만으로 진행`() {
        val retriever = object : com.navoodi.morimi.data.pipeline.FeedbackRetriever {
            override suspend fun retrieve(query: String, topK: Int) = emptyList<FeedbackEntry>()
            override suspend fun complaints(limit: Int): List<FeedbackEntry> = throw IllegalStateException("DB")
        }
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당"))), retriever = retriever)
        assertEquals(listOf("미미식당"), r.placeNames)
    }

    // ══ 나간 사람의 불만은 반영하지 않는다(MemberScope 규칙과 일치) ═══════════════

    @Test
    fun `퇴장 - 작성자가 현재 멤버면 반영, 나간 사람이면 제외도 프롬프트도 없음`() {
        val byLeaver = fb("미미식당 너무 시끄러웠어요", author = "정하늘")
        val r = AgentFlow.run(dinner, okGemini(), retriever = FixedRetriever(listOf(byLeaver)), memberIds = members)
        assertEquals(listOf("미미식당", "소담식당").toSet(), r.placeNames.toSet())
        assertFalse(r.prompts.first().contains("미미식당 너무 시끄러웠어요"))

        val byMember = fb("미미식당 너무 시끄러웠어요", author = "김민수")
        assertEquals(listOf("소담식당"), AgentFlow.run(dinner, okGemini(), retriever = FixedRetriever(listOf(byMember)), memberIds = members).placeNames)
    }

    @Test
    fun `퇴장 - 작성자 미기록(예전 후기)은 이 기기 사용자 것으로 보고 반영`() {
        val legacy = fb("미미식당 별로", author = "")
        assertEquals(listOf("소담식당"), AgentFlow.run(dinner, okGemini(), retriever = FixedRetriever(listOf(legacy)), memberIds = members).placeNames)
    }

    @Test
    fun `퇴장 - 멤버 정보가 없으면(이전 동작) 작성자와 무관하게 반영`() {
        assertTrue(ComplaintGate.authorIsMember(fb("x", author = "누구든"), null))
    }

    // ══ 후기 대상 기록 규칙 ═══════════════════════════════════════════════

    @Test
    fun `대상 기록 - 결정한 장소가 우선, 없으면 추천이 1곳일 때만 그곳`() {
        assertEquals(listOf("미미식당"), ComplaintGate.feedbackTargets(listOf("미미식당", " "), listOf("소담식당", "서울식당")))
        assertEquals(listOf("소담식당"), ComplaintGate.feedbackTargets(emptyList(), listOf("소담식당")))
        assertTrue("여러 곳 중 어디였는지 모르면 빈 대상", ComplaintGate.feedbackTargets(emptyList(), listOf("소담식당", "서울식당")).isEmpty())
    }

    private fun okGemini() = ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당")))

    @Test
    fun `정상 - 불만 장소를 뺀 결과도 Guardrail 검증은 그대로`() {
        val r = AgentFlow.run(dinner, okGemini(), retriever = FixedRetriever(listOf(fb("미미식당 별로"))))
        assertEquals(VerificationStatus.VERIFIED, r.places.single().verification)
        assertFalse(r.success!!.summary.recommendation.contains("미미식당"))
    }
}
