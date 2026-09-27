package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 하네스 지표 계산 검증 (2026-09-23 D1·D8 재정의 반영).
 *
 * 핵심은 **UNVERIFIED가 실존 확인율·할루시네이션율의 분자에도 분모에도 들어가지 않는 것**이다.
 * 예전 정의는 확인불가를 분모에 넣어, 카카오 키가 없으면 정확도가 0%로 보여 모델 탓처럼 읽혔다.
 */
class HarnessMetricsTest {

    private fun place(name: String, status: VerificationStatus) =
        RecommendedPlace(name = name, verification = status)

    private fun metrics(
        places: List<RecommendedPlace>,
        violationItems: Int = 0,
        itemCount: Int = places.size,
        attempts: Int = 1,
        raw: VerificationTally = VerificationTally(),
    ) = HarnessMetrics.of(places, violationItems, itemCount, attempts, maxAttempts = 3, rawCounts = raw)

    // ── 실존 확인율 / 할루시네이션율 / 확인률 (D1) ────────────────────────────

    @Test
    fun `분모는 판정 가능분 - 확인불가는 양쪽 비율에서 빠진다`() {
        // 시나리오 C: 검증 1 / 미발견 1 / 확인불가 1
        val m = metrics(
            listOf(
                place("연남 한식당", VerificationStatus.VERIFIED),
                place("조용한 미식당", VerificationStatus.NOT_FOUND),
                place("확인불가 식당", VerificationStatus.UNVERIFIED),
            ),
        )
        assertEquals(0.5, m.verifiedRate!!, 1e-9)
        assertEquals(0.5, m.hallucinationRate!!, 1e-9)
        assertEquals(2.0 / 3, m.coverage!!, 1e-9)
    }

    @Test
    fun `전부 확인불가면 비율은 판정 불가이고 확인률은 0`() {
        // 카카오 키가 없을 때의 상태 — 정확도 0%가 아니라 "판정 불가"여야 한다
        val m = metrics(
            listOf(
                place("A", VerificationStatus.UNVERIFIED),
                place("B", VerificationStatus.UNVERIFIED),
            ),
        )
        assertNull(m.verifiedRate)
        assertNull(m.hallucinationRate)
        assertEquals(0.0, m.coverage!!, 1e-9)
    }

    @Test
    fun `장소가 없으면 전부 판정 불가`() {
        val m = metrics(emptyList(), itemCount = 0)
        assertNull(m.verifiedRate)
        assertNull(m.hallucinationRate)
        assertNull(m.coverage)
        assertNull(m.constraintCompliance)
    }

    @Test
    fun `전부 검증되면 확인율 100 할루시네이션 0`() {
        val m = metrics(List(3) { place("P$it", VerificationStatus.VERIFIED) })
        assertEquals(1.0, m.verifiedRate!!, 1e-9)
        assertEquals(0.0, m.hallucinationRate!!, 1e-9)
        assertEquals(1.0, m.coverage!!, 1e-9)
    }

    // ── 제약 준수율: 분자·분모 모두 '항목' 단위 (C2) ─────────────────────────

    @Test
    fun `장소 3곳이 모두 같은 불호를 어기면 0`() {
        val m = metrics(List(3) { place("P$it", VerificationStatus.VERIFIED) }, violationItems = 3, itemCount = 3)
        assertEquals(0.0, m.constraintCompliance!!, 1e-9)
    }

    @Test
    fun `장소 3곳 중 1곳이 불호 2개를 어겨도 위반 항목은 1`() {
        // 구절 단위로 세면 2건이라 준수율이 0.333으로 과소평가된다 — 항목 단위라 0.667
        val m = metrics(List(3) { place("P$it", VerificationStatus.VERIFIED) }, violationItems = 1, itemCount = 3)
        assertEquals(2.0 / 3, m.constraintCompliance!!, 1e-9)
    }

    @Test
    fun `위반 항목이 항목 수를 넘어도 음수로 내려가지 않는다`() {
        val m = metrics(List(2) { place("P$it", VerificationStatus.VERIFIED) }, violationItems = 5, itemCount = 2)
        assertEquals(0.0, m.constraintCompliance!!, 1e-9)
    }

    @Test
    fun `항목이 0이면 제약 준수율은 판정 불가`() {
        val m = metrics(emptyList(), violationItems = 0, itemCount = 0)
        assertNull(m.constraintCompliance)
    }

    // ── 모델 원시 할루시네이션 (C1) ──────────────────────────────────────────

    @Test
    fun `원시 할루시네이션은 전 시도 누적으로 센다`() {
        // 1회차 NOT_FOUND 1/3 → 2회차 전부 VERIFIED 3/3 → 누적 검증4 미발견2
        val raw = VerificationTally(verified = 2, notFound = 1) + VerificationTally(verified = 3)
        val m = metrics(
            places = List(3) { place("P$it", VerificationStatus.VERIFIED) },
            attempts = 2,
            raw = raw,
        )
        // 최종 기준은 0 (사용자에게 간 결과에는 NOT_FOUND가 없다)
        assertEquals(0.0, m.hallucinationRate!!, 1e-9)
        // 원시 기준은 1/6
        assertEquals(1.0 / 6, m.rawHallucinationRate!!, 1e-6)
    }

