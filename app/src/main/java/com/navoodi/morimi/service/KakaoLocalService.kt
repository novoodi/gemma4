package com.navoodi.morimi.service

import android.util.Log
import com.navoodi.morimi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class KakaoPlace(
    val name: String,
    val category: String,
    val phone: String,
    val address: String,
    val roadAddress: String,
    val url: String,
)

object KakaoLocalService {

    private const val TAG = "KakaoLocalService"
    private const val ENDPOINT = "https://dapi.kakao.com/v2/local/search/keyword.json"
    private val apiKey get() = BuildConfig.KAKAO_REST_API_KEY

    suspend fun searchKeyword(
        query: String,
        size: Int = 5,
        categoryGroupCode: String? = null,
    ): List<KakaoPlace> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "KAKAO_REST_API_KEY 미설정 — 빈 결과 반환")
            return@withContext emptyList()
        }
        try {
            val sb = StringBuilder(ENDPOINT)
                .append("?query=").append(URLEncoder.encode(query, "UTF-8"))
                .append("&size=$size")
            if (!categoryGroupCode.isNullOrBlank()) sb.append("&category_group_code=$categoryGroupCode")

            val conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "KakaoAK ${apiKey.trim()}")
            }
            val code = conn.responseCode
            if (code != 200) {
                val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "HTTP $code: ${body.take(200)}")
                return@withContext emptyList()
            }
            val text = conn.inputStream.bufferedReader().readText()
            val docs = JSONObject(text).getJSONArray("documents")
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
     * 정상 응답이면 OPEN(검색됨)/CLOSED(미검색), 키 미설정·HTTP 오류·예외는 UNKNOWN(검증 불가).
     *
     * 과거에는 검증 불가 상황에서 true(OPEN)를 반환하는 fail-open이었으나,
     * 이는 "검증하지 못한 것"을 "검증됨"으로 위장해 Guardrail 신뢰성을 훼손했다.
     * 이제 검증 불가를 UNKNOWN으로 정직하게 노출한다.
     */
    suspend fun checkPlace(name: String): PlaceStatus = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "KAKAO_REST_API_KEY 미설정 — 검증 불가(UNKNOWN)")
            return@withContext PlaceStatus.UNKNOWN
        }
        try {
            val url = "$ENDPOINT?query=${URLEncoder.encode(name, "UTF-8")}&size=1"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "KakaoAK ${apiKey.trim()}")
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "checkPlace HTTP ${conn.responseCode} name=$name — 검증 불가(UNKNOWN)")
                return@withContext PlaceStatus.UNKNOWN
            }
            val text = conn.inputStream.bufferedReader().readText()
            val count = JSONObject(text).getJSONObject("meta").optInt("total_count", 0)
            if (count > 0) PlaceStatus.OPEN else PlaceStatus.CLOSED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "checkPlace 오류 name=$name — 검증 불가(UNKNOWN)", e)
            PlaceStatus.UNKNOWN
        }
    }
}
