package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `docs/SCENARIOS.md`에 실린 수치를 **테스트로 고정**한다 (2026-09-23 T2).
 *
 * 문서의 출력은 발표 자료에 그대로 들어가므로, 코드가 바뀌어 수치가 달라지면
 * 문서가 조용히 거짓이 된다. 여기서 깨지면 SCENARIOS.md도 같이 갱신해야 한다는 뜻이다.
 *
 * 통제 조건: A와 B는 **성향 프로필도 후보 목록도 완전히 동일**하고 대화만 다르다.
 * 그래야 "결론을 가른 것은 취향이 아니라 목적"이라고 말할 수 있다.
 */
class ScenarioSnapshotTest {

    private val chatDate = LocalDate.of(2026, 9, 17) // 목요일

    private fun msg(who: String, text: String) =
        Message(roomId = "demo", senderId = who, senderName = who, content = text)

    /** A·B 공통 성향 프로필 */
    private val sharedProfile = UserStatusEntity(
        roomId = "demo",
        participants = listOf("도윤", "하린", "준호"),
        preferences = listOf("좋아요: 분위기 좋은 곳", "싫어요: 시끄러운 술집"),
        availability = listOf("저녁 가능"),
    )

    /** A·B 공통 후보 픽스처 */
    private val sharedCandidates = listOf(
        RecommendedPlace("루프탑 다이닝", reason = "분위기 좋은 루프탑 코스요리 레스토랑", verification = VerificationStatus.VERIFIED),
        RecommendedPlace("골목 포차", reason = "골목 안쪽 아늑한 포차", verification = VerificationStatus.VERIFIED),
        RecommendedPlace("시끌벅적 호프", reason = "시끄러운 술집 분위기의 호프", verification = VerificationStatus.VERIFIED),
    )

    private val scenarioA = listOf(
        msg("도윤", "이번 주 토요일이 준호 생일인데 축하해주자"),
        msg("하린", "좋아! 홍대에서 분위기 좋은 데로 예약할까? 4명이야"),
        msg("도윤", "1인 4만원 정도까지는 괜찮을 듯"),
    )
    private val scenarioB = listOf(
        msg("도윤", "준호 오늘 회사에서 잘렸대... 많이 속상해하더라"),
        msg("하린", "오늘 저녁에 홍대에서 얼굴이나 보자 4명"),
    )

    private fun weights(ctx: MeetingContext) = AhpEngine.solve(ctx.basePairwiseMatrix())

    private fun rank(messages: List<Message>, candidates: List<RecommendedPlace>, status: UserStatusEntity) =
        ContextClassifier.classify(messages).let { c ->
            c to PlaceRanker.rank(candidates, c.context, weights(c.context), status.preferences)
        }

    private fun pct(v: Double?) = if (v == null) null else Math.round(v * 1000) / 10.0

    // ── 시나리오 A — 축하 자리 ───────────────────────────────────────────────

    @Test
    fun `A - 상황 분류와 점수분포`() {
        val c = ContextClassifier.classify(scenarioA)
        assertEquals(MeetingContext.CELEBRATION, c.context)
        // C8 이후: "축하해주자"가 축하·축하해에 이중 계상되지 않아 9 → 6
        assertEquals(6, c.scores[MeetingContext.CELEBRATION])
        assertEquals(listOf("생일", "축하해"), c.signals.sorted().sortedBy { listOf("생일", "축하해").indexOf(it) })
        assertEquals(1.0, c.confidence, 1e-9)
        assertEquals(null, c.runnerUp)
    }

    @Test
    fun `A - AHP 가중치와 CR은 이번 변경으로 바뀌지 않는다`() {
        val w = weights(MeetingContext.CELEBRATION)
        assertEquals(41.4, pct(w.weightOf(AhpCriterion.PURPOSE_FIT))!!, 0.05)
        assertEquals(24.1, pct(w.weightOf(AhpCriterion.PAST_SATISFACTION))!!, 0.05)
        assertEquals(13.5, pct(w.weightOf(AhpCriterion.PREFERENCE_FIT))!!, 0.05)
        assertEquals(13.5, pct(w.weightOf(AhpCriterion.VERIFIED_TRUST))!!, 0.05)
        assertEquals(7.4, pct(w.weightOf(AhpCriterion.CONSTRAINT_SAFETY))!!, 0.05)
        assertEquals(0.0039, w.cr, 0.0001)
    }

