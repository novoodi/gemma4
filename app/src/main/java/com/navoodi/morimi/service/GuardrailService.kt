package com.navoodi.morimi.service

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        /**
         * 동시 검색 상한. 예전에는 후보를 5건으로 잘라 호출 수를 묶었지만, 그러면 6번째 이후
         * 장소가 검증 없이 통과했다. 이제 전부 검증하는 대신 한 번에 나가는 프록시 호출을 이 수로
         * 제한한다 — 모델이 장소를 수십 곳 내도 kakaoSearch 동시 요청이 폭주하지 않게 하고
         * (카카오 쿼터·Functions 동시 실행), 호출 수 자체는 같은 장소명 중복 검색 제거로 줄인다.
         */
        internal const val MAX_CONCURRENT_SEARCHES = 4

        /**
         * 이보다 긴 장소명은 검색하지 않고 존재하지 않음으로 본다. 실제 상호명은 이 길이에
         * 한참 못 미치고, kakaoSearch 프록시는 200자를 넘는 검색어를 오류로 거절하는데
         * 오류는 UNKNOWN(통과)이 되므로 — 아주 긴 지어낸 이름이 장애 경로로 새어 나가지 않게 막는다.
         */
        internal const val MAX_PLACE_NAME_LEN = 60

        internal const val EMPTY_FEEDBACK =
            "추천 장소가 한 곳도 없습니다. 실제로 존재하는 장소를 1곳 이상 recommendedPlaces에 상호명으로 추천하세요."
    }

    /**
     * 추천된 장소명을 검색 결과와 대조해 실존 여부를 검증한다.
     * 이름이 일치하고 모임과 같은 시·도인 결과가 있을 때만 OPEN ([PlaceMatcher] 참조).
     * 추천된 장소는 개수·길이와 무관하게 전부 검증하고, 0곳이면 실패로 본다.
     * @param placeNames  Gemini 응답 JSON에서 파싱된 추천 장소명 목록 (구조화 데이터)
     * @param city        모임 도시 — 시·도 단위 지역 검사에 쓴다. "미정"·모르는 지역이면 검사 생략
     */
    suspend fun verify(placeNames: List<String>, city: String): GuardrailResult {
        // 길이·개수로 거르지 않는다 — 걸러진 장소는 검증 없이 UNVERIFIED로 통과했기 때문이다.
        // 빈 문자열만 버리고, 같은 이름은 한 번만 검증한다.
        val candidates = placeNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        Log.d(TAG, "장소 후보 ${candidates.size}건 (city=$city): $candidates")

        // 추천이 0곳이면 검증할 것이 없는 게 아니라 추천에 실패한 것이다.
        if (candidates.isEmpty()) {
            Log.w(TAG, "추천 장소 0곳 — 실패 처리")
            return GuardrailResult(passed = false, verifiedPlaces = emptyList(), feedbackForRetry = EMPTY_FEEDBACK)
        }

        // "미미식당 - 분위기 좋음"처럼 이유가 붙어 와도 장소명만 떼서 검색한다.
        // PlaceVerification.name은 원래 문자열 그대로 둔다 — 호출자가 그 이름으로 결과를 다시 찾는다.
        val queryOf = candidates.associateWith { PlaceMatcher.placeNameOf(it) }
        val gate = Semaphore(MAX_CONCURRENT_SEARCHES)
        val outcomeByQuery: Map<String, PlaceMatcher.Outcome?> = coroutineScope {
            queryOf.values.distinct()
                .map { q -> async { q to gate.withPermit { check(q, city) } } }
                .awaitAll()
                .toMap()
        }
        val outcomes = candidates.map { it to outcomeByQuery.getValue(queryOf.getValue(it)) }

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
        fun namesWith(o: PlaceMatcher.Outcome) =
            outcomes.filter { it.second == o }.map { queryOf.getValue(it.first) }.distinct()
        val notFound = namesWith(PlaceMatcher.Outcome.NOT_FOUND)
        val outOfRegion = namesWith(PlaceMatcher.Outcome.OUT_OF_REGION)
        val unknownCount = verified.count { it.status == PlaceStatus.UNKNOWN }
        val passed = notFound.isEmpty() && outOfRegion.isEmpty()
        val feedback = if (passed) "" else buildFeedback(notFound, outOfRegion, city)

        Log.d(
            TAG,
            "검증 완료 — passed=$passed 검색 ${outcomeByQuery.size}회 notFound=${notFound.size}건 " +
                "outOfRegion=${outOfRegion.size}건 unknown=${unknownCount}건",
        )
        return GuardrailResult(passed = passed, verifiedPlaces = verified, feedbackForRetry = feedback)
    }

    /** 검색 + 매칭. 검색 오류(프록시 장애·예외)는 null = 검증 불가. */
    private suspend fun check(name: String, city: String): PlaceMatcher.Outcome? {
        if (name.length > MAX_PLACE_NAME_LEN) {
            Log.w(TAG, "장소명 ${name.length}자 — 실제 상호명으로 볼 수 없어 존재하지 않음 처리")
            return PlaceMatcher.Outcome.NOT_FOUND
        }
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
