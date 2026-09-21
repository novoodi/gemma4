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
     * 장소 실존 여부를 3-상태로 반환한다.
     * 정상 응답이면 OPEN(검색됨)/CLOSED(미검색), 프록시·업스트림 오류·예외는 UNKNOWN(검증 불가).
     *
     * 과거에는 검증 불가 상황에서 true(OPEN)를 반환하는 fail-open이었으나,
     * 이는 "검증하지 못한 것"을 "검증됨"으로 위장해 Guardrail 신뢰성을 훼손했다.
     * 이제 검증 불가를 UNKNOWN으로 정직하게 노출한다.
     */
    suspend fun checkPlace(name: String): PlaceStatus {
        if (name.isBlank()) return PlaceStatus.UNKNOWN
        return try {
            val count = search(name, size = 1).getJSONObject("meta").optInt("total_count", 0)
            if (count > 0) PlaceStatus.OPEN else PlaceStatus.CLOSED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "checkPlace 오류 name=$name — 검증 불가(UNKNOWN)", e)
            PlaceStatus.UNKNOWN
        }
    }
}