    @Test
    fun `A - 정형 블록에 판정 근거가 없고 슬롯은 그대로다`() {
        val c = ContextClassifier.classify(scenarioA)
        val s = PromptSieve.sieve(
            scenarioA,
            "이번 주 토요일 서울 서북부에서 지인의 생일을 축하하는 모임을 진행할 예정입니다.",
            sharedProfile, chatDate, weights(c.context), c,
        )
        assertFalse("판정 근거 줄이 남아 있음", s.frame.contains("상황 판정 근거"))
        FrameSlot.entries.forEach { assertTrue("슬롯 누락: ${it.label}", s.frame.contains("${it.label}:")) }
        assertEquals("2026-09-19 (토)", s.slot(FrameSlot.WHEN))
        assertEquals("홍대", s.slot(FrameSlot.WHERE))
        assertEquals("4명", s.slot(FrameSlot.HEADCOUNT))
        assertEquals("1인 4만원 내외", s.slot(FrameSlot.BUDGET))
        assertEquals(listOf(FrameSlot.TIME_OF_DAY), s.missing)
        assertEquals("홍대 분위기 좋은 레스토랑 파티룸", s.placeQuery)
        // 이름은 어디에도 실리지 않는다
        listOf("도윤", "하린", "준호").forEach { assertFalse("이름 유출: $it", s.frame.contains(it)) }
    }

    /**
     * **블록 전문을 통째로 고정한다 (2026-09-23 D10).**
     *
     * 슬롯 존재 여부만 검사하던 탓에, C4로 `상황 판정 근거` 줄이 사라진 뒤에도
     * `docs/SCENARIOS.md`가 그 줄이 있는 블록을 "전송본 **전체**"라고 싣고 있었다.
     * 문서가 "실제로 나가는 전문"이라고 주장하는 자리이므로 전문으로 고정한다.
     * 여기서 깨지면 SCENARIOS.md 3단계 블록도 같이 갱신해야 한다는 뜻이다.
     */
    @Test
    fun `A - 전송본 전문이 문서와 한 글자도 다르지 않다`() {
        val c = ContextClassifier.classify(scenarioA)
        val s = PromptSieve.sieve(
            scenarioA,
            "이번 주 토요일 서울 서북부에서 지인의 생일을 축하하는 모임을 진행할 예정입니다.",
            sharedProfile, chatDate, weights(c.context), c,
        )
        val expected = """
            [정형화 요청 블록 v1]
            상황: 축하·기념 (CELEBRATION, 신뢰도 100.0%)
            목적: 생일·합격 등을 축하하는 자리
            일시: 2026-09-19 (토)
            시간대: 미정
            지역: 홍대
            인원: 4명
            예산: 1인 4만원 내외
            선호: 분위기 좋은 곳
            제약(배제 조건): 시끄러운 술집
            가능 일정: 저녁 가능

            [판단 기준 가중치 — AHP, 이 순서대로 중요도를 두고 고를 것]
            1. 목적 적합 41.4% — 그날 모임의 목적·상황(식사/술자리/위로 등)에 업종이 맞는가
            2. 과거 만족 24.1% — 유사한 과거 모임 후기에서 만족도가 높았던 유형인가
            3. 취향 적합 13.5% — 참가자 성향 프로필의 '좋아요' 항목을 얼마나 만족시키는가
            4. 검증 신뢰 13.5% — 실존·영업이 팩트 체크로 확인됐는가 (할루시네이션 방어)
            5. 제약 준수 7.4% — '싫어요' 항목·금지 조건을 위반하지 않는가
            일관성 비율 CR=0.004 (유효, 임계 0.1)

            [대화 요약 — 온디바이스 익명화 결과]
            이번 주 토요일 서울 서북부에서 지인의 생일을 축하하는 모임을 진행할 예정입니다.
            ※ 날짜·시간·지역은 위 슬롯 값이 요약문 표현보다 우선한다. 요약문의 상대 표현을 다시 해석하지 말 것.

            [미확정 슬롯 — 값을 지어내지 말 것]
            시간대
            위 항목은 대화에서 확인되지 않았다. 임의로 가정하지 말고 추천 이유에서 그 사실을 전제로 다뤄라.
        """.trimIndent()
        assertEquals(expected, s.frame.trim())
    }

