package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AHP 계산 엔진과 상황별 프리셋 검증.
 *
 * 핵심은 **모든 프리셋의 CR이 0.10 미만**이라는 것 — 이게 깨지면 "기준 가중치가 판단에서
 * 유도됐다"는 주장 자체가 무효가 된다(Saaty 일관성 기준). 프리셋 판단을 손볼 때 이 테스트가
 * 회귀를 막는다.
 */
class AhpEngineTest {

    @Test
    fun `모든 상황 프리셋의 일관성 비율이 임계 미만`() {
        MeetingContext.entries.forEach { ctx ->
            val r = AhpEngine.solve(ctx.basePairwiseMatrix())
            assertTrue(
                "${ctx.name} CR=${r.cr} 이 임계 ${AhpEngine.CONSISTENCY_THRESHOLD} 이상",
                r.cr < AhpEngine.CONSISTENCY_THRESHOLD,
            )
            assertTrue("${ctx.name} consistent=false", r.consistent)
        }
    }

    @Test
    fun `가중치는 합이 1인 우선순위 벡터`() {
        MeetingContext.entries.forEach { ctx ->
            val r = AhpEngine.solve(ctx.basePairwiseMatrix())
            assertEquals("${ctx.name} 가중치 합", 1.0, r.weights.values.sum(), 1e-9)
            assertEquals("${ctx.name} 기준 개수", AhpCriterion.entries.size, r.weights.size)
            r.weights.forEach { (c, w) -> assertTrue("$c 가중치 음수", w > 0.0) }
        }
    }

    @Test
    fun `완전 일관 행렬은 CR이 0이고 가중치가 비율을 그대로 복원한다`() {
        // w = (0.5, 0.25, 0.25) 에서 유도한 a_ij = w_i / w_j → 이론상 CI = 0
        val order = listOf(
            AhpCriterion.PURPOSE_FIT,
            AhpCriterion.PREFERENCE_FIT,
            AhpCriterion.CONSTRAINT_SAFETY,
        )
        val m = PairwiseMatrix.of(
            order = order,
            judgments = mapOf(
                (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 2.0,
                (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 2.0,
                (AhpCriterion.PREFERENCE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to 1.0,
            ),
        )
        val r = AhpEngine.solve(m)
        assertEquals(0.0, r.cr, 1e-9)
        assertEquals(0.5, r.weightOf(AhpCriterion.PURPOSE_FIT), 1e-9)
        assertEquals(0.25, r.weightOf(AhpCriterion.PREFERENCE_FIT), 1e-9)
        assertEquals(0.25, r.weightOf(AhpCriterion.CONSTRAINT_SAFETY), 1e-9)
    }

    @Test
    fun `상황마다 1순위 기준이 다르다 - 프리셋이 실제로 구별된다`() {
        fun top(ctx: MeetingContext) = AhpEngine.solve(ctx.basePairwiseMatrix()).ranked().first().first

        // 밥 약속은 목적(업종)이, 대화 자리는 제약(시끄러움 배제)이, 액티비티는 영업 여부가 최상위
        assertEquals(AhpCriterion.PURPOSE_FIT, top(MeetingContext.MEAL))
        assertEquals(AhpCriterion.CONSTRAINT_SAFETY, top(MeetingContext.CAFE))
        assertEquals(AhpCriterion.CONSTRAINT_SAFETY, top(MeetingContext.CONSOLATION))
        assertEquals(AhpCriterion.VERIFIED_TRUST, top(MeetingContext.ACTIVITY))

        // 밥 약속과 술 약속의 가중치 벡터가 달라야 한다(회의 지적: 둘은 다른 상황이다)
        val meal = AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix()).weights
        val drink = AhpEngine.solve(MeetingContext.DRINK.basePairwiseMatrix()).weights
        assertNotEquals(meal, drink)
    }

    @Test
    fun `역수성이 구조적으로 보장된다`() {
        val m = MeetingContext.MEAL.basePairwiseMatrix()
        val n = m.order.size
        for (i in 0 until n) {
            assertEquals("대각", 1.0, m.value[i][i], 1e-12)
            for (j in 0 until n) {
                assertEquals("역수성 ($i,$j)", 1.0 / m.value[i][j], m.value[j][i], 1e-12)
            }
        }
    }

    @Test
    fun `비정상 판단값은 무시하고 동등으로 둔다`() {
        val m = PairwiseMatrix.of(
            judgments = mapOf(
                (AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT) to 0.0,
                (AhpCriterion.PURPOSE_FIT to AhpCriterion.CONSTRAINT_SAFETY) to -3.0,
                (AhpCriterion.VERIFIED_TRUST to AhpCriterion.VERIFIED_TRUST) to 5.0,
            ),
        )
        assertEquals(1.0, m.judgment(AhpCriterion.PURPOSE_FIT, AhpCriterion.PREFERENCE_FIT), 1e-12)
        assertEquals(1.0, m.judgment(AhpCriterion.PURPOSE_FIT, AhpCriterion.CONSTRAINT_SAFETY), 1e-12)
        val r = AhpEngine.solve(m)
        assertEquals(1.0, r.weights.values.sum(), 1e-9)
    }

    @Test
    fun `종합 점수는 가중합이고 0에서 1 사이다`() {
        val r = AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix())
        val allPerfect = AhpCriterion.entries.associateWith { 1.0 }
        val allZero = AhpCriterion.entries.associateWith { 0.0 }
        assertEquals(1.0, AhpEngine.synthesize(allPerfect, r), 1e-9)
        assertEquals(0.0, AhpEngine.synthesize(allZero, r), 1e-9)

        // 최상위 기준만 만족한 후보가, 최하위 기준만 만족한 후보보다 높아야 한다
        val topOnly = mapOf(AhpCriterion.PURPOSE_FIT to 1.0)
        val bottomOnly = mapOf(AhpCriterion.PAST_SATISFACTION to 1.0)
        assertTrue(AhpEngine.synthesize(topOnly, r) > AhpEngine.synthesize(bottomOnly, r))
    }

    @Test
    fun `범위 밖 점수는 잘라서 합산한다`() {
        val r = AhpEngine.solve(MeetingContext.GENERIC.basePairwiseMatrix())
        val crazy = AhpCriterion.entries.associateWith { 99.0 }
        assertEquals(1.0, AhpEngine.synthesize(crazy, r), 1e-9)
    }
}
