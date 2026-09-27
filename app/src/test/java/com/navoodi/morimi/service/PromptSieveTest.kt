package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 거름망(정형화 게이트) 검증.
 *
 * 특히 **상대 날짜 환산**은 온디바이스에서 확정해야 하는 대표 항목이다 — 모델에게 맡기면
 * 조용히 틀리고, 틀린 날짜로 날씨를 조회해 결과 전체가 어긋난다.
 */
class PromptSieveTest {

    // 2026-09-17 은 목요일
    private val chatDate = LocalDate.of(2026, 9, 17)

    private fun msg(who: String, text: String) =
        Message(roomId = "r1", senderId = who, senderName = who, content = text)

    private val status = UserStatusEntity(
        roomId = "r1",
        participants = listOf("양예찬", "차민영", "이승현"),
        preferences = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집"),
        availability = listOf("토요일 오후 가능"),
    )

    private fun ahp(ctx: MeetingContext) = AhpEngine.solve(ctx.basePairwiseMatrix())

    // ── 날짜 환산 ────────────────────────────────────────────────────────────

    @Test
    fun `이번 주 요일을 절대 날짜로 환산한다`() {
        assertEquals("2026-09-19 (토)", PromptSieve.extractDate("이번 주 토요일에 보자", chatDate))
    }

    @Test
    fun `다음 주는 한 주를 더 민다`() {
        assertEquals("2026-09-26 (토)", PromptSieve.extractDate("다음 주 토요일 어때", chatDate))
    }

    @Test
    fun `오늘 내일 모레를 환산한다`() {
        assertEquals("2026-09-17 (목)", PromptSieve.extractDate("오늘 보자", chatDate))
        assertEquals("2026-09-18 (금)", PromptSieve.extractDate("내일 보자", chatDate))
        assertEquals("2026-09-19 (토)", PromptSieve.extractDate("모레 보자", chatDate))
    }

    @Test
    fun `명시된 월일은 그대로 쓰되 지난 날짜는 내년으로 본다`() {
        assertEquals("2026-10-03 (토)", PromptSieve.extractDate("10월 3일에 모이자", chatDate))
        assertEquals("2027-01-05 (화)", PromptSieve.extractDate("1월 5일에 보자", chatDate))
    }

    @Test
    fun `날짜 단서가 없으면 null - 지어내지 않는다`() {
        assertNull(PromptSieve.extractDate("우리 언제 볼까", chatDate))
    }

    @Test
    fun `존재하지 않는 날짜는 무시한다`() {
        assertNull(PromptSieve.extractDate("13월 40일에 보자", chatDate))
    }

    // ── 나머지 슬롯 ──────────────────────────────────────────────────────────

    @Test
    fun `지역은 알려진 상권명이나 역 동 구 시 패턴에서만 뽑는다`() {
        assertEquals("홍대", PromptSieve.extractArea("홍대에서 만나자"))
        assertEquals("사당", PromptSieve.extractArea("사당역 근처 어때"))
        // 지역처럼 보이는 일반 단어에 걸리면 엉뚱한 곳을 검색하게 된다
        assertNull(PromptSieve.extractArea("이동하기 편한 데로 하자"))
        assertNull(PromptSieve.extractArea("아무 데나 좋아"))
    }

    @Test
    fun `시간대와 시각을 구분해 뽑는다`() {
        assertEquals("저녁 7시", PromptSieve.extractTimeOfDay("저녁 7시에 보자"))
        assertEquals("점심", PromptSieve.extractTimeOfDay("점심에 만날까"))
        // "3시간"의 시는 시각이 아니다
        assertNull(PromptSieve.extractTimeOfDay("3시간쯤 걸려"))
    }

    @Test
    fun `예산은 금액이나 성향 표현에서 뽑는다`() {
        assertEquals("1인 3만원 내외", PromptSieve.extractBudget("1인 3만원 정도로"))
        assertEquals("가성비 중시", PromptSieve.extractBudget("가성비 좋은 데로 가자"))
        assertNull(PromptSieve.extractBudget("아무거나"))
    }

    @Test
    fun `인원은 명시값 우선 없으면 발화자 수`() {
        assertEquals("5명", PromptSieve.extractHeadcount("우리 5명이야", emptyList()))
        val msgs = listOf(msg("A", "안녕"), msg("B", "하이"), msg("A", "또 나"))
        assertEquals("2명 (대화 참여자 기준)", PromptSieve.extractHeadcount("언제 볼까", msgs))
        // 혼자면 인원 정보로 쓰지 않는다
        assertNull(PromptSieve.extractHeadcount("혼잣말", listOf(msg("A", "음"))))
    }

