package com.example.gemma4.service

import android.util.Log
import com.example.gemma4.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object WeatherService {

    private val apiKey get() = BuildConfig.WEATHER_API_KEY

    // 중기 육상예보 구역코드
    private val landRegionMap = mapOf(
        "서울" to "11B00000", "마포" to "11B00000", "강남" to "11B00000",
        "홍대" to "11B00000", "종로" to "11B00000", "여의도" to "11B00000",
        "인천" to "11B00000", "경기" to "11B00000", "수원" to "11B00000",
        "성남" to "11B00000", "고양" to "11B00000", "용인" to "11B00000",
        "부산" to "11H20000", "대구" to "11H10000",
        "광주" to "11F20000", "대전" to "11C20000", "울산" to "11H20000",
        "세종" to "11C20000", "강릉" to "11D20000", "전주" to "11F10000",
        "청주" to "11C10000", "춘천" to "11D10000", "제주" to "11G00000",
        "창원" to "11H20000"
    )

    // 중기 기온예보 구역코드
    private val taRegionMap = mapOf(
        "서울" to "11B10101", "마포" to "11B10101", "강남" to "11B10101",
        "홍대" to "11B10101", "종로" to "11B10101", "여의도" to "11B10101",
        "인천" to "11B20601", "경기" to "11B20601", "수원" to "11B10305",
        "성남" to "11B10302", "고양" to "11B10101", "용인" to "11B10314",
        "부산" to "11H20201", "대구" to "11H10201",
        "광주" to "11F20501", "대전" to "11C20401", "울산" to "11H20101",
        "세종" to "11C20404", "강릉" to "11D20501", "전주" to "11F10201",
        "청주" to "11C10301", "춘천" to "11D10301", "제주" to "11G00201",
        "창원" to "11H20301"
    )

    private fun findLandRegion(city: String): String =
        landRegionMap.entries.firstOrNull { city.contains(it.key) }?.value ?: "11B00000"

    private fun findTaRegion(city: String): String =
        taRegionMap.entries.firstOrNull { city.contains(it.key) }?.value ?: "11B10101"

    // 중기예보 발표시각 (하루 2회: 06시, 18시)
    private fun getTmFc(): String {
        val now = LocalDateTime.now()
        val baseHour = if (now.hour >= 18) 18 else 6
        return now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) +
            String.format("%02d00", baseHour)
    }

    suspend fun getWeather(city: String, date: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "여기에_API_키_입력") {
            return@withContext "날씨 API 키가 설정되지 않았습니다"
        }
        if (date == "미정" || !date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return@withContext "모임 날짜가 확정되지 않아 날씨를 가져올 수 없습니다"
        }

        val targetDate = try { LocalDate.parse(date) } catch (e: Exception) {
            return@withContext "날짜 형식 오류: $date"
        }
        val dayDiff = ChronoUnit.DAYS.between(LocalDate.now(), targetDate).toInt()

        when {
            dayDiff < 0  -> return@withContext "이미 지난 날짜입니다"
            dayDiff < 3  -> return@withContext "$date 날씨: 중기예보는 3일 이후부터 제공됩니다"
            dayDiff > 10 -> return@withContext "$date 는 중기예보 범위(최대 10일)를 초과합니다"
        }

        try {
            val encodedKey = java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
            val tmFc = getTmFc()

            // ── 육상 중기예보 (날씨상태, 강수확률) ──────────────────────────────
            val landRegId = findLandRegion(city)
            val landUrl = "https://apis.data.go.kr/1360000/MidFcstInfoService/getMidLandFcst" +
                "?serviceKey=$encodedKey&numOfRows=10&pageNo=1&dataType=JSON" +
                "&regId=$landRegId&tmFc=$tmFc"
            Log.d("WeatherService", "LandURL: $landUrl")

            val landConn = (java.net.URL(landUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val landCode = landConn.responseCode
            Log.d("WeatherService", "HTTP: $landCode")

            if (landCode != 200) {
                val body = landConn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.d("WeatherService", "Error body: $body")
                return@withContext "HTTP $landCode 오류: ${body.take(200)}"
            }

            val landText = landConn.inputStream.bufferedReader().readText()
            if (landText.trimStart().startsWith("<")) return@withContext "날씨 API 인증 오류"

            val landItem = JSONObject(landText)
                .getJSONObject("response").getJSONObject("body")
                .getJSONObject("items").getJSONArray("item").getJSONObject(0)

            val wfAm   = landItem.optString("wf${dayDiff}Am", "")
            val wfPm   = landItem.optString("wf${dayDiff}Pm", "")
            val rnStAm = landItem.optString("rnSt${dayDiff}Am", "-")
            val rnStPm = landItem.optString("rnSt${dayDiff}Pm", "-")
            val sky    = wfPm.ifBlank { wfAm }

            // ── 중기 기온예보 (최저/최고기온) ────────────────────────────────────
            val taRegId = findTaRegion(city)
            val taUrl = "https://apis.data.go.kr/1360000/MidFcstInfoService/getMidTaFcst" +
                "?serviceKey=$encodedKey&numOfRows=10&pageNo=1&dataType=JSON" +
                "&regId=$taRegId&tmFc=$tmFc"

            val taConn = (java.net.URL(taUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val (taMin, taMax) = if (taConn.responseCode == 200) {
                val taText = taConn.inputStream.bufferedReader().readText()
                if (!taText.trimStart().startsWith("<")) {
                    val taItem = JSONObject(taText)
                        .getJSONObject("response").getJSONObject("body")
                        .getJSONObject("items").getJSONArray("item").getJSONObject(0)
                    taItem.optString("taMin$dayDiff", "-") to taItem.optString("taMax$dayDiff", "-")
                } else "-" to "-"
            } else "-" to "-"

            "$sky | 최저 ${taMin}도 / 최고 ${taMax}도 | 강수확률 오전 ${rnStAm}% / 오후 ${rnStPm}%"
        } catch (e: Exception) {
            "날씨 정보를 가져오는 중 오류: ${e.message}"
        }
    }
}
