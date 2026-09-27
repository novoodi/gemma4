package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Test

class ConstraintConsistencyTest {
    private val context = MeetingContext.MEAL
    private val ahp = AhpEngine.solve(context.basePairwiseMatrix())

    @Test
    fun `주소에만 있는 불호 지역도 자기비평과 랭킹과 지표가 모두 위반으로 본다`() {
        val places = listOf(RecommendedPlace(
            name = "고요 카페", reason = "차분한 공간", address = "서울 강남구",
            verification = VerificationStatus.VERIFIED,
        ))
        val preferences = listOf("싫어요: 강남")
        assertFalse(ReflectionService.reflect(places, emptyList(), preferences).passed)
        assertEquals(0.0, PlaceRanker.rank(places, context, ahp, preferences).single().breakdown[AhpCriterion.CONSTRAINT_SAFETY]!!, 0.0)
        val violations = ReflectionService.violatingItemCount(places, emptyList(), preferences)
        assertEquals(1, violations)
        assertEquals(0.0, HarnessMetrics.of(places, violations, 1, 1, 3).constraintCompliance!!, 0.0)
    }

    @Test
    fun `불호를 피했다는 설명은 양쪽 판정에서 위반이 아니다`() {
        val places = listOf(RecommendedPlace(name = "고요 카페", reason = "술집 대신 따뜻한 차", address = "서울 마포구"))
        val preferences = listOf("싫어요: 술집")
        assertTrue(ReflectionService.reflect(places, emptyList(), preferences).passed)
        assertEquals(1.0, PlaceRanker.rank(places, context, ahp, preferences).single().breakdown[AhpCriterion.CONSTRAINT_SAFETY]!!, 0.0)
        assertEquals(0, ReflectionService.violatingItemCount(places, emptyList(), preferences))
    }

    @Test
    fun `제약 점수는 가중합 감점이며 실존하는 위반 후보를 자동 제외하지 않는다`() {
        val violating = RecommendedPlace(name = "식당", reason = "조용한 술집 레스토랑", verification = VerificationStatus.VERIFIED)
        val compliant = RecommendedPlace(name = "후보", reason = "휴식", verification = VerificationStatus.VERIFIED)
        val preferences = listOf("좋아요: 조용한 곳", "싫어요: 술집")
        val ranked = PlaceRanker.rank(
            listOf(violating, compliant), context, ahp, preferences,
            listOf(PastImpression("식당 조용한 술집 레스토랑", 5)),
        )
        assertEquals(violating, ranked.first().place)
        assertTrue(ranked.all { it.feasible })
        assertEquals(0.0, ranked.first().breakdown[AhpCriterion.CONSTRAINT_SAFETY]!!, 0.0)
        assertFalse(ReflectionService.reflect(listOf(violating), emptyList(), preferences).passed)
    }
}
