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

/**
 * @param searchPlaces 장소 검색 함수. 기본값은 카카오 로컬(프록시 경유). 테스트에서 주입해 교체한다.
 */
class GuardrailService(
    private val searchPlaces: suspend (String) -> PlaceSearchResult = { KakaoLocalService.searchForVerification(it) },
) {

    companion object {
        private const val TAG = "GuardrailService"
        private const val MAX_CANDIDATES = 5
    }

    /**
     * 추천된 장소명을 검색 결과와 대조해 실존 여부를 검증한다.
     * 이름이 일치하고 모임과 같은 시·도인 결과가 있을 때만 OPEN ([PlaceMatcher] 참조).
     * @param placeNames  Gemini 응답 JSON에서 파싱된 추천 장소명 목록 (구조화 데이터)
     * @param city        모임 도시 — 시·도 단위 지역 검사에 쓴다. "미정"·모르는 지역이면 검사 생략
     */
    suspend fun verify(placeNames: List<String>, city: String): GuardrailResult {
        val candidates = placeNames
            .map { it.trim() }
            .filter { it.length in 2..25 }
            .distinct()
            .take(MAX_CANDIDATES)
        Log.d(TAG, "장소 후보 ${candidates.size}건 (city=$city): $candidates")

        if (candidates.isEmpty()) {
            return GuardrailResult(passed = true, verifiedPlaces = emptyList(), feedbackForRetry = "")
        }

        val outcomes: List<Pair<String, PlaceMatcher.Outcome?>> = coroutineScope {
            candidates.map { name -> async { name to check(name, city) } }.awaitAll()
        }

        val verified = outcomes.map { (name, outcome) ->
            PlaceVerification(
                name = name,
                status = when (outcome) {
                    PlaceMatcher.Outcome.MATCHED -> PlaceStatus.OPEN
                    PlaceMatcher.Outcome.NOT_FOUND, PlaceMatcher.Outcome.OUT_OF_REGION -> PlaceStatus.CLOSED
                    null -> PlaceStatus.UNKNOWN
                },
            )
        }

        // CLOSED(존재하지 않음·모임 지역 밖)만 재시도 대상. UNKNOWN(검증 불가)은 재시도해도
        // 해결되지 않으므로 통과시키되, 상태를 보존해 UI가 "검증 불가"로 정직하게 표기한다.
        val notFound = outcomes.filter { it.second == PlaceMatcher.Outcome.NOT_FOUND }.map { it.first }
        val outOfRegion = outcomes.filter { it.second == PlaceMatcher.Outcome.OUT_OF_REGION }.map { it.first }
        val unknownCount = verified.count { it.status == PlaceStatus.UNKNOWN }
        val passed = notFound.isEmpty() && outOfRegion.isEmpty()
        val feedback = if (passed) "" else buildFeedback(notFound, outOfRegion, city)

        Log.d(
            TAG,
            "검증 완료 — passed=$passed notFound=${notFound.size}건 outOfRegion=${outOfRegion.size}건 unknown=${unknownCount}건",
        )
        return GuardrailResult(passed = passed, verifiedPlaces = verified, feedbackForRetry = feedback)
    }

    /** 검색 + 매칭. 검색 오류(프록시 장애·예외)는 null = 검증 불가. */
    private suspend fun check(name: String, city: String): PlaceMatcher.Outcome? {
        val result = try {
            searchPlaces(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "검색 예외 name=$name — 검증 불가(UNKNOWN)", e)
            return null
        }
        return when (result) {
            is PlaceSearchResult.Failed -> {
                Log.w(TAG, "검색 실패 name=$name reason=${result.reason} — 검증 불가(UNKNOWN)")
                null
            }
            is PlaceSearchResult.Found -> PlaceMatcher.evaluate(name, result.places, city)
        }
    }

    private fun buildFeedback(notFound: List<String>, outOfRegion: List<String>, city: String): String =
        buildList {
            if (notFound.isNotEmpty()) {
                add(
                    "다음 장소는 지도 검색에서 같은 이름의 가게를 찾을 수 없어 존재하지 않는 것으로 판단됩니다: " +
                        "${notFound.joinToString(", ")}. 지어낸 이름이 아닌 실제 상호명으로만 추천하세요."
                )
            }
            if (outOfRegion.isNotEmpty()) {
                add(
                    "다음 장소는 실제로 있지만 모임 지역(${city.trim()})이 아닌 다른 시·도에 있습니다: " +
                        "${outOfRegion.joinToString(", ")}. 모임 지역 안의 장소로 바꾸세요."
                )
            }
            add("위 장소들을 반드시 제외하고 다시 추천하세요.")
        }.joinToString(" ")
}
