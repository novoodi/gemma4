package com.navoodi.morimi.service

import android.util.Log
import org.json.JSONArray
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
        "창원" to "11H20000",
        // 강원은 영서·영동 권역이 달라 시군 단위로 둔다
        "속초" to "11D20000", "양양" to "11D20000", "원주" to "11D10000", "평창" to "11D10000"
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
        "창원" to "11H20301",
        // 기온은 같은 권역 대표 도시(강릉·춘천) 값
        "속초" to "11D20501", "양양" to "11D20501", "원주" to "11D10301", "평창" to "11D10301"
    )

    /**
     * 시·도 → 구역코드. 위 두 표에 없는 동네·시군("해운대", "여수")은 [PlaceMatcher]의
     * 지역→시·도 사전으로 시·도를 구한 뒤 이 표로 간다. 육상예보는 권역 단위라 정확하고,
     * 기온은 그 시·도 대표 도시(위 표에 이미 있는 코드) 값이다.
     * 강원은 영서(11D10000)·영동(11D20000)으로 갈려 도 이름만으로는 정할 수 없어 넣지 않는다.
     */
    private val provinceRegionMap = mapOf(
        "서울" to Region("11B00000", "11B10101"), "인천" to Region("11B00000", "11B20601"),
        "경기" to Region("11B00000", "11B20601"),
        "부산" to Region("11H20000", "11H20201"), "울산" to Region("11H20000", "11H20101"),
        "경남" to Region("11H20000", "11H20301"),
        "대구" to Region("11H10000", "11H10201"), "경북" to Region("11H10000", "11H10201"),
        "광주" to Region("11F20000", "11F20501"), "전남" to Region("11F20000", "11F20501"),
        "전북" to Region("11F10000", "11F10201"),
        "대전" to Region("11C20000", "11C20401"), "세종" to Region("11C20000", "11C20404"),
        "충남" to Region("11C20000", "11C20401"),
        "충북" to Region("11C10000", "11C10301"),
        "제주" to Region("11G00000", "11G00201"),
    )

    internal const val UNAVAILABLE = "날씨 정보를 가져올 수 없음"

    /** 중기 육상예보·기온예보 구역코드 한 쌍 */
    internal data class Region(val land: String, val ta: String)

    /**
     * 도시명 → 구역코드. 매핑에 없는 지역은 null — 예전처럼 서울 코드로 대체하지 않는다
     * (서울 날씨가 그 지역 날씨인 것처럼 나갔다).
     */
    internal fun regionOf(city: String): Region? {
        val land = landRegionMap.entries.firstOrNull { city.contains(it.key) }?.value
        val ta = taRegionMap.entries.firstOrNull { city.contains(it.key) }?.value
        if (land != null && ta != null) return Region(land, ta)
        return PlaceMatcher.provinceOfCity(city)?.let { provinceRegionMap[it] }
    }

    internal fun unsupportedMessage(city: String): String {
        val c = city.trim()
        return if (c.isEmpty() || c == "미정") "모임 지역이 정해지지 않아 날씨를 가져올 수 없습니다"
        else "$c: 해당 지역 예보 미지원 (다른 지역 날씨로 대체하지 않았습니다)"
    }

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

    /**
     * 기상청 응답 JSON에서 response.body.items.item[0](예보 필드가 담긴 객체)을 꺼낸다.
     * 형식이 깨졌으면(items 없음·빈 문자열·빈 배열 등) 예외 대신 null.
     */
    internal fun firstItem(response: JSONObject?): JSONObject? {
        val item = response?.optJSONObject("response")?.optJSONObject("body")
            ?.optJSONObject("items")?.opt("item")
        return when (item) {
            is JSONArray -> item.optJSONObject(0)
            is JSONObject -> item
            else -> null
        }
    }

    /** 문자열·숫자 필드만 읽는다. 없음·null·공백·객체는 null (optString은 Android에서 null을 "null"로 돌려준다) */
    private fun JSONObject.text(key: String): String? = when (val v = opt(key)) {
        is String -> v.trim().ifEmpty { null }
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString()
        else -> null
    }

    /**
     * 육상·기온 중기예보 응답을 [day]일 뒤 날씨 한 줄로 정리한다 (순수 함수 — 네트워크·Android 의존 없음).
     *
     * 육상예보 필드는 두 형식이 있다 — 오전/오후 구분(wf{n}Am·wf{n}Pm, rnSt{n}Am·rnSt{n}Pm)과
     * 하루 단위(wf{n}, rnSt{n}: 8~10일 뒤). 오전/오후 필드가 있으면 그쪽을, 없으면 하루 단위를 읽는다.
     * 그 날짜의 하늘 상태를 읽을 수 없으면 [UNAVAILABLE]. 기온만 없으면 기온은 "-"로 둔다.
     */
    internal fun formatForecast(land: JSONObject?, ta: JSONObject?, day: Int): String {
        val landItem = firstItem(land) ?: return UNAVAILABLE
        val wfAm = landItem.text("wf${day}Am")
        val wfPm = landItem.text("wf${day}Pm")
        val sky = when {
            wfAm != null && wfPm != null && wfAm != wfPm -> "오전 $wfAm / 오후 $wfPm"
            else -> wfPm ?: wfAm ?: landItem.text("wf$day")
        } ?: return UNAVAILABLE

        val rnStAm = landItem.text("rnSt${day}Am")
        val rnStPm = landItem.text("rnSt${day}Pm")
        val split = wfAm != null || wfPm != null || rnStAm != null || rnStPm != null
        val rain = if (split) "강수확률 오전 ${rnStAm ?: "-"}% / 오후 ${rnStPm ?: "-"}%"
        else "강수확률 ${landItem.text("rnSt$day") ?: "-"}%"

        val taItem = firstItem(ta)
        val taMin = taItem?.text("taMin$day") ?: "-"
        val taMax = taItem?.text("taMax$day") ?: "-"
        return "$sky | 최저 ${taMin}도 / 최고 ${taMax}도 | $rain"
    }

    private suspend fun fetch(kind: String, regId: String, tmFc: String): JSONObject {
        val data = JSONObject().put("kind", kind).put("regId", regId).put("tmFc", tmFc)
        return CloudProxy.callJson(FN, data, TIMEOUT_SEC)
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

        val region = regionOf(city) ?: return unsupportedMessage(city)
        val tmFc = getTmFc()

        // ── 육상 중기예보 (날씨상태, 강수확률) ──────────────────────────────
        val land = try {
            fetch("land", region.land, tmFc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "육상 중기예보 조회 실패 city=$city", e)
            return UNAVAILABLE
        }
        if (firstItem(land) == null) {
            Log.w(TAG, "육상 중기예보 응답 형식 오류 city=$city: ${land.toString().take(200)}")
            return UNAVAILABLE
        }

        // ── 중기 기온예보 (최저/최고기온) — 실패해도 날씨 상태는 살린다 ────────
        val ta = try {
            fetch("ta", region.ta, tmFc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "기온 중기예보 조회 실패 city=$city — 기온 공란", e)
            null
        }

        return formatForecast(land, ta, dayDiff)
    }
}
