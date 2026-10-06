package com.navoodi.morimi.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * WeatherService 결함 4건 — 네트워크(CloudProxy) 없이 순수 함수만 검증한다.
 *
 * 1) 모르는 지역을 말없이 서울 구역코드로 대체
 * 2) 8~10일 뒤 예보(오전/오후 구분 없는 wf8·rnSt8 형식) 미처리
 * 3) 오전·오후 하늘 상태가 달라도 오후만 표시
 * 4) 응답 형식이 깨지면 예외 메시지가 그대로 노출
 */
class WeatherServiceTest {

    private val unavailable = WeatherService.UNAVAILABLE

    /** 기상청 응답 봉투 — response.body.items.item[0] 에 [fields]를 담는다 */
    private fun envelope(fields: String): JSONObject = JSONObject(
        """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
           "body":{"dataType":"JSON","items":{"item":[{$fields}]},"pageNo":1,"numOfRows":10,"totalCount":1}}}"""
    )

    private fun ta(day: Int, min: Int = 12, max: Int = 21) = envelope(""""taMin$day":$min,"taMax$day":$max""")

    private fun format(landFields: String, day: Int, ta: JSONObject? = ta(day)) =
        WeatherService.formatForecast(envelope(landFields), ta, day)

    // ── 1) 지역 매핑 ─────────────────────────────────────────

    @Test
    fun `지역 정상 - 기존 매핑 지역의 구역코드는 그대로`() {
        assertEquals(WeatherService.Region("11B00000", "11B10101"), WeatherService.regionOf("서울"))
        assertEquals(WeatherService.Region("11B00000", "11B10101"), WeatherService.regionOf("홍대"))
        assertEquals(WeatherService.Region("11H20000", "11H20201"), WeatherService.regionOf("부산"))
        assertEquals(WeatherService.Region("11G00000", "11G00201"), WeatherService.regionOf("제주"))
        assertEquals(WeatherService.Region("11D20000", "11D20501"), WeatherService.regionOf("강릉"))
    }

    @Test
    fun `지역 비정상 - 매핑에 없는 지역은 서울로 대체하지 않고 null`() {
        listOf("뉴욕", "평양", "도쿄", "아무데나", "가까운 곳", "Seoul?").forEach {
            assertNull("'$it'", WeatherService.regionOf(it))
        }
    }

    @Test
    fun `지역 경계 - 빈 값과 미정은 null`() {
        listOf("", "   ", "미정", "\n").forEach { assertNull("'$it'", WeatherService.regionOf(it)) }
    }

    @Test
    fun `지역 보강 - 동네 이름은 PlaceMatcher 사전으로 시도 구역에 연결`() {
        val seoul = WeatherService.regionOf("서울")
        listOf("신촌", "성수", "이태원", "잠실", "건대", "연남동").forEach {
            assertEquals("'$it'", seoul, WeatherService.regionOf(it))
        }
        val busan = WeatherService.regionOf("부산")
        listOf("해운대", "광안리", "서면").forEach { assertEquals("'$it'", busan, WeatherService.regionOf(it)) }
        assertEquals("11B00000", WeatherService.regionOf("판교")?.land)
        assertEquals("11B00000", WeatherService.regionOf("송도")?.land)
        assertEquals("11G00000", WeatherService.regionOf("서귀포")?.land)
    }

    @Test
    fun `지역 보강 - 서울이 아닌 시군은 자기 권역 구역코드`() {
        mapOf(
            "여수" to "11F20000", "목포" to "11F20000", "군산" to "11F10000",
            "경주" to "11H10000", "포항" to "11H10000", "천안" to "11C20000",
            "충주" to "11C10000", "김해" to "11H20000", "통영" to "11H20000",
        ).forEach { (city, land) ->
            assertEquals("'$city'", land, WeatherService.regionOf(city)?.land)
            assertFalse("'$city' 기온 구역이 서울", WeatherService.regionOf(city)?.ta == "11B10101")
        }
    }

