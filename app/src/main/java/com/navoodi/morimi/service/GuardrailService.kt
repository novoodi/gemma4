package com.navoodi.morimi.service

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class PlaceStatus { OPEN, CLOSED, UNKNOWN }

data class PlaceVerification(val name: String, val status: PlaceStatus)

data class GuardrailResult(
    val passed: Boolean,
    val verifiedPlaces: List<PlaceVerification>,
    val feedbackForRetry: String
)

class GuardrailService {

    companion object {
        private const val TAG = "GuardrailService"

        // 번호 목록 항목에서 장소명 추출: "1. 장소명 — 설명" → "장소명"
        private val NUMBERED_PLACE = Regex(
            """^\d+\.\s*(.{2,25}?)(?:\s*[—–\-].+)?$""",
            RegexOption.MULTILINE
        )

        // 일반 명사·헤더로 오인될 수 있는 잡음 단어
        private val NOISE_TOKENS = setOf(
            "장소", "추천", "활동", "방문", "이용", "정보", "지역", "주변",
            "메뉴", "음식", "카페", "레스토랑", "식당", "공원", "센터"
        )
    }

    /**
     * 추천 텍스트에서 장소명을 추출하고 카카오 로컬 API로 실존 여부를 검증한다.
     * @param text  AgentOrchestrator가 Gemini로부터 받은 최종 추천 텍스트
     * @param city  모임 도시 (로그용)
     */
    suspend fun verify(text: String, city: String): GuardrailResult {
        val candidates = extractPlaceCandidates(text)
        Log.d(TAG, "장소 후보 ${candidates.size}건: $candidates")

        if (candidates.isEmpty()) {
            return GuardrailResult(passed = true, verifiedPlaces = emptyList(), feedbackForRetry = "")
        }

        val verified = coroutineScope {
            candidates.map { name ->
                async {
                    val exists = KakaoLocalService.placeExists(name)
                    PlaceVerification(name = name, status = if (exists) PlaceStatus.OPEN else PlaceStatus.CLOSED)
                }
            }.awaitAll()
        }

        val closed = verified.filter { it.status == PlaceStatus.CLOSED }
        val passed = closed.isEmpty()
        val feedback = if (!passed) {
            "다음 장소는 현재 영업하지 않거나 존재하지 않습니다: ${closed.joinToString(", ") { it.name }}. " +
            "해당 장소들을 반드시 제외하고, 실제 영업 중인 다른 장소로만 재추천하세요."
        } else ""

        Log.d(TAG, "검증 완료 — passed=$passed closed=${closed.size}건")
        return GuardrailResult(passed = passed, verifiedPlaces = verified, feedbackForRetry = feedback)
    }

    private fun extractPlaceCandidates(text: String): List<String> =
        NUMBERED_PLACE.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { name ->
                name.length in 2..25
                    && name.split(" ").size <= 5
                    && NOISE_TOKENS.none { noise -> name == noise }
            }
            .distinct()
            .take(5)
            .toList()
}
