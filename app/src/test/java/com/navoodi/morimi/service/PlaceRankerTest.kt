package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AHP 종합 랭킹 검증.
 *
 * 핵심 주장 두 가지를 테스트로 고정한다:
 *  1. **상황이 바뀌면 순위가 바뀐다** — 그래야 상황 분류와 프리셋이 장식이 아니다
 *  2. **실존하지 않는 장소는 어떤 가중치로도 1위가 되지 못한다** — 실행 가능성은 사전 선별이다
 */
class PlaceRankerTest {

    private fun place(
        name: String,
        reason: String = "",
        status: VerificationStatus = VerificationStatus.VERIFIED,
    ) = RecommendedPlace(name = name, reason = reason, verification = status)

    private fun ahp(ctx: MeetingContext) = AhpEngine.solve(ctx.basePairwiseMatrix())

    private val prefs = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집")

    @Test
    fun `취향에 맞고 검증된 곳이 1위`() {
        val ranked = PlaceRanker.rank(
            places = listOf(
                place("시끌벅적 호프", "시끄러운 술집 분위기"),
                place("조용한 한식당", "조용한 곳에서 식사 가능"),
            ),
            context = MeetingContext.MEAL,
            ahp = ahp(MeetingContext.MEAL),
            preferences = prefs,
        )
        assertEquals("조용한 한식당", ranked.first().place.name)
    }

    @Test
    fun `상황이 바뀌면 순위가 바뀐다`() {
        val bar = place("포차 한잔", "포차 분위기의 술집")
        val restaurant = place("김밥천국", "분식 식당")

        val inDrink = PlaceRanker.rank(listOf(restaurant, bar), MeetingContext.DRINK, ahp(MeetingContext.DRINK), emptyList())
        val inMeal = PlaceRanker.rank(listOf(restaurant, bar), MeetingContext.MEAL, ahp(MeetingContext.MEAL), emptyList())

        assertEquals("포차 한잔", inDrink.first().place.name)
        assertEquals("김밥천국", inMeal.first().place.name)
    }

    @Test
    fun `실존하지 않는 장소는 다른 기준이 좋아도 최하위`() {
        val ranked = PlaceRanker.rank(
            places = listOf(
                place("없는집", "조용한 곳 딱 좋음", VerificationStatus.NOT_FOUND),
                place("시끌벅적 호프", "시끄러운 술집 분위기"),
            ),
            context = MeetingContext.MEAL,
            ahp = ahp(MeetingContext.MEAL),
            preferences = prefs,
        )
        assertEquals("없는집", ranked.last().place.name)
        assertFalse(ranked.last().feasible)
        assertTrue(ranked.first().feasible)
        assertEquals(1, PlaceRanker.feasibleOnly(ranked).size)
    }

    @Test
    fun `제약 위반은 해당 기준 점수가 0`() {
        val ranked = PlaceRanker.rank(
            places = listOf(place("시끄러운 술집 명가", "활기찬 분위기")),
            context = MeetingContext.MEAL,
            ahp = ahp(MeetingContext.MEAL),
            preferences = prefs,
        )
        assertEquals(0.0, ranked.first().breakdown[AhpCriterion.CONSTRAINT_SAFETY]!!, 1e-9)
    }

    @Test
    fun `검증 불가는 검증됨과 구분되는 중간값 - fail open 금지`() {
        assertEquals(1.0, PlaceRanker.verifiedTrust(VerificationStatus.VERIFIED), 1e-9)
        assertEquals(0.0, PlaceRanker.verifiedTrust(VerificationStatus.NOT_FOUND), 1e-9)
        val unverified = PlaceRanker.verifiedTrust(VerificationStatus.UNVERIFIED)
        assertTrue(unverified > 0.0 && unverified < 1.0)
    }

    @Test
    fun `좋았던 후기와 닮으면 가점 나빴던 후기와 닮으면 감점`() {
        val good = listOf(PastImpression("조용한 카페가 대화하기 좋았어", rating = 5))
        val bad = listOf(PastImpression("조용한 카페가 너무 답답했어", rating = 1))
        val text = "조용한 카페"

        val withGood = PlaceRanker.pastSatisfaction(text, good)
        val withBad = PlaceRanker.pastSatisfaction(text, bad)
        val neutral = PlaceRanker.pastSatisfaction(text, emptyList())

        assertTrue("좋은 후기가 가점이 아님", withGood > neutral)
        assertTrue("나쁜 후기가 감점이 아님", withBad < neutral)
    }

    @Test
    fun `평점 없는 후기는 어느 쪽 증거도 되지 않는다`() {
        val unrated = listOf(PastImpression("조용한 카페 갔었어", rating = 0))
        val neutral = PlaceRanker.pastSatisfaction("조용한 카페", emptyList())
        assertEquals(neutral, PlaceRanker.pastSatisfaction("조용한 카페", unrated), 1e-9)
    }

    @Test
    fun `보통 평점 3점은 중립으로 둔다`() {
        val soso = listOf(PastImpression("조용한 카페 갔었어", rating = 3))
        val neutral = PlaceRanker.pastSatisfaction("조용한 카페", emptyList())
        assertEquals(neutral, PlaceRanker.pastSatisfaction("조용한 카페", soso), 1e-9)
    }

    @Test
    fun `좋아요가 없으면 취향 기준은 중립 - 근거 없음이 벌점이 되면 안 된다`() {
        val withNoLikes = PlaceRanker.preferenceFit("아무 식당", emptyList())
        assertTrue(withNoLikes > 0.0 && withNoLikes < 1.0)
    }

    @Test
    fun `목적에 맞는 업종은 만점 다른 상황 전용 업종은 감점`() {
        assertEquals(1.0, PlaceRanker.purposeFit("동네 한식당", MeetingContext.MEAL), 1e-9)
        val foreign = PlaceRanker.purposeFit("이자카야 술집", MeetingContext.MEAL)
        val unknown = PlaceRanker.purposeFit("무언가", MeetingContext.MEAL)
        assertTrue("다른 업종이 중립보다 높음", foreign < unknown)
    }

    @Test
    fun `빈 후보 목록도 안전하다`() {
        assertTrue(PlaceRanker.rank(emptyList(), MeetingContext.MEAL, ahp(MeetingContext.MEAL), prefs).isEmpty())
    }

    @Test
    fun `점수는 항상 0에서 1 사이`() {
        val ranked = PlaceRanker.rank(
            places = listOf(place("조용한 한식당", "조용한 곳"), place("시끄러운 술집 명가", "시끄러운 술집")),
            context = MeetingContext.MEAL,
            ahp = ahp(MeetingContext.MEAL),
            preferences = prefs,
        )
        ranked.forEach { assertTrue("점수 범위 이탈: ${it.score}", it.score in 0.0..1.0) }
    }
}