    @Test
    fun `지역 보강 - 강원은 영서와 영동을 구분하고 도 이름만으로는 미지원`() {
        assertEquals("11D20000", WeatherService.regionOf("속초")?.land)
        assertEquals("11D20000", WeatherService.regionOf("양양")?.land)
        assertEquals("11D10000", WeatherService.regionOf("원주")?.land)
        assertEquals("11D10000", WeatherService.regionOf("춘천")?.land)
        assertEquals("11D20000", WeatherService.regionOf("강원도 강릉시")?.land)
        assertNull(WeatherService.regionOf("강원도"))
    }

    @Test
    fun `지역 이상 입력 - 행정구역 접미사와 긴 표기도 해석`() {
        assertEquals(WeatherService.regionOf("서울"), WeatherService.regionOf("서울특별시 마포구"))
        assertEquals(WeatherService.regionOf("부산"), WeatherService.regionOf("부산광역시 해운대구"))
        assertEquals(WeatherService.regionOf("부산"), WeatherService.regionOf("  해운대역 "))
        assertEquals("11H20000", WeatherService.regionOf("경상남도")?.land)
        assertEquals("11F20000", WeatherService.regionOf("전남")?.land)
    }

    @Test
    fun `지역 미지원 - getWeather는 미지원을 알리고 서울 날씨를 조회하지 않는다`() {
        val date = LocalDate.now().plusDays(5).toString()
        val result = runBlocking { WeatherService.getWeather("뉴욕", date) }
        assertTrue(result, result.contains("해당 지역 예보 미지원"))
        assertTrue(result, result.contains("뉴욕"))
        val undecided = runBlocking { WeatherService.getWeather("미정", date) }
        assertTrue(undecided, undecided.contains("모임 지역이 정해지지 않아"))
        // 날짜 미정 안내는 지역 검사보다 먼저다 (기존 동작 유지)
        assertEquals(
            "모임 날짜가 확정되지 않아 날씨를 가져올 수 없습니다",
            runBlocking { WeatherService.getWeather("뉴욕", "미정") },
        )
    }

    // ── 2) 8~10일 뒤 예보 (오전/오후 구분 없음) ─────────────────

    @Test
    fun `장기 정상 - 8일 뒤는 wf8과 rnSt8로 읽는다`() {
        assertEquals("구름많음 | 최저 12도 / 최고 21도 | 강수확률 40%", format(""""wf8":"구름많음","rnSt8":40""", 8))
    }

