package com.navoodi.morimi.service

import android.util.Log
import org.json.JSONObject

data class KakaoPlace(
    val name: String,
    val category: String,
    val phone: String,
    val address: String,
    val roadAddress: String,
    val url: String,
)

/**
 * 카카오 로컬 키워드 검색 — 서버 프록시(kakaoSearch) 경유.
 * 카카오 REST 키는 앱에 없다([CloudProxy] 참조). 프록시는 카카오 응답 JSON을 그대로 돌려준다.
 */
object KakaoLocalService {

    private const val TAG = "KakaoLocalService"
    private const val FN = "kakaoSearch"
    private const val TIMEOUT_SEC = 30L

    private suspend fun search(query: String, size: Int, categoryGroupCode: String? = null): JSONObject {
        val data = JSONObject().put("query", query).put("size", size)
        if (!categoryGroupCode.isNullOrBlank()) data.put("categoryGroupCode", categoryGroupCode)
        return CloudProxy.callJson(FN, data, TIMEOUT_SEC)
    }

    suspend fun searchKeyword(
        query: String,
        size: Int = 5,
        categoryGroupCode: String? = null,
    ): List<KakaoPlace> {
        if (query.isBlank()) return emptyList()
        return try {
            val docs = search(query, size, categoryGroupCode).getJSONArray("documents")
            (0 until docs.length()).map { i ->
                val d = docs.getJSONObject(i)
                KakaoPlace(
                    name = d.optString("place_name"),
                    category = d.optString("category_name"),
                    phone = d.optString("phone"),
                    address = d.optString("address_name"),
                    roadAddress = d.optString("road_address_name"),
                    url = d.optString("place_url"),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "searchKeyword 오류 query=$query", e)
            emptyList()
        }
    }

    /**
     * Guardrail 실존 검증용 검색. "결과 없음"과 "오류"를 구분해 반환한다.
     * - 정상 응답: [PlaceSearchResult.Found] (documents가 비어 있으면 빈 목록 = 결과 없음)
     * - 프록시·업스트림 오류, 응답 형식 이상: [PlaceSearchResult.Failed] (→ UNKNOWN)
     *
     * 실존 판정(이름·지역 일치)은 [PlaceMatcher]가 한다. 과거의 total_count > 0 판정은
     * 지어낸 이름도 비슷한 가게가 검색되면 통과시켰다.
     */
    suspend fun searchForVerification(name: String, size: Int = VERIFY_SIZE): PlaceSearchResult {
        if (name.isBlank()) return PlaceSearchResult.Failed("빈 검색어")
        return try {
            val docs = search(name, size).optJSONArray("documents")
                ?: return PlaceSearchResult.Failed("응답에 documents 없음")
            PlaceSearchResult.Found(
                (0 until docs.length()).mapNotNull { i ->
                    val d = docs.optJSONObject(i) ?: return@mapNotNull null
                    KakaoPlace(
                        name = d.optString("place_name"),
                        category = d.optString("category_name"),
                        phone = d.optString("phone"),
                        address = d.optString("address_name"),
                        roadAddress = d.optString("road_address_name"),
                        url = d.optString("place_url"),
                    )
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "searchForVerification 오류 name=$name — 검증 불가(UNKNOWN)", e)
            PlaceSearchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 검증 시 받는 검색 결과 수 (카카오 키워드 검색 size 상한 15 이내) */
    const val VERIFY_SIZE = 10
}

/** 실존 검증용 검색 결과 — "결과 없음"(빈 Found)과 "오류"(Failed)를 타입으로 구분한다. */
sealed interface PlaceSearchResult {
    data class Found(val places: List<KakaoPlace>) : PlaceSearchResult
    data class Failed(val reason: String) : PlaceSearchResult
}