    @Test
    fun `원시 집계가 없으면 원시 할루시네이션은 판정 불가`() {
        val m = metrics(List(2) { place("P$it", VerificationStatus.VERIFIED) })
        assertNull(m.rawHallucinationRate)
    }

    @Test
    fun `VerificationTally 합산`() {
        val a = VerificationTally(1, 2, 3)
        val b = VerificationTally(10, 20, 30)
        assertEquals(VerificationTally(11, 22, 33), a + b)
    }

    // ── 재시도 효율·만족도 ───────────────────────────────────────────────────

    @Test
    fun `재시도 효율은 1회 통과가 만점 상한 도달이 0점`() {
        fun eff(attempts: Int) = metrics(emptyList(), itemCount = 0, attempts = attempts).retryEfficiency
        assertEquals(1.0, eff(1), 1e-9)
        assertEquals(0.5, eff(2), 1e-9)
        assertEquals(0.0, eff(3), 1e-9)
    }

    @Test
    fun `만족 임계는 5점 척도 4점에서 유도된다`() {
        assertEquals(HarnessMetrics.SATISFACTION_THRESHOLD, HarnessMetrics.satisfactionFromRating(4), 1e-9)
        assertEquals(0.2, HarnessMetrics.satisfactionFromRating(1), 1e-9)
        assertEquals(1.0, HarnessMetrics.satisfactionFromRating(5), 1e-9)
        assertEquals(1.0, HarnessMetrics.satisfactionFromRating(99), 1e-9)
        assertEquals(0.2, HarnessMetrics.satisfactionFromRating(-3), 1e-9)
    }

    @Test
    fun `요약 문자열은 판정 불가를 숨기지 않는다`() {
        val m = metrics(listOf(place("A", VerificationStatus.UNVERIFIED)), itemCount = 1)
        val s = m.summary()
        assertTrue(s, s.contains("실존 확인율 판정 불가"))
        assertTrue(s, s.contains("확인률 0.0%"))
    }

    // ── 추이 요약 ────────────────────────────────────────────────────────────

    private fun point(satisfaction: Double?, verified: Int = 1, notFound: Int = 0) = HarnessRunPoint(
        timestamp = 0L,
        roomId = "r1",
        context = MeetingContext.MEAL,
        metrics = HarnessMetrics(
            placeCount = verified + notFound,
            verifiedCount = verified, notFoundCount = notFound, unverifiedCount = 0,
            itemCount = 1, violationItemCount = 0,
            attempts = 1, retryEfficiency = 1.0,
        ),
        satisfaction = satisfaction,
        consistencyRatio = 0.02,
    )

    @Test
    fun `추이 요약은 평가된 건만 만족도 평균에 넣는다`() {
        val t = TrendSummary.of(listOf(point(1.0), point(null), point(0.6)))
        assertEquals(3, t.sampleSize)
        assertEquals(2, t.ratedCount)
        assertEquals(0.8, t.averageSatisfaction!!, 1e-9)
    }

    @Test
    fun `판정 불가인 실행은 지표 평균에서 제외된다`() {
        // 장소 0인 실행(전부 판정 불가)이 섞여도 평균이 0으로 끌려가지 않는다
        val judged = point(null, verified = 1)
        val unjudged = HarnessRunPoint(
            0L, "r1", MeetingContext.MEAL,
            HarnessMetrics(0, 0, 0, 0, 0, 0, attempts = 1, retryEfficiency = 1.0),
            null, 0.02,
        )
        val t = TrendSummary.of(listOf(judged, unjudged))
        // judged만 평균에 들어간다 — unjudged(장소 0, 항목 0)는 전부 null이라 제외
        assertEquals(1.0, t.averageVerifiedRate!!, 1e-9)
        assertEquals(1.0, t.averageCompliance!!, 1e-9)
        assertEquals(2, t.sampleSize)
    }

    @Test
    fun `임계 도달 여부를 평균으로 판정한다`() {
        assertTrue(TrendSummary.of(listOf(point(0.8), point(1.0))).meetsThreshold)
        assertFalse(TrendSummary.of(listOf(point(0.6), point(0.6))).meetsThreshold)
        assertFalse(TrendSummary.of(listOf(point(null))).meetsThreshold)
    }

    @Test
    fun `빈 추이도 안전하다`() {
        val t = TrendSummary.of(emptyList())
        assertEquals(0, t.sampleSize)
        assertNull(t.averageSatisfaction)
        assertNull(t.averageVerifiedRate)
        assertFalse(t.meetsThreshold)
    }
}
