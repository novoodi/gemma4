package com.navoodi.morimi.data.model

/**
 * Guardrail 장소 실존 검증 결과.
 * UNVERIFIED는 "검증 불가"(API 실패·키 미설정) — "검증됨"과 구분해 UI에 정직하게 노출한다.
 */
enum class VerificationStatus { VERIFIED, NOT_FOUND, UNVERIFIED }

data class RecommendedPlace(
    val name: String,
    val address: String = "",
    val reason: String = "",    // Gemini 추천 이유 (장소명 뒤 — 이하)
    val placeUrl: String = "",  // 카카오맵 상세 링크
    val verification: VerificationStatus = VerificationStatus.UNVERIFIED
)