    @Test
    fun `선호와 불호를 접두사로 분리한다`() {
        val prefs = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집", "선호: 이탈리안", "그냥 메모")
        assertEquals(listOf("조용한 곳", "이탈리안"), PromptSieve.likes(prefs))
        assertEquals(listOf("시끄러운 술집"), PromptSieve.dislikes(prefs))
    }

    // ── 프레임 렌더 ──────────────────────────────────────────────────────────

    private fun sieveSample() = PromptSieve.sieve(
        messages = listOf(
            msg("양예찬", "이번 주 토요일에 홍대에서 저녁 먹을까? 우리 4명"),
            msg("차민영", "좋아 조용한 데로 가자 1인 3만원 정도면 좋겠어"),
        ),
        safeSummary = "이번 주 토요일 서울 서북부에서 식사 모임을 진행할 예정입니다.",
        userStatus = status,
        chatDate = chatDate,
        ahp = ahp(MeetingContext.MEAL),
    )

    @Test
    fun `채팅을 고정 스키마 블록으로 정형화한다`() {
        val s = sieveSample()
        assertEquals(MeetingContext.MEAL, s.context)
        assertEquals("2026-09-19 (토)", s.slot(FrameSlot.WHEN))
        assertEquals("홍대", s.slot(FrameSlot.WHERE))
        assertEquals("4명", s.slot(FrameSlot.HEADCOUNT))
        assertEquals("1인 3만원 내외", s.slot(FrameSlot.BUDGET))
        assertEquals("조용한 곳", s.slot(FrameSlot.PREFERENCES))
        assertEquals("시끄러운 술집", s.slot(FrameSlot.CONSTRAINTS))
        assertEquals("홍대 식당 맛집", s.placeQuery)
    }

    @Test
    fun `슬롯 키는 값이 없어도 사라지지 않는다 - 블록 형태가 항상 같다`() {
        val sparse = PromptSieve.sieve(
            messages = listOf(msg("A", "우리 언제 한번 보자")),
            safeSummary = "모임 일정을 조율 중입니다.",
            userStatus = null,
            chatDate = chatDate,
            ahp = ahp(MeetingContext.GENERIC),
        )
        FrameSlot.entries.forEach {
            assertTrue("슬롯 키 누락: ${it.label}", sparse.frame.contains("${it.label}:"))
        }
        assertTrue(sparse.frame.contains(SievedPrompt.UNSPECIFIED))
    }

    @Test
    fun `못 채운 슬롯은 미확정으로 명시한다 - 모델이 지어내지 못하게`() {
        val sparse = PromptSieve.sieve(
            messages = listOf(msg("A", "우리 언제 한번 보자")),
            safeSummary = "모임 일정을 조율 중입니다.",
            userStatus = null,
            chatDate = chatDate,
            ahp = ahp(MeetingContext.GENERIC),
        )
        assertTrue(sparse.missing.contains(FrameSlot.WHEN))
        assertTrue(sparse.missing.contains(FrameSlot.WHERE))
        assertTrue(sparse.frame.contains("미확정 슬롯"))
        assertTrue(sparse.frame.contains("지어내지 말 것"))
    }

    @Test
    fun `프레임에 채팅 원문이 실리지 않는다`() {
        val s = sieveSample()
        assertFalse("발신자명 유출", s.frame.contains("양예찬"))
        assertFalse("발신자명 유출", s.frame.contains("차민영"))
        assertFalse("원문 문장 유출", s.frame.contains("먹을까"))
    }

