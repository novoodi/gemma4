package com.navoodi.morimi.data.model

data class RecommendedPlace(
    val name: String,
    val address: String = "",
    val reason: String = "",    // Gemini 추천 이유 (장소명 뒤 — 이하)
    val placeUrl: String = ""   // 카카오맵 상세 링크
)
