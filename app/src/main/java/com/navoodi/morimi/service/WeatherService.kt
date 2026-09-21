package com.navoodi.morimi.service

import android.util.Log
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 기상청 중기예보 — 서버 프록시(weatherMidFcst) 경유.
 * 기상청 서비스 키는 앱에 없다([CloudProxy] 참조). 구역코드·발표시각 계산은 그대로 앱이 하고,
 * 프록시는 키만 붙여 기상청 응답 JSON을 그대로 돌려준다.
 */
object WeatherService {

    private const val TAG = "WeatherService"
    private const val FN = "weatherMidFcst"
    private const val TIMEOUT_SEC = 30L

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
    // 0~5시에는 오늘 06시 발표본이 아직 없으므로 전날 18시 발표본을 사용해야 한다.
    private fun getTmFc(): String {
        val now = LocalDateTime.now()
        val (baseDate, baseHour) = when {
            now.hour >= 18 -> now.toLocalDate() to 18
            now.hour >= 6  -> now.toLocalDate() to 6
            else           -> now.toLocalDate().minusDays(1) to 18  // 0~5시: 전날 18시 발표본
        }
        return baseDate.format(DateTimeFormatter.BASIC_ISO_DATE) +
            String.format("%02d00", baseHour)
    }

    /** 프록시 호출 후 response.body.items.item[0] 를 꺼낸다 (예보 필드가 담긴 객체) */
    private suspend fun fetchFirstItem(kind: String, regId: String, tmFc: String): JSONObject {
        val data = JSONObject().put("kind", kind).put("regId", regId).put("tmFc", tmFc)
        return CloudProxy.callJson(FN, data, TIMEOUT_SEC)
            .getJSONObject("response").getJSONObject("body")
            .getJSONObject("items").getJSONArray("item").getJSONObject(0)
    }

    suspend fun getWeather(city: String, date: String): String {
        if (date == "미정" || !date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return "모임 날짜가 확정되지 않아 날씨를 가져올 수 없습니다"
        }

        val targetDate = try { LocalDate.parse(date) } catch (e: Exception) {
            return "날짜 형식 오류: $date"
        }
        val dayDiff = ChronoUnit.DAYS.between(LocalDate.now(), targetDate).toInt()

        when {
            dayDiff < 0  -> return "이미 지난 날짜입니다"
            dayDiff < 3  -> return "$date 날씨: 중기예보는 3일 이후부터 제공됩니다"
            dayDiff > 10 -> return "$date 는 중기예보 범위(최대 10일)를 초과합니다"
        }

        val tmFc = getTmFc()

        // ── 육상 중기예보 (날씨상태, 강수확률) ──────────────────────────────
        val landItem = try {
            fetchFirstItem("land", findLandRegion(city), tmFc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "육상 중기예보 조회 실패 city=$city", e)
            return "날씨 정보를 가져오는 중 오류: ${e.message}"
        }

        val wfAm   = landItem.optString("wf${dayDiff}Am", "")
        val wfPm   = landItem.optString("wf${dayDiff}Pm", "")
        val rnStAm = landItem.optString("rnSt${dayDiff}Am", "-")
        val rnStPm = landItem.optString("rnSt${dayDiff}Pm", "-")
        val sky    = wfPm.ifBlank { wfAm }

        // ── 중기 기온예보 (최저/최고기온) — 실패해도 날씨 상태는 살린다 ────────
        val (taMin, taMax) = try {
            val taItem = fetchFirstItem("ta", findTaRegion(city), tmFc)
            taItem.optString("taMin$dayDiff", "-") to taItem.optString("taMax$dayDiff", "-")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "기온 중기예보 조회 실패 city=$city — 기온 공란", e)
            "-" to "-"
        }

        return "$sky | 최저 ${taMin}도 / 최고 ${taMax}도 | 강수확률 오전 ${rnStAm}% / 오후 ${rnStPm}%"
    }
}