    @Test
    fun `장기 정상 - 9일 10일 뒤도 같은 형식`() {
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 10%", format(""""wf9":"맑음","rnSt9":10""", 9))
        assertEquals("흐리고 비 | 최저 12도 / 최고 21도 | 강수확률 80%", format(""""wf10":"흐리고 비","rnSt10":80""", 10))
    }

    @Test
    fun `장기 양쪽 형식 - 8일 뒤에 오전 오후 필드가 오면 그것을 우선한다`() {
        assertEquals(
            "맑음 | 최저 12도 / 최고 21도 | 강수확률 오전 10% / 오후 20%",
            format(""""wf8Am":"맑음","wf8Pm":"맑음","rnSt8Am":10,"rnSt8Pm":20,"wf8":"흐림","rnSt8":90""", 8),
        )
    }

    @Test
    fun `장기 경계 - 7일 뒤는 오전 오후 형식 그대로`() {
        assertEquals(
            "흐림 | 최저 12도 / 최고 21도 | 강수확률 오전 30% / 오후 40%",
            format(""""wf7Am":"흐림","wf7Pm":"흐림","rnSt7Am":30,"rnSt7Pm":40,"wf8":"맑음","rnSt8":0""", 7),
        )
    }

    @Test
    fun `장기 경계 - 다른 날짜 필드를 끌어다 쓰지 않는다`() {
        // 8일 뒤 필드만 있는 응답에서 9일 뒤를 물으면 8일 값을 내보내지 않는다
        assertEquals(unavailable, format(""""wf8":"맑음","rnSt8":0""", 9))
        // wf1·wf10 접두 혼동 없음
        assertEquals(unavailable, format(""""wf10":"맑음","rnSt10":0""", 1))
    }

    @Test
    fun `장기 비정상 - 강수확률만 빠지면 하늘 상태는 살린다`() {
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 -%", format(""""wf8":"맑음"""", 8))
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 0%", format(""""wf8":"맑음","rnSt8":0""", 8))
    }

    @Test
    fun `장기 이상 입력 - 숫자가 문자열이나 실수로 와도 읽는다`() {
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 30%", format(""""wf8":" 맑음 ","rnSt8":"30"""", 8))
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 30%", format(""""wf8":"맑음","rnSt8":30.0""", 8))
    }

    // ── 3) 오전·오후 하늘 상태 ─────────────────────────────────

    @Test
    fun `하늘 정상 - 오전 오후가 같으면 한 번만 (기존 형식 유지)`() {
        assertEquals(
            "맑음 | 최저 12도 / 최고 21도 | 강수확률 오전 0% / 오후 10%",
            format(""""wf5Am":"맑음","wf5Pm":"맑음","rnSt5Am":0,"rnSt5Pm":10""", 5),
        )
    }

    @Test
    fun `하늘 정상 - 오전 오후가 다르면 둘 다 표시`() {
        assertEquals(
            "오전 맑음 / 오후 흐리고 비 | 최저 12도 / 최고 21도 | 강수확률 오전 10% / 오후 70%",
            format(""""wf5Am":"맑음","wf5Pm":"흐리고 비","rnSt5Am":10,"rnSt5Pm":70""", 5),
        )
    }

    @Test
    fun `하늘 경계 - 공백 차이만 있으면 같은 것으로 본다`() {
        assertTrue(format(""""wf5Am":"구름많음 ","wf5Pm":" 구름많음","rnSt5Am":20,"rnSt5Pm":20""", 5).startsWith("구름많음 | "))
    }

    @Test
    fun `하늘 비정상 - 한쪽만 있으면 있는 쪽을 쓴다`() {
        assertTrue(format(""""wf5Am":"맑음","rnSt5Am":0""", 5).startsWith("맑음 | "))
        assertTrue(format(""""wf5Pm":"흐림","rnSt5Pm":30""", 5).startsWith("흐림 | "))
        assertTrue(format(""""wf5Am":"맑음","wf5Pm":"","rnSt5Am":0,"rnSt5Pm":0""", 5).startsWith("맑음 | "))
        assertEquals(
            "맑음 | 최저 12도 / 최고 21도 | 강수확률 오전 0% / 오후 -%",
            format(""""wf5Am":"맑음","rnSt5Am":0""", 5),
        )
    }

    @Test
    fun `하늘 이상 입력 - null 값은 없는 것으로 본다`() {
        val r = format(""""wf5Am":null,"wf5Pm":"흐림","rnSt5Am":null,"rnSt5Pm":30""", 5)
        assertEquals("흐림 | 최저 12도 / 최고 21도 | 강수확률 오전 -% / 오후 30%", r)
        assertFalse(r, r.contains("null"))
    }

    @Test
    fun `하늘 이상 입력 - 문자열이 아닌 값은 버린다`() {
        assertEquals(unavailable, format(""""wf5Am":{"a":1},"wf5Pm":[1,2]""", 5))
        assertTrue(format(""""wf5Am":{"a":1},"wf5Pm":"맑음"""", 5).startsWith("맑음 | "))
    }

    // ── 4) 깨진 응답 ─────────────────────────────────────────

    private val brokenLandResponses = mapOf(
        "빈 객체" to "{}",
        "response 없음" to """{"result":"ok"}""",
        "body 없음(기상청 오류 응답)" to """{"response":{"header":{"resultCode":"03","resultMsg":"NO_DATA"}}}""",
        "items 없음" to """{"response":{"body":{"totalCount":0}}}""",
        "items가 빈 문자열" to """{"response":{"body":{"items":""}}}""",
        "items가 null" to """{"response":{"body":{"items":null}}}""",
        "item 없음" to """{"response":{"body":{"items":{}}}}""",
        "item 빈 배열" to """{"response":{"body":{"items":{"item":[]}}}}""",
        "item 원소가 객체가 아님" to """{"response":{"body":{"items":{"item":["x"]}}}}""",
        "item 원소가 빈 객체" to """{"response":{"body":{"items":{"item":[{}]}}}}""",
        "response가 문자열" to """{"response":"SERVICE ERROR"}""",
    )

    @Test
    fun `깨진 응답 - 육상예보 형식이 깨지면 예외 없이 가져올 수 없음`() {
        brokenLandResponses.forEach { (label, json) ->
            assertEquals(label, unavailable, WeatherService.formatForecast(JSONObject(json), ta(5), 5))
        }
    }

    @Test
    fun `깨진 응답 - 응답 자체가 없으면 가져올 수 없음`() {
        assertEquals(unavailable, WeatherService.formatForecast(null, null, 5))
        assertEquals(unavailable, WeatherService.formatForecast(null, ta(5), 5))
    }

    @Test
    fun `깨진 응답 - 해당 날짜 필드가 없으면 가져올 수 없음`() {
        assertEquals(unavailable, format(""""regId":"11B00000"""", 5))
        assertEquals(unavailable, format(""""wf5Am":"","wf5Pm":"  ","rnSt5Am":10,"rnSt5Pm":10""", 5))
        // 범위 밖 날짜
        listOf(-1, 0, 11, 99).forEach { assertEquals("day=$it", unavailable, format(""""wf5Am":"맑음"""", it)) }
    }

    @Test
    fun `깨진 응답 - 기온 응답만 깨지면 날씨는 살리고 기온은 공란`() {
        val land = """"wf5Am":"맑음","wf5Pm":"맑음","rnSt5Am":0,"rnSt5Pm":10"""
        val expected = "맑음 | 최저 -도 / 최고 -도 | 강수확률 오전 0% / 오후 10%"
        assertEquals(expected, format(land, 5, ta = null))
        brokenLandResponses.forEach { (label, json) -> assertEquals(label, expected, format(land, 5, ta = JSONObject(json))) }
        // 한쪽 기온만 있는 경우
        assertEquals(
            "맑음 | 최저 12도 / 최고 -도 | 강수확률 오전 0% / 오후 10%",
            format(land, 5, ta = envelope(""""taMin5":12,"taMax5":null""")),
        )
    }

    @Test
    fun `깨진 응답 - item이 배열이 아니라 단일 객체여도 읽는다`() {
        val land = JSONObject("""{"response":{"body":{"items":{"item":{"wf8":"맑음","rnSt8":20}}}}}""")
        assertEquals("맑음 | 최저 12도 / 최고 21도 | 강수확률 20%", WeatherService.formatForecast(land, ta(8), 8))
    }

    @Test
    fun `깨진 응답 - 결과 문자열에 예외 메시지나 JSON 조각이 섞이지 않는다`() {
        (brokenLandResponses.values.map { WeatherService.formatForecast(JSONObject(it), null, 5) } +
            format(""""wf5Am":{"a":1}""", 5)).forEach { r ->
            listOf("Exception", "JSONObject", "null", "{", "오류").forEach { bad -> assertFalse("$r ← $bad", r.contains(bad)) }
        }
    }

    @Test
    fun `firstItem - 정상 응답에서 첫 예보 객체를 꺼낸다`() {
        val item = WeatherService.firstItem(envelope(""""wf5Am":"맑음""""))
        assertNotNull(item)
        assertEquals("맑음", item!!.getString("wf5Am"))
    }
}
