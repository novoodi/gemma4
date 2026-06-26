package com.navoodi.morimi.data.model

data class MeetingSummary(
    val roomId: String,
    val summary: String = "",
    val location: String = "",
    val meetingDate: String = "",
    val recommendation: String = "",   // 기존 통문자열 유지 (하위 호환)
    val weather: String = "",
    val directions: String = "",
    // ── 구조화 필드 (신규, 기본값으로 하위 호환) ──────────────────────────
    val places: List<RecommendedPlace> = emptyList(),
    val activities: List<String> = emptyList(),
    val itemsToBring: List<String> = emptyList()
)
