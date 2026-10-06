package com.navoodi.morimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/D9_DECISION_BRIEF.md`의 핵심 수치를 **관측값으로 고정**한다 (2026-09-23 S5).
 *
 * 이 테스트는 "옳은 동작"을 주장하지 않는다. 브리프가 팀 결정의 근거로 쓰이므로,
 * **운영 코드가 바뀌면 그 자료도 다시 만들어야 한다**는 신호를 주는 것이 목적이다.
 * 여기서 깨지면 브리프의 표 1·2를 다시 뽑아야 한다.
 *
 * `MAX_STEPS`에 의존하지 않는다 — 상한을 A/B/C 중 무엇으로 정하든 이 표는 유효해야 한다.
 * 그래서 학습기의 게이트(`resolve`)가 아니라 `apply` + `AhpEngine.solve`로 직접 계산한다.
 */
class D9DecisionBriefTest {

    private val VT = AhpCriterion.VERIFIED_TRUST
    private val PF = AhpCriterion.PREFERENCE_FIT
    private val CS = AhpCriterion.CONSTRAINT_SAFETY

    /** `trimToConsistent` 없이 k칸을 그대로 적용했을 때의 결과 */
    private fun raw(ctx: MeetingContext, row: AhpCriterion, k: Int): AhpResult {
        val base = ctx.basePairwiseMatrix()
        if (k == 0) return AhpEngine.solve(base)
        return AhpEngine.solve(AhpJudgmentLearner.apply(base, listOf(JudgmentDelta(row, PF, k))))
    }

    /**
     * 시나리오 C의 두 후보는 목적 적합·제약 준수·과거 만족 점수가 같다.
     * 그래서 점수 차가 두 기준으로만 결정된다 — 계수는 `PlaceRanker` 실측값이다
     * (확인불가 − 연남: 취향 적합 +0.8, 검증 신뢰 −0.5).
     */
    private fun gap(r: AhpResult) = 0.8 * r.weightOf(PF) - 0.5 * r.weightOf(VT)

    /** k=0부터 연속으로 CR<0.10을 지키는 최대 k (0..6 범위) */
    private fun maxConsistentK(ctx: MeetingContext, row: AhpCriterion): Int =
        (0..6).last { upTo -> (0..upTo).all { raw(ctx, row, it).consistent } }

    // ── 표 1 ────────────────────────────────────────────────────────────────

    @Test
    fun `표 1 - 18조합 모두 CR 임계를 지키는 최대 보정 칸수는 2다`() {
        val perCombo = MeetingContext.entries.flatMap { ctx ->
            listOf(VT, CS).map { row -> Triple(ctx, row, maxConsistentK(ctx, row)) }
        }
        assertEquals("조합 수", 18, perCombo.size)
        assertEquals("18조합 공통 최대 k", 2, perCombo.minOf { it.third })
    }

    @Test
    fun `표 1 - 공통 상한을 정하는 것은 MEAL 제약준수 축이다`() {
        // 이 조합이 k=3에서 혼자 먼저 임계를 넘는다. 여유가 조합마다 크게 다르다는 근거.
        assertEquals(2, maxConsistentK(MeetingContext.MEAL, CS))
        assertTrue("k=3에서 임계 초과여야 한다", raw(MeetingContext.MEAL, CS, 3).cr >= 0.10)
        assertEquals(0.1251, raw(MeetingContext.MEAL, CS, 3).cr, 0.0005)

        // 반대편 — 같은 k=6에서도 한참 여유 있는 조합이 있다
        assertEquals(6, maxConsistentK(MeetingContext.DRINK, CS))
        assertTrue(raw(MeetingContext.DRINK, CS, 6).cr < 0.07)
    }

    @Test
    fun `표 1 - 사다리 끝에 닿으면 칸수를 올려도 CR이 더 움직이지 않는다`() {
        // CONSOLATION 제약준수 축: k=5와 k=6이 같은 값 → 상한을 올려도 더 세지지 않는다
        val k5 = raw(MeetingContext.CONSOLATION, CS, 5).cr
        val k6 = raw(MeetingContext.CONSOLATION, CS, 6).cr
        assertEquals(k5, k6, 1e-9)
    }

    // ── 표 2 ────────────────────────────────────────────────────────────────

    @Test
    fun `표 2 - MEAL 값이 00_START_HERE 3절 ④와 일치한다`() {
        val meal = MeetingContext.MEAL
        assertEquals("k=0 격차", 0.1057, gap(raw(meal, VT, 0)), 0.0005)
        assertEquals("k=3 격차", 0.0138, gap(raw(meal, VT, 3)), 0.0005)

        val flipK = (0..6).first { gap(raw(meal, VT, it)) < 0 }
        assertEquals("역전 최소 k", 4, flipK)
        assertEquals("역전 시점 CR", 0.0744, raw(meal, VT, flipK).cr, 0.0005)
        assertTrue("역전 시점이 임계 이내여야 한다", raw(meal, VT, flipK).consistent)
    }

    @Test
    fun `표 2 - 역전은 MEAL만의 현상이 아니다`() {
        // 상한을 올려야만 역전이 나온다는 그림을 반증하는 근거.
        fun flipK(ctx: MeetingContext) = (0..6).firstOrNull { gap(raw(ctx, VT, it)) < 0 }

        // 검증 신뢰가 원래 1순위인 상황은 보정 없이도 실존 확인 후보가 이긴다
        assertEquals("ACTIVITY는 보정 0칸", 0, flipK(MeetingContext.ACTIVITY))
        assertEquals("TRIP은 보정 0칸", 0, flipK(MeetingContext.TRIP))
        assertEquals("STUDY는 1칸", 1, flipK(MeetingContext.STUDY))
        assertEquals("CELEBRATION은 3칸", 3, flipK(MeetingContext.CELEBRATION))
        assertEquals("MEAL은 4칸", 4, flipK(MeetingContext.MEAL))

        val flippable = MeetingContext.entries.count { ctx ->
            flipK(ctx)?.let { raw(ctx, VT, it).consistent } == true
        }
        assertEquals("임계 안에서 역전하는 상황 수", 5, flippable)
    }

    @Test
    fun `표 2 - 상한을 올려도 역전하지 않는 상황이 있다`() {
        // 이 후보 쌍에서는 DRINK·CAFE·GENERIC이 7칸 안에서 뒤집히지 않는다
        listOf(MeetingContext.DRINK, MeetingContext.CAFE, MeetingContext.GENERIC).forEach { ctx ->
            assertNull("${ctx.name}은 7칸 내 역전이 없어야 한다", (0..6).firstOrNull { gap(raw(ctx, VT, it)) < 0 })
        }
        // CONSOLATION은 6칸에서 뒤집히지만 그때 CR이 임계를 넘어 게이트가 막는다
        val k = (0..6).first { gap(raw(MeetingContext.CONSOLATION, VT, it)) < 0 }
        assertEquals(6, k)
        assertTrue("게이트가 막아야 한다", raw(MeetingContext.CONSOLATION, VT, k).cr >= 0.10)
    }

    // ── 선택지 ──────────────────────────────────────────────────────────────

    @Test
    fun `선택지 A 상한 2는 MEAL을 역전시키지 못하지만 격차를 68퍼센트 줄인다`() {
        val meal = MeetingContext.MEAL
        val g0 = gap(raw(meal, VT, 0))
        val g2 = gap(raw(meal, VT, 2))
        assertTrue("상한 2에서는 역전하지 않는다", g2 > 0)
        assertEquals("상한칸 격차", 0.0342, g2, 0.0005)
        assertEquals("축소율(%)", 68.0, (1 - g2 / g0) * 100, 1.0)
    }

    @Test
    fun `선택지 B 현행 상한 5는 MEAL을 역전시키고 상한칸도 임계 이내다`() {
        val meal = MeetingContext.MEAL
        assertTrue("상한 5에서 역전", gap(raw(meal, VT, 5)) < 0)
        assertEquals("상한칸 CR", 0.0961, raw(meal, VT, 5).cr, 0.0005)
        assertTrue("상한칸이 임계 이내", raw(meal, VT, 5).consistent)
        // 6칸은 넘는다 — 상한 5가 "일관성이 허용하는 최대치"라는 서술의 근거
        assertTrue("6칸은 임계 초과", raw(meal, VT, 6).cr >= 0.10)
    }

    @Test
    fun `선택지 C 상한 없이 CR만으로도 MEAL은 같은 지점에서 역전한다`() {
        // 이 후보 쌍에서 C는 B와 결과가 같다. 다만 실제 적용은 coerceIn 제거가 필요하다.
        val meal = MeetingContext.MEAL
        val maxK = (0..16).last { upTo -> (0..upTo).all { raw(meal, VT, it).consistent } }
        assertEquals("CR만 상한일 때 유지 가능한 최대 k", 5, maxK)
        assertEquals("역전 최소 k", 4, (0..16).first { gap(raw(meal, VT, it)) < 0 })
    }
}
