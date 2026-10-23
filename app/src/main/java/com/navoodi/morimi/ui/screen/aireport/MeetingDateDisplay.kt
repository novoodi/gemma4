package com.navoodi.morimi.ui.screen.aireport

import java.time.DateTimeException
import java.time.LocalDate

/** 저장된 날짜는 그대로 두고 AI 추천 화면에 보이는 날짜만 간결하게 표시한다. */
internal object MeetingDateDisplay {
    private val isoDate = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?:\s*\([월화수목금토일]\))?""")
    private val koreanDate = Regex("""(?<!\d)(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일(?:\s*[월화수목금토일]요일)?""")
    private val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")

    fun compact(text: String): String = koreanDate.replace(isoDate.replace(text, ::replaceDate), ::replaceDate)

    private fun replaceDate(match: MatchResult): String {
        val year = match.groupValues[1].toIntOrNull() ?: return match.value
        val month = match.groupValues[2].toIntOrNull() ?: return match.value
        val day = match.groupValues[3].toIntOrNull() ?: return match.value
        val date = try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return match.value
        }
        return "${date.monthValue}월 ${date.dayOfMonth}일(${weekdays[date.dayOfWeek.value - 1]})"
    }
}
