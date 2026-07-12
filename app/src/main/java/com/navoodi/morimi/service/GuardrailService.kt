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
        private const val MAX_CANDIDATES = 5
    }

    /**
     * 추천된 장소명을 카카오 로컬 API로 실존 여부를 검증한다.
     * @param placeNames  Gemini 응답 JSON에서 파싱된 추천 장소명 목록 (구조화 데이터)
     * @param city        모임 도시 (로그용)
     */
    suspend fun verify(placeNames: List<String>, city: String): GuardrailResult {
        val candidates = placeNames
            .map { it.trim() }
            .filter { it.length in 2..25 }
            .distinct()
            .take(MAX_CANDIDATES)
        Log.d(TAG, "장소 후보 ${candidates.size}건: $candidates")

        if (candidates.isEmpty()) {
            return GuardrailResult(passed = true, verifiedPlaces = emptyList(), feedbackForRetry = "")
        }

        val verified = coroutineScope {
            candidates.map { name ->
                async {
                    PlaceVerification(name = name, status = KakaoLocalService.checkPlace(name))
                }
            }.awaitAll()
        }

        // CLOSED(실존하지 않음)만 재시도 대상. UNKNOWN(검증 불가)은 재시도해도
        // 해결되지 않으므로 통과시키되, 상태를 보존해 UI가 "검증 불가"로 정직하게 표기한다.
        val closed = verified.filter { it.status == PlaceStatus.CLOSED }
        val unknown = verified.filter { it.status == PlaceStatus.UNKNOWN }
        val passed = closed.isEmpty()
        val feedback = if (!passed) {
            "다음 장소는 현재 영업하지 않거나 존재하지 않습니다: ${closed.joinToString(", ") { it.name }}. " +
            "해당 장소들을 반드시 제외하고, 실제 영업 중인 다른 장소로만 재추천하세요."
        } else ""

        Log.d(TAG, "검증 완료 — passed=$passed closed=${closed.size}건 unknown=${unknown.size}건")
        return GuardrailResult(passed = passed, verifiedPlaces = verified, feedbackForRetry = feedback)
    }
}