    @Test
    fun `A - 순위는 이번 변경으로 바뀌지 않는다`() {
        val (_, ranked) = rank(scenarioA, sharedCandidates, sharedProfile)
        assertEquals(listOf("루프탑 다이닝", "골목 포차", "시끌벅적 호프"), ranked.map { it.place.name })
        assertEquals(0.879, ranked[0].score, 0.001)
        assertEquals(0.564, ranked[1].score, 0.001)
        assertEquals(0.490, ranked[2].score, 0.001)
    }

    @Test
    fun `A - 지표 (D1 재정의)`() {
        val (_, ranked) = rank(scenarioA, sharedCandidates, sharedProfile)
        val places = ranked.map { it.place }
        val violations = ReflectionService.violatingItemCount(places, emptyList(), sharedProfile.preferences)
        assertEquals("위반 항목 수", 1, violations)

        val m = HarnessMetrics.of(places, violations, places.size, attempts = 1, maxAttempts = 3)
        assertEquals(100.0, pct(m.verifiedRate)!!, 0.05)
        assertEquals(0.0, pct(m.hallucinationRate)!!, 0.05)
        assertEquals(100.0, pct(m.coverage)!!, 0.05)
        assertEquals(66.7, pct(m.constraintCompliance)!!, 0.05)
    }

    // ── 시나리오 B — 위로 자리 (A와 동일 프로필·후보) ─────────────────────────

    @Test
    fun `B - 상황 분류`() {
        val c = ContextClassifier.classify(scenarioB)
        assertEquals(MeetingContext.CONSOLATION, c.context)
        assertEquals(6, c.scores[MeetingContext.CONSOLATION])
        assertEquals(1.0, c.confidence, 1e-9)
    }

    @Test
    fun `B - AHP 가중치와 CR`() {
        val w = weights(MeetingContext.CONSOLATION)
        assertEquals(44.2, pct(w.weightOf(AhpCriterion.CONSTRAINT_SAFETY))!!, 0.05)
        assertEquals(28.9, pct(w.weightOf(AhpCriterion.PURPOSE_FIT))!!, 0.05)
        assertEquals(12.3, pct(w.weightOf(AhpCriterion.PREFERENCE_FIT))!!, 0.05)
        assertEquals(0.0123, w.cr, 0.0001)
    }

    @Test
    fun `B - 민감한 판정 근거가 블록에 실리지 않는다`() {
        val c = ContextClassifier.classify(scenarioB)
        val s = PromptSieve.sieve(
            scenarioB,
            "오늘 저녁 서울 서북부에서 힘든 일을 겪은 지인을 위로하는 모임을 진행할 예정입니다.",
            sharedProfile, chatDate, weights(c.context), c,
        )
        assertFalse("민감한 근거 어휘 유출", s.frame.contains("잘렸"))
        assertFalse("민감한 근거 어휘 유출", s.frame.contains("속상"))
        assertEquals("2026-09-17 (목)", s.slot(FrameSlot.WHEN))
        assertEquals(listOf(FrameSlot.BUDGET), s.missing)
    }

