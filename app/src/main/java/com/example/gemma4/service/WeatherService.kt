package com.example.gemma4.service

import android.util.Log
import com.example.gemma4.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

object WeatherService {

    private val apiKey get() = BuildConfig.WEATHER_API_KEY

    // 기상청 단기예보 격자 좌표 (nx, ny)
    private val gridMap = mapOf(
        "서울" to (60 to 127), "마포" to (60 to 127), "강남" to (61 to 126),
        "홍대" to (60 to 127), "종로" to (60 to 127), "여의도" to (59 to 126),
        "인천" to (55 to 124), "경기" to (60 to 121), "수원" to (60 to 121),
        "성남" to (62 to 123), "부산" to (98 to 76),  "대구" to (89 to 90),
        "광주" to (58 to 74),  "대전" to (67 to 100), "울산" to (102 to 84),
        "세종" to (66 to 103), "강릉" to (92 to 131), "전주" to (63 to 89),
        "청주" to (69 to 107), "춘천" to (73 to 134), "제주" to (52 to 38),
        "창원" to (89 to 77),  "고양" to (57 to 128), "용인" to (62 to 120)
    )

    private fun findGrid(city: String): Pair<Int, Int> =
        gridMap.entries.firstOrNull { city.contains(it.key) }?.value ?: (60 to 127)

    // 기상청 발표 시각 (매 3시간, 10분 후 제공)
    private fun getBaseDateTime(): Pair<String, String> {
        val now = LocalDateTime.now().minusMinutes(10)
        val validHours = listOf(2, 5, 8, 11, 14, 17, 20, 23)
        val baseHour = validHours.lastOrNull { it <= now.hour } ?: run {
            // 자정 이전이면 전날 23시 기준
            return LocalDate.now().minusDays(1).toString().replace("-", "") to "2300"
        }
        return now.toLocalDate().toString().replace("-", "") to String.format("%02d00", baseHour)
    }

    private val skyMap = mapOf("1" to "맑음", "3" to "구름많음", "4" to "흐림")
    private val ptyMap = mapOf("1" to " (비)", "2" to " (비/눈)", "3" to " (눈)", "4" to " (소나기)")

    suspend fun getWeather(city: String, date: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "여기에_API_키_입력") {
            return@withContext "날씨 API 키가 설정되지 않았습니다"
        }
        if (date == "미정" || !date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return@withContext "모임 날짜가 확정되지 않아 날씨를 가져올 수 없습니다"
        }

        try {
            val (nx, ny) = findGrid(city)
            val fcstDate = date.replace("-", "")
            val (baseDate, baseTime) = getBaseDateTime()

            val encodedKey = java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
            val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
                "?serviceKey=$encodedKey&numOfRows=1000&pageNo=1&dataType=JSON" +
                "&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny"

            Log.d("WeatherService", "URL: $url")

            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val httpCode = conn.responseCode
            Log.d("WeatherService", "HTTP: $httpCode")

            val responseText = if (httpCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.d("WeatherService", "Error body: $body")
                return@withContext "HTTP $httpCode 오류: ${body.take(300)}"
            }

            // 공공데이터포털 인증/서비스 오류 시 XML 반환
            if (responseText.trimStart().startsWith("<")) {
                val reasonCode = Regex("<returnReasonCode>(.*?)</returnReasonCode>")
                    .find(responseText)?.groupValues?.get(1) ?: "?"
                val authMsg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>")
                    .find(responseText)?.groupValues?.get(1) ?: "알 수 없음"
                return@withContext "날씨 API 인증 오류 ($reasonCode): $authMsg"
            }

            val json = JSONObject(responseText)
            val resultCode = json.getJSONObject("response")
                .getJSONObject("header")
                .getString("resultCode")

            if (resultCode != "00") return@withContext "날씨 API 오류 (코드: $resultCode)"

            val items = json.getJSONObject("response")
                .getJSONObject("body")
                .getJSONObject("items")
                .getJSONArray("item")

            // 해당 날짜 정오(1200) 데이터 수집
            val data = mutableMapOf<String, String>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.getString("fcstDate") == fcstDate &&
                    item.getString("fcstTime") == "1200") {
                    data[item.getString("category")] = item.getString("fcstValue")
                }
            }

            if (data.isEmpty()) {
                return@withContext "$date 예보 없음 (기상청 단기예보는 오늘부터 3일 이내만 제공)"
            }

            val sky = skyMap[data["SKY"]] ?: "알 수 없음"
            val pty = ptyMap[data["PTY"]] ?: ""
            val tmp = data["TMP"] ?: "-"
            val pop = data["POP"] ?: "-"
            val reh = data["REH"] ?: "-"

            "$sky$pty | 기온 ${tmp}도 | 강수확률 ${pop}% | 습도 ${reh}%"
        } catch (e: Exception) {
            "날씨 정보를 가져오는 중 오류: ${e.message}"
        }
    }
}
