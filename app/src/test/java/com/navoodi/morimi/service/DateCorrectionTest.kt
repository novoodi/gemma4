package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.Message
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DateCorrectionTest {
    private val date = LocalDate.of(2026, 9, 22)

    private fun resolve(vararg messages: String, summary: String = ""): String = PromptSieve.sieve(
        messages.map { Message(roomId = "r", senderId = "u", senderName = "참가자", content = it) },
        summary, null, date, AhpEngine.solve(MeetingContext.MEAL.basePairwiseMatrix()),
    ).slot(FrameSlot.WHEN)

    @Test
    fun `다음 주를 이번 주로 고친 뒤 맨 요일은 새 주를 따른다`() {
        assertEquals("2026-09-27 (일)", resolve("다음 주 토요일 저녁 어때", "이번 주 일요일이 낫겠다", "일요일 6시로 확정"))
    }

    @Test
    fun `한 문장 안에서도 마지막 비부정 날짜가 이긴다`() {
        assertEquals("2026-09-22 (화)", resolve("내일 말고 오늘 보자"))
        assertEquals("2026-09-27 (일)", resolve("다음 주 토요일 말고 이번 주 일요일"))
        assertEquals("2026-09-23 (수)", resolve("오늘 말고 내일"))
    }

    @Test
    fun `마지막 날짜가 부정이면 앞의 비부정 날짜를 유지한다`() {
        assertEquals("2026-09-22 (화)", resolve("오늘 보자. 내일은 말고"))
        assertNull(PromptSieve.extractDate("내일은 말고", date))
    }

    @Test
    fun `명시적 변경 없으면 다음 주 한정을 유지한다`() {
        assertEquals("2026-10-04 (일)", resolve("다음 주 토요일", "좋아요", "일요일 6시"))
    }

    @Test
    fun `한 문장에서 다음 주의 요일만 바꾸면 주 한정은 유지한다`() {
        assertEquals("2026-10-04 (일)", resolve("다음 주 토요일 말고 일요일"))
        assertEquals("2026-09-27 (일)", resolve("이번 주 토요일 말고 일요일"))
    }

    @Test
    fun `날짜 뒤 장소나 활동의 부정은 날짜를 취소하지 않는다`() {
        assertEquals("2026-09-23 (수)", resolve("내일 술집은 말고 카페로"))
        assertEquals("2026-09-23 (수)", resolve("내일. 술집은 말고"))
        assertEquals("2026-09-24 (목)", resolve("내일은 안 되고 모레로"))
    }

    @Test
    fun `절대 날짜와 오늘 합의는 오래된 다음 주 한정을 해제한다`() {
        assertEquals("2026-09-27 (일)", resolve("다음 주 토요일", "오늘로 변경", "일요일로 확정"))
        assertEquals("2026-09-27 (일)", resolve("다음 주 토요일", "9월 24일", "일요일로 확정"))
    }

    @Test
    fun `낡은 요약의 다음 주 표현은 원문 날짜를 덮어쓰지 않는다`() {
        assertEquals("2026-09-27 (일)", resolve("일요일 6시", summary = "다음 주 토요일에 식사"))
        assertEquals("2026-10-03 (토)", resolve("밥 먹자", summary = "다음 주 토요일에 식사"))
    }

    @Test
    fun `잘못된 날짜는 건너뛰고 유효한 마지막 날짜를 사용한다`() {
        assertEquals("2026-09-22 (화)", resolve("2월 30일 말고 오늘"))
        assertNull(PromptSieve.extractDate("13월 40일", date))
    }

    @Test
    fun `연말 변경도 언급 순서를 따른다`() {
        assertEquals("2027-01-03 (일)", PromptSieve.extractDate("12월 31일 말고 1월 3일", LocalDate.of(2026, 12, 30)))
    }
}