    @Test
    fun `핵심 서사 - 같은 프로필 같은 후보인데 목적이 1위를 바꾼다`() {
        val (ca, ra) = rank(scenarioA, sharedCandidates, sharedProfile)
        val (cb, rb) = rank(scenarioB, sharedCandidates, sharedProfile)

        assertEquals(MeetingContext.CELEBRATION, ca.context)
        assertEquals(MeetingContext.CONSOLATION, cb.context)
        assertEquals("루프탑 다이닝", ra.first().place.name)
        assertEquals("골목 포차", rb.first().place.name)
        assertEquals(0.865, rb[0].score, 0.001)
        assertEquals(0.732, rb[1].score, 0.001)

        // 루프탑 다이닝은 B에서 취향 적합이 만점인데도 목적 적합 때문에 2위로 내려간다
        val rooftopInB = rb.first { it.place.name == "루프탑 다이닝" }
        assertEquals(1.0, rooftopInB.breakdown[AhpCriterion.PREFERENCE_FIT]!!, 1e-9)
        assertEquals(0.2, rooftopInB.breakdown[AhpCriterion.PURPOSE_FIT]!!, 1e-9)
    }

    // ── 시나리오 C — 실존하지 않는 가게 ──────────────────────────────────────

    @Test
    fun `C - 순위와 지표 (D1 재정의)`() {
        val profileC = UserStatusEntity(
            roomId = "demo",
            preferences = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집"),
            availability = listOf("토요일 저녁 가능"),
        )
        val candidates = listOf(
            RecommendedPlace("조용한 미식당", reason = "조용한 곳에서 식사 가능한 한식당", verification = VerificationStatus.NOT_FOUND),
            RecommendedPlace("연남 한식당", reason = "차분한 분위기의 한식당", verification = VerificationStatus.VERIFIED),
            RecommendedPlace("확인불가 식당", reason = "조용한 곳으로 알려진 식당", verification = VerificationStatus.UNVERIFIED),
        )
        val messages = listOf(
            msg("도윤", "이번 주 토요일 홍대에서 저녁 먹자 4명"),
            msg("하린", "조용한 데로 1인 3만원 정도"),
        )
        val c = ContextClassifier.classify(messages)
        assertEquals(MeetingContext.MEAL, c.context)

        val ranked = PlaceRanker.rank(candidates, c.context, weights(c.context), profileC.preferences)
        // 실존 부정 후보는 점수가 더 높아도 정렬 최하위 (제거되는 것은 아니다)
        assertEquals(listOf("확인불가 식당", "연남 한식당", "조용한 미식당"), ranked.map { it.place.name })
        assertEquals(0.885, ranked[0].score, 0.001)
        assertEquals(0.779, ranked[1].score, 0.001)
        assertEquals(0.803, ranked[2].score, 0.001)
        assertFalse(ranked[2].feasible)

        val places = ranked.map { it.place }
        val m = HarnessMetrics.of(places, 0, places.size, attempts = 1, maxAttempts = 3)
        // 확인불가는 분자에도 분모에도 없다 — 판정 가능분 2건 기준
        assertEquals(50.0, pct(m.verifiedRate)!!, 0.05)
        assertEquals(50.0, pct(m.hallucinationRate)!!, 0.05)
        assertEquals(66.7, pct(m.coverage)!!, 0.05)
        assertEquals(100.0, pct(m.constraintCompliance)!!, 0.05)
    }

    // ── CR 게이트 ────────────────────────────────────────────────────────────

    @Test
    fun `CAFE 모순 주입 - 기각 전후 CR`() {
        val absurd = listOf(
            JudgmentDelta(AhpCriterion.CONSTRAINT_SAFETY, AhpCriterion.PURPOSE_FIT, 3),
            JudgmentDelta(AhpCriterion.PAST_SATISFACTION, AhpCriterion.PURPOSE_FIT, 3),
            JudgmentDelta(AhpCriterion.VERIFIED_TRUST, AhpCriterion.PREFERENCE_FIT, 3),
        )
        val base = MeetingContext.CAFE.basePairwiseMatrix()
        assertEquals("기본 CR", 0.0081, AhpEngine.solve(base).cr, 0.0001)
        assertEquals("3건 전부 적용 시 CR(임계 초과)", 0.1625, AhpEngine.solve(AhpJudgmentLearner.apply(base, absurd)).cr, 0.0001)

        val resolved = AhpJudgmentLearner.resolve(MeetingContext.CAFE, absurd)
        assertEquals("최종 CR", 0.0714, resolved.result.cr, 0.0001)
        assertEquals("기각 건수", 1, resolved.droppedDeltas.size)
    }
}
