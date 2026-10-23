package com.navoodi.morimi.ui.screen.aireport

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingDateDisplayTest {
    @Test
    fun `ISO 날짜와 한국어 날짜를 월 일 요일로 표시한다`() {
        assertEquals("10월 24일(토)", MeetingDateDisplay.compact("2026-10-24"))
        assertEquals("10월 13일(화) 저녁 모임", MeetingDateDisplay.compact("2026-10-13(화) 저녁 모임"))
        assertEquals("10월 19일(월) 새벽", MeetingDateDisplay.compact("2026년 10월 19일 월요일 새벽"))
    }

    @Test
    fun `날짜가 아니거나 유효하지 않으면 원문을 보존한다`() {
        assertEquals("미정", MeetingDateDisplay.compact("미정"))
        assertEquals("2026-02-30 날씨", MeetingDateDisplay.compact("2026-02-30 날씨"))
    }
}
