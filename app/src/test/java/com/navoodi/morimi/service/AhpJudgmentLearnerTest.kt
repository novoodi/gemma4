package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AHP 판단 재학습 검증 — 피드백 순환 고리가 실제로 가중치를 움직이는지, 그리고
 * **CR이 채택 게이트로 동작하는지**.
 *
 * CR 게이트가 형식적이면(무엇을 넣어도 통과) 일관성 검사를 한다는 주장이 무의미해지므로,
 * 모순된 보정이 실제로 기각되는 경우를 테스트로 고정한다.
 */
class AhpJudgmentLearnerTest {

    /** 불만(2점) 증거 — D3 이후 보정은 1~2점에서만 일어난다 */
    private fun unhappy(violations: Int = 0, hallucinated: Int = 0) =
        RunEvidence(violations, hallucinated, satisfaction = 0.4)

    @Test
    fun `제약 위반이 나면 제약 준수 대 취향 적합 셀을 움직인다`() {
        val d = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 2))
        assertTrue(
            "축이 (제약 준수, 취향 적합)이 아님: $d",
            d.any {
                it.row == AhpCriterion.CONSTRAINT_SAFETY &&
                    it.col == AhpCriterion.PREFERENCE_FIT && it.steps == 1
            },
        )
    }

    @Test
    fun `실존하지 않는 장소가 나오면 검증 신뢰 대 취향 적합 셀을 움직인다`() {
        val d = AhpJudgmentLearner.learn(emptyList(), unhappy(hallucinated = 1))
        assertTrue(
            "축이 (검증 신뢰, 취향 적합)이 아님: $d",
            d.any {
                it.row == AhpCriterion.VERIFIED_TRUST &&
                    it.col == AhpCriterion.PREFERENCE_FIT && it.steps == 1
            },
        )
    }

    @Test
    fun `원인 불명 불만이면 취향과 과거만족을 목적 적합 대비로 올린다`() {
        val d = AhpJudgmentLearner.learn(emptyList(), unhappy())
        assertTrue(d.any { it.row == AhpCriterion.PREFERENCE_FIT && it.col == AhpCriterion.PURPOSE_FIT })
        assertTrue(d.any { it.row == AhpCriterion.PAST_SATISFACTION && it.col == AhpCriterion.PURPOSE_FIT })
    }

    @Test
    fun `3점은 보정도 감쇠도 일으키지 않는다`() {
        // D3: PlaceRanker의 과거 만족 기준과 해석을 통일 — 3점은 어느 쪽 증거도 아니다
        val seeded = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1))
        assertEquals(seeded, AhpJudgmentLearner.learn(seeded, RunEvidence(satisfaction = 0.6)))
        assertTrue(AhpJudgmentLearner.learn(emptyList(), RunEvidence(satisfaction = 0.6)).isEmpty())
    }

    @Test
    fun `미평가는 아무것도 하지 않는다`() {
        val seeded = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1))
        assertEquals(seeded, AhpJudgmentLearner.learn(seeded, RunEvidence()))
    }

    @Test
    fun `만족 1회는 가장 오래된 보정만 1칸 해제한다 - FIFO`() {
        // 제약(먼저) → 검증(나중) 순으로 쌓고 만족을 주면 제약만 풀려야 한다
        var d = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1))
        d = AhpJudgmentLearner.learn(d, unhappy(hallucinated = 1))
        assertEquals(2, d.size)

        val after = AhpJudgmentLearner.learn(d, RunEvidence(satisfaction = 1.0))
        assertTrue(
            "가장 오래된(제약 준수) 보정이 남아 있음: $after",
            after.none { it.row == AhpCriterion.CONSTRAINT_SAFETY },
        )
        assertTrue(
            "나중 보정(검증 신뢰)까지 사라짐: $after",
            after.any { it.row == AhpCriterion.VERIFIED_TRUST && it.steps == 1 },
        )
    }

    @Test
    fun `만족이 반복되면 결국 기본 판단으로 수렴한다`() {
        var d = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1))
        repeat(6) { d = AhpJudgmentLearner.learn(d, RunEvidence(satisfaction = 1.0)) }
        assertTrue("기본으로 수렴하지 않음: $d", d.isEmpty())
    }

    @Test
    fun `보정에는 상한이 있다`() {
        var d = emptyList<JudgmentDelta>()
        repeat(10) { d = AhpJudgmentLearner.learn(d, unhappy(violations = 1)) }
        assertTrue(d.all { it.steps <= AhpJudgmentLearner.MAX_STEPS })
    }

    @Test
    fun `학습이 실제로 가중치를 움직인다`() {
        val base = AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix())
        val deltas = AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1))
        val learned = AhpJudgmentLearner.resolve(MeetingContext.MEAL, deltas)

        assertTrue(learned.accepted)
        assertTrue(
            "제약 준수 가중치가 오르지 않음",
            learned.result.weightOf(AhpCriterion.CONSTRAINT_SAFETY) >
                base.weightOf(AhpCriterion.CONSTRAINT_SAFETY),
        )
    }

    @Test
    fun `학습 결과도 일관성 임계를 지킨다`() {
        MeetingContext.entries.forEach { ctx ->
            var d = emptyList<JudgmentDelta>()
            repeat(4) { d = AhpJudgmentLearner.learn(d, unhappy(violations = 1, hallucinated = 1)) }
            val learned = AhpJudgmentLearner.resolve(ctx, d)
            assertTrue(
                "${ctx.name} 학습 후 CR=${learned.result.cr}",
                learned.result.cr < AhpEngine.CONSISTENCY_THRESHOLD,
            )
        }
    }

    @Test
    fun `모순된 보정은 CR 게이트에서 되돌려진다`() {
        // 한 칸씩 극단으로 밀어 추이성을 깨뜨린다
        val absurd = listOf(
            JudgmentDelta(AhpCriterion.CONSTRAINT_SAFETY, AhpCriterion.PURPOSE_FIT, 3),
            JudgmentDelta(AhpCriterion.PAST_SATISFACTION, AhpCriterion.PURPOSE_FIT, 3),
            JudgmentDelta(AhpCriterion.VERIFIED_TRUST, AhpCriterion.PREFERENCE_FIT, 3),
        )
        val learned = AhpJudgmentLearner.resolve(MeetingContext.CAFE, absurd)

        assertTrue("아무것도 기각되지 않음 — 게이트가 형식적", learned.droppedDeltas.isNotEmpty())
        assertTrue("기각 후에도 일관적이지 않음", learned.result.cr < AhpEngine.CONSISTENCY_THRESHOLD)
    }

    @Test
    fun `CR을 깨는 보정은 통째로 버리지 않고 칸수를 낮춰 안착한다`() {
        // D9로 MAX_STEPS를 5로 올리면서 필요해진 동작.
        // DRINK는 검증 신뢰 축 4칸부터 CR이 임계를 넘는다 — 예전 로직이면 유일한 보정이라
        // 통째로 버려지고 기본 판단으로 롤백돼, 3칸에서 안정적으로 남던 학습이 0이 됐다.
        val five = listOf(JudgmentDelta(AhpCriterion.VERIFIED_TRUST, AhpCriterion.PREFERENCE_FIT, 5))
        val learned = AhpJudgmentLearner.resolve(MeetingContext.DRINK, five)

        assertTrue("롤백됨 — 학습이 증발했다", learned.appliedDeltas.isNotEmpty())
        assertTrue("일관성 회복 실패", learned.result.cr < AhpEngine.CONSISTENCY_THRESHOLD)
        val applied = learned.appliedDeltas.single()
        assertTrue("칸수가 낮아지지 않음: ${applied.steps}", applied.steps in 1 until 5)
        // 축은 유지된다 — 낮추는 것은 칸수뿐
        assertEquals(AhpCriterion.VERIFIED_TRUST, applied.row)
        assertEquals(AhpCriterion.PREFERENCE_FIT, applied.col)
    }

    @Test
    fun `어떤 상황과 축 조합도 완전 롤백되지 않는다`() {
        // 상한까지 밀었을 때 학습이 통째로 사라지는 조합이 하나라도 있으면 안 된다
        val axes = listOf(AhpCriterion.VERIFIED_TRUST, AhpCriterion.CONSTRAINT_SAFETY)
        MeetingContext.entries.forEach { ctx ->
            axes.forEach { row ->
                val d = listOf(JudgmentDelta(row, AhpCriterion.PREFERENCE_FIT, AhpJudgmentLearner.MAX_STEPS))
                val learned = AhpJudgmentLearner.resolve(ctx, d)
                assertTrue(
                    "${ctx.name} × ${row.name}: 완전 롤백됨",
                    learned.appliedDeltas.isNotEmpty(),
                )
                assertTrue(
                    "${ctx.name} × ${row.name}: CR=${learned.result.cr}",
                    learned.result.cr < AhpEngine.CONSISTENCY_THRESHOLD,
                )
            }
        }
    }

    @Test
    fun `학습이 순위를 뒤집는다 - D9 상향의 목적`() {
        // 시나리오 C의 두 후보: 확인불가 식당(취향 만점, 검증 0.5) vs 연남 한식당(취향 0.2, 검증 1.0)
        // MAX_STEPS가 3이던 때는 상한까지 밀어도 역전되지 않았다(격차 0.0138).
        val places = listOf(
            com.navoodi.morimi.data.model.RecommendedPlace(
                "확인불가 식당", reason = "조용한 곳으로 알려진 식당",
                verification = com.navoodi.morimi.data.model.VerificationStatus.UNVERIFIED,
            ),
            com.navoodi.morimi.data.model.RecommendedPlace(
                "연남 한식당", reason = "차분한 분위기의 한식당",
                verification = com.navoodi.morimi.data.model.VerificationStatus.VERIFIED,
            ),
        )
        val prefs = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집")

        // 보정 전: 검증 안 된 쪽이 1위
        val before = AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList())
        assertEquals("확인불가 식당", PlaceRanker.rank(places, MeetingContext.MEAL, before.result, prefs).first().place.name)

        // "없는 가게 + 2점"을 4회 겪으면 검증 신뢰가 올라 순위가 뒤집힌다
        var d = emptyList<JudgmentDelta>()
        repeat(4) { d = AhpJudgmentLearner.learn(d, unhappy(hallucinated = 1)) }
        val after = AhpJudgmentLearner.resolve(MeetingContext.MEAL, d)

        assertEquals("역전되지 않음", "연남 한식당",
            PlaceRanker.rank(places, MeetingContext.MEAL, after.result, prefs).first().place.name)
        assertTrue("역전했는데 CR이 임계를 넘음: ${after.result.cr}",
            after.result.cr < AhpEngine.CONSISTENCY_THRESHOLD)
    }

    @Test
    fun `보정이 없으면 상황 기본 가중치 그대로`() {
        val learned = AhpJudgmentLearner.resolve(MeetingContext.MEAL, emptyList())
        val base = AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix())
        assertTrue(learned.accepted)
        assertFalse(learned.isLearned)
        assertEquals(base.weights, learned.result.weights)
    }

    @Test
    fun `사다리 이동은 Saaty 척도 위에서만 일어나고 범위를 벗어나지 않는다`() {
        assertEquals(3.0, AhpJudgmentLearner.shift(2.0, 1), 1e-9)
        assertEquals(2.0, AhpJudgmentLearner.shift(3.0, -1), 1e-9)
        assertEquals(1.0 / 2, AhpJudgmentLearner.shift(1.0 / 3, 1), 1e-9)
        assertEquals(9.0, AhpJudgmentLearner.shift(9.0, 5), 1e-9)
        assertEquals(1.0 / 9, AhpJudgmentLearner.shift(1.0 / 9, -5), 1e-9)
    }

    @Test
    fun `직렬화 왕복이 보존된다`() {
        val d = AhpJudgmentLearner.learn(
            AhpJudgmentLearner.learn(emptyList(), unhappy(violations = 1)),
            unhappy(hallucinated = 1),
        )
        assertEquals(d, AhpJudgmentLearner.decode(AhpJudgmentLearner.encode(d)))
    }

    @Test
    fun `깨진 직렬화는 건너뛴다 - 크래시하지 않는다`() {
        val decoded = AhpJudgmentLearner.decode("GARBAGE>NOPE:x|CONSTRAINT_SAFETY>PURPOSE_FIT:2|::")
        assertEquals(1, decoded.size)
        assertEquals(AhpCriterion.CONSTRAINT_SAFETY, decoded.first().row)
        assertTrue(AhpJudgmentLearner.decode("").isEmpty())
    }
}
