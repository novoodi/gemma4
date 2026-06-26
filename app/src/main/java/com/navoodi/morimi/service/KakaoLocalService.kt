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

    suspend fun placeExists(name: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "KAKAO_REST_API_KEY 미설정 — 보수적으로 true 반환")
            return@withContext true
        }
        try {
            val url = "$ENDPOINT?query=${URLEncoder.encode(name, "UTF-8")}&size=1"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "KakaoAK ${apiKey.trim()}")
            }
            if (conn.responseCode != 200) return@withContext true
            val text = conn.inputStream.bufferedReader().readText()
            JSONObject(text).getJSONObject("meta").optInt("total_count", 0) > 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "placeExists 오류 name=$name", e)
            true
        }
    }
}