    @Test
    fun `판정 근거 어휘는 블록에 실리지 않는다`() {
        // D2: 근거는 사전 어휘라 이름은 실릴 수 없지만 "잘렸" 같은 민감한 사정이 드러난다.
        // 추천에 필요한 건 상황 라벨이므로 근거는 디버그 패널에만 남긴다.
        val s = PromptSieve.sieve(
            messages = listOf(msg("A", "친구가 오늘 회사에서 잘렸대 많이 속상해하더라")),
            safeSummary = "오늘 저녁 지인을 위로하는 모임을 진행할 예정입니다.",
            userStatus = status,
            chatDate = chatDate,
            ahp = ahp(MeetingContext.CONSOLATION),
        )
        assertFalse("판정 근거 줄이 남아 있음", s.frame.contains("상황 판정 근거"))
        assertFalse("민감한 근거 어휘 유출", s.frame.contains("잘렸"))
        // 근거 자체는 결과 객체에 남아 디버그 패널이 쓸 수 있어야 한다
        assertTrue("분류 근거가 사라짐", s.classification.signals.isNotEmpty())
        // 슬롯 9개 키는 그대로
        FrameSlot.entries.forEach { assertTrue("슬롯 누락: ${it.label}", s.frame.contains("${it.label}:")) }
    }

    @Test
    fun `프레임에 기준 가중치와 일관성 비율이 동봉된다`() {
        val s = sieveSample()
        assertTrue(s.frame.contains("판단 기준 가중치"))
        assertTrue(s.frame.contains("CR="))
        AhpCriterion.entries.forEach {
            assertTrue("기준 누락: ${it.label}", s.frame.contains(it.label))
        }
    }

    @Test
    fun `상황이 다르면 장소 검색어 힌트가 달라진다`() {
        val drink = PromptSieve.sieve(
            messages = listOf(msg("A", "홍대에서 술 한잔 하자 이자카야 어때")),
            safeSummary = "술자리를 계획 중입니다.",
            userStatus = null,
            chatDate = chatDate,
            ahp = ahp(MeetingContext.DRINK),
        )
        assertEquals(MeetingContext.DRINK, drink.context)
        assertTrue(drink.placeQuery.contains("술집"))
        assertFalse(drink.placeQuery.contains("맛집"))
    }

    /**
     * Q2 (2026-09-23) — 요약문에 상대 날짜가 남아 모델이 다시 해석할 여지가 있던 문제.
     * 슬롯은 절대 날짜로 환산해 두는데 요약문은 Gemma가 쓴 자연어라 "이번 주 토요일"이 남는다.
     * 우선순위를 블록에 못박아 둔다.
     */
    @Test
    fun `요약문과 슬롯이 어긋날 때 슬롯이 우선한다는 규칙이 블록에 있다`() {
        val messages = listOf(msg("가", "이번 주 토요일에 홍대에서 저녁 먹자 4명"))
        val c = ContextClassifier.classify(messages)
        val s = PromptSieve.sieve(
            messages,
            // 일부러 상대 표현과 일반화된 지역을 남긴 요약문
            "이번 주 토요일 서울 서북부에서 식사 모임을 진행할 예정입니다.",
            null, LocalDate.of(2026, 9, 17), AhpEngine.solve(c.context.basePairwiseMatrix()), c,
        )
        assertTrue("슬롯 우선 규칙이 없다", s.frame.contains(PromptSieve.SLOT_PRECEDENCE_RULE))
        // 규칙은 요약문 **뒤**에 와야 한다 — 앞에 있으면 무엇에 대한 규칙인지 모호하다
        assertTrue(
            "규칙이 요약문보다 앞에 있다",
            s.frame.indexOf(PromptSieve.SLOT_PRECEDENCE_RULE) > s.frame.indexOf("[대화 요약"),
        )
        // 슬롯 자체는 여전히 절대 날짜여야 한다 (규칙만 넣고 환산이 깨지면 의미 없다)
        assertEquals("2026-09-19 (토)", s.slot(FrameSlot.WHEN))
    }

    /**
     * "다음 주 X요일"이 목표 요일이 기준일보다 **주 앞쪽**일 때 한 주 밀리던 버그.
     * 2026-09-23 평가(`SieveBulkEvalTest`, syn-06)에서 잡혔다.
     * 틀린 날짜는 그대로 날씨 조회 같은 툴 호출로 전파되므로 조용히 넘어가면 안 된다.
     */
    @Test
    fun `다음 주 요일은 다음 ISO 주의 그 요일이다`() {
        // 2026-09-17은 목요일 — 수요일은 '주 앞쪽'이라 예전 구현이 한 주를 더 밀었다
        val thu = LocalDate.of(2026, 9, 17)
        assertEquals("2026-09-23 (수)", whenSlotOf("다음 주 수요일 저녁에 보자", thu))

        // 목표가 '주 뒤쪽'인 경우는 원래도 맞았다 — 회귀 확인
        val tue = LocalDate.of(2026, 9, 22)
        assertEquals("2026-10-03 (토)", whenSlotOf("다음 주 토요일에 보자", tue))

        // 기준일과 같은 요일 → 다음 주 같은 요일
        assertEquals("2026-09-24 (목)", whenSlotOf("다음 주 목요일에 보자", thu))

        // "이번 주"는 영향받지 않는다
        assertEquals("2026-09-19 (토)", whenSlotOf("이번 주 토요일에 보자", thu))
    }

