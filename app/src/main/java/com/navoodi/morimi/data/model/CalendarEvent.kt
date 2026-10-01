package com.navoodi.morimi.data.model

import java.util.UUID

data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String, // YYYY-MM-DD
    val time: String = "", // HH:mm (선택)
    val location: String = "",
    val note: String = "",
    val roomId: String? = null,
    // ── 카드뉴스에서 선택한 장소 정보 (기본값으로 하위 호환) ─────────────
    val placeName: String = "",
    val placeAddress: String = "",
    val placeUrl: String = "",
    // ── 사용자 지정 카테고리 (빈 문자열 = 미분류) ─────────────────────
    val category: String = "",
)