    private fun whenSlotOf(text: String, chatDate: LocalDate): String {
        val messages = listOf(msg("가", text))
        val c = ContextClassifier.classify(messages)
        return PromptSieve.sieve(
            messages, "", null, chatDate,
            AhpEngine.solve(c.context.basePairwiseMatrix()), c,
        ).slot(FrameSlot.WHEN)
    }

    /**
     * 대화 중 값이 바뀌면 **마지막 언급이 이긴다** (2026-09-23).
     * 예전에는 대화 전체를 한 문자열로 합쳐 첫 일치를 잡아, 뒤집힌 제안을 그대로 보냈다.
     */
    @Test
    fun `대화 중 변경되면 마지막 언급을 쓴다`() {
        val chatDate = LocalDate.of(2026, 9, 22)   // 화요일
        val messages = listOf(
            msg("가", "토요일 저녁 홍대에서 볼까"),
            msg("나", "홍대는 주말에 너무 붐벼"),
            msg("다", "망원동은 어때"),
            msg("가", "일요일로 바꾸자"),
            msg("나", "일요일 6시 망원동 좋다"),
        )
        val s = sieveOf(messages, chatDate)
        assertEquals("2026-09-27 (일)", s.slot(FrameSlot.WHEN))
        assertEquals("망원", s.slot(FrameSlot.WHERE))
    }

    /**
     * 마지막 언급 우선의 부작용 — 뒤의 언급이 앞보다 **덜 구체적**이면 한정이 사라진다.
     * "다음 주"가 한 번 잡혔으면 뒤의 맨 요일도 그 주 안에서 읽는다.
     * (`SieveBulkEvalTest` syn-08·syn-13에서 실제로 걸렸다)
     */
    @Test
    fun `다음 주 한정은 뒤의 맨 요일 언급에도 이어진다`() {
        val chatDate = LocalDate.of(2026, 9, 22)   // 화요일
        val kept = sieveOf(
            listOf(
                msg("가", "다음 주 토요일 저녁 어때"),
                msg("나", "그럼 일요일은?"),
                msg("가", "일요일 6시로 하자"),
            ),
            chatDate,
        )
        // 다음 주 일요일 = 2026-10-04 (이번 주 일요일 09-27이 아니다)
        assertEquals("2026-10-04 (일)", kept.slot(FrameSlot.WHEN))

        // "다음 주"가 없으면 가장 가까운 일요일
        val plain = sieveOf(listOf(msg("가", "일요일 6시로 하자")), chatDate)
        assertEquals("2026-09-27 (일)", plain.slot(FrameSlot.WHEN))

        // 뒤에 "이번 주"로 **명시적으로** 뒤집으면 그것이 이긴다 — 한정을 물려받지 않는다
        val overridden = sieveOf(
            listOf(
                msg("가", "다음 주 토요일 저녁 어때"),
                msg("나", "이번 주 일요일이 낫겠다"),
            ),
            chatDate,
        )
        assertEquals("2026-09-27 (일)", overridden.slot(FrameSlot.WHEN))
    }

    private fun sieveOf(messages: List<Message>, chatDate: LocalDate): SievedPrompt {
        val c = ContextClassifier.classify(messages)
        return PromptSieve.sieve(
            messages, "", null, chatDate,
            AhpEngine.solve(c.context.basePairwiseMatrix()), c,
        )
    }

    /**
     * **당일이 그 요일일 때** 세 표현이 갈린다 (2026-09-23 팀 결정).
     * 오늘을 뜻했다면 "오늘"이라고 했을 것이므로, 맨 요일은 다음 주로 읽는다.
     */
    @Test
    fun `당일과 같은 요일을 말하면 맨 요일은 다음 주다`() {
        val sat = LocalDate.of(2026, 9, 19)   // 토요일

        // 맨 요일 → 다음 주 토요일
        assertEquals("2026-09-26 (토)", whenSlotOf("토요일 11시에 보자", sat))

        // "이번 주"라고 명시하면 오늘 — 화자가 이번 주라고 했다
        assertEquals("2026-09-19 (토)", whenSlotOf("이번 주 토요일 11시에 보자", sat))

        // "오늘"은 그대로 오늘
        assertEquals("2026-09-19 (토)", whenSlotOf("오늘 11시에 보자", sat))

        // "다음 주"는 다음 ISO 주
        assertEquals("2026-09-26 (토)", whenSlotOf("다음 주 토요일에 보자", sat))

        // 당일이 아닌 요일은 영향 없다 — 토요일에 "일요일"은 내일
        assertEquals("2026-09-20 (일)", whenSlotOf("일요일에 보자", sat))
    }

    /**
     * 지역 슬롯이 **문장이 말하는 곳**을 잡아야 한다.
     *
     * 두 가지가 겹쳐 있었다 (2026-09-23 발견):
     *  1. `KNOWN_AREAS.firstOrNull { text.contains(it) }` — **사전 순서**로 찾는다.
     *     "홍대"가 목록 0번이라 `"홍대 말고 망원동"`도 `"망원동 말고 홍대"`도 전부 홍대를 준다.
     *  2. 부정 표현을 전혀 보지 않는다. `ContextClassifier`에는 `isNegated`가 있는데
     *     슬롯 추출은 그걸 쓰지 않았다.
     */
    @Test
    fun `부정된 지역은 슬롯에 들어가지 않는다`() {
        val d = LocalDate.of(2026, 9, 22)
        assertEquals("망원", sieveOf(listOf(msg("가", "홍대 말고 망원동으로 가자")), d).slot(FrameSlot.WHERE))
        assertEquals("홍대", sieveOf(listOf(msg("가", "망원동 말고 홍대로 가자")), d).slot(FrameSlot.WHERE))
        assertEquals("성수", sieveOf(listOf(msg("가", "강남은 빼고 성수에서 보자")), d).slot(FrameSlot.WHERE))
    }

    /** 부정이 없으면 **문장에 늦게 나온 쪽**을 쓴다 — 마지막 언급 우선과 같은 규칙. */
    @Test
    fun `한 문장에 지역이 둘이면 뒤에 나온 것을 쓴다`() {
        val d = LocalDate.of(2026, 9, 22)
        assertEquals("망원", sieveOf(listOf(msg("가", "홍대나 망원동 중에 고르자")), d).slot(FrameSlot.WHERE))
        assertEquals("홍대", sieveOf(listOf(msg("가", "망원동이나 홍대 중에 고르자")), d).slot(FrameSlot.WHERE))
    }

    /**
     * 시각만 남으면 **아침 7시인지 저녁 7시인지 모르는 값**이 클라우드로 나간다.
     * 식당 추천이 완전히 달라지는 차이다.
     * 2026-09-23 측정에서 시간대 표현이 9건 전부 소실됐다(`SieveBulkEvalTest`).
     */
    @Test
    fun `시간대 표현은 시각만 말한 뒤에도 유지된다`() {
        val d = LocalDate.of(2026, 9, 22)

        // "금요일 저녁" → (합의) → "금요일 7시" : 저녁이 살아 있어야 한다
        val kept = sieveOf(
            listOf(
                msg("가", "금요일 저녁에 볼까"),
                msg("나", "좋아"),
                msg("가", "금요일 7시로 하자"),
            ),
            d,
        )
        assertEquals("저녁 7시", kept.slot(FrameSlot.TIME_OF_DAY))

        // 시간대 언급이 없으면 시각만 — 없는 정보를 지어내지 않는다
        val plain = sieveOf(listOf(msg("가", "금요일 7시로 하자")), d)
        assertEquals("7시", plain.slot(FrameSlot.TIME_OF_DAY))

        // 문장이 직접 말하면 그쪽이 이긴다 — 물려받지 않는다
        val explicit = sieveOf(
            listOf(
                msg("가", "점심에 볼까"),
                msg("나", "점심 말고 저녁 7시가 낫겠다"),
            ),
            d,
        )
        assertEquals("저녁 7시", explicit.slot(FrameSlot.TIME_OF_DAY))

        // 시간대 표현만 있고 시각이 없으면 그대로
        val markerOnly = sieveOf(listOf(msg("가", "금요일 저녁에 보자")), d)
        assertEquals("저녁", markerOnly.slot(FrameSlot.TIME_OF_DAY))
    }
}
