package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카카오 documents → KakaoPlace 좌표 파싱, 그리고 오케스트레이터가 추천 장소에 좌표를 옮겨 담는 단계.
 * 결함을 찾는 관점: 깨진 좌표가 다른 필드를 망가뜨리지 않는가, 엉뚱한 가게의 좌표가 붙지 않는가.
 */
class KakaoPlaceCoordinateTest {

    private fun doc(name: String, x: Any? = "126.9245", y: Any? = "37.5571") = JSONObject().apply {
        put("place_name", name)
        put("category_name", "음식점 > 한식")
        put("phone", "02-000-0000")
        put("address_name", "서울 마포구 서교동 1")
        put("road_address_name", "서울 마포구 와우산로 1")
        put("place_url", "https://place.map.kakao.com/1")
        if (x != null) put("x", x)
        if (y != null) put("y", y)
    }

    private fun parse(vararg docs: Any) = KakaoLocalService.parseDocuments(JSONArray(docs.toList()))

    // ── 파싱 ──────────────────────────────────────────────

    @Test
    fun `정상 - x·y 문자열이 경도·위도로 들어간다`() {
        val p = parse(doc("미미식당")).single()
        assertEquals(37.5571, p.latitude!!, 1e-9)
        assertEquals(126.9245, p.longitude!!, 1e-9)
        assertEquals("서울 마포구 와우산로 1", p.roadAddress)
    }

    @Test
    fun `정상 - 숫자 타입 x·y도 받는다`() {
        val p = parse(doc("미미식당", x = 127.0276, y = 37.4979)).single()
        assertEquals(37.4979, p.latitude!!, 1e-9)
    }

    @Test
    fun `좌표 없음 - x·y 키가 없으면 null, 나머지 필드는 유지`() {
        val p = parse(doc("미미식당", x = null, y = null)).single()
        assertNull(p.latitude)
        assertNull(p.longitude)
        assertEquals("미미식당", p.name)
        assertEquals("https://place.map.kakao.com/1", p.url)
    }

    @Test
    fun `빈 문자열 - 좌표만 null`() {
        val p = parse(doc("미미식당", x = "", y = "")).single()
        assertNull(p.latitude)
        assertEquals("서울 마포구 서교동 1", p.address)
    }

    @Test
    fun `숫자 아님 - 좌표만 null이고 가게는 버리지 않는다`() {
        val ps = parse(doc("미미식당", x = "abc", y = "37.5"), doc("소담식당"))
        assertEquals(listOf("미미식당", "소담식당"), ps.map { it.name })
        assertNull(ps[0].latitude)
        assertEquals(37.5571, ps[1].latitude!!, 1e-9)
    }

    @Test
    fun `범위 밖 - 뒤바뀐 x·y와 JSON null은 좌표 없음`() {
        val ps = parse(doc("뒤바뀜", x = "37.5571", y = "126.9245"), doc("널", x = JSONObject.NULL, y = JSONObject.NULL))
        assertTrue(ps.all { it.latitude == null && it.longitude == null })
    }

    @Test
    fun `이상 입력 - 객체가 아닌 원소는 건너뛴다`() {
        val ps = parse(doc("미미식당"), "문자열", 42, JSONObject.NULL)
        assertEquals(listOf("미미식당"), ps.map { it.name })
    }

    // ── 추천 장소에 좌표 옮기기 (AssistantOrchestrator.toRecommendedPlace) ──

    private val kakao = listOf(
        KakaoPlace("미미식당", "", "", "서울 강남구 역삼동 1", "서울 강남구 테헤란로 1", "https://place.map.kakao.com/10", 37.50, 127.03),
        KakaoPlace("소담식당", "", "", "서울 강남구 논현동 2", "", "https://place.map.kakao.com/20", null, null),
        KakaoPlace("카페 모모", "", "", "서울 마포구 연남동 3", "", "https://place.map.kakao.com/30", 37.56, 126.92),
    )

    @Test
    fun `매칭 - 이름이 같으면 주소·링크·좌표가 함께 채워진다`() {
        val p = AssistantOrchestrator.toRecommendedPlace("미미식당 — 조용해서 좋음", kakao)
        assertEquals("미미식당", p.name)
        assertEquals("조용해서 좋음", p.reason)
        assertEquals("서울 강남구 테헤란로 1", p.address)
        assertEquals(37.50, p.latitude!!, 1e-9)
        assertEquals(127.03, p.longitude!!, 1e-9)
    }

    @Test
    fun `매칭 - 검색 결과에 좌표가 없으면 주소는 채우고 좌표는 null`() {
        val p = AssistantOrchestrator.toRecommendedPlace("소담식당", kakao)
        assertEquals("서울 강남구 논현동 2", p.address)
        assertNull(p.geoPoint)
    }

    @Test
    fun `매칭 - 검색 결과에 없는 장소는 주소·좌표 모두 비어 있다`() {
        val p = AssistantOrchestrator.toRecommendedPlace("유령식당", kakao)
        assertEquals("", p.address)
        assertNull(p.latitude)
        assertNull(p.longitude)
    }

    @Test
    fun `매칭 - 검색 결과가 비면 좌표 없음`() {
        assertNull(AssistantOrchestrator.toRecommendedPlace("미미식당", emptyList()).geoPoint)
    }

    @Test
    fun `매칭 - 일반명사 '카페'가 다른 가게 '카페 모모'의 좌표를 가져오면 안 된다`() {
        val p = AssistantOrchestrator.toRecommendedPlace("카페", kakao)
        assertNull("엉뚱한 가게 좌표가 붙음: ${p.address}", p.geoPoint)
    }

    // ── findKakaoMatch = PlaceMatcher.bestMatch (Guardrail과 같은 규칙) ──────────

    private fun kp(name: String, address: String, url: String, lat: Double? = 37.5, lng: Double? = 127.0) =
        KakaoPlace(name, "", "", address, "", url, lat, lng)

    @Test
    fun `매칭 - 일반명사 두 개('카페 바')도 다른 가게에 붙지 않는다`() {
        val p = AssistantOrchestrator.toRecommendedPlace("카페 바", listOf(kp("카페 바 모노", "서울 마포구 1", "u1")))
        assertNull(p.geoPoint)
        assertEquals("", p.placeUrl)
    }

    @Test
    fun `매칭 - 동명 가게가 다른 시도에만 있으면 붙이지 않는다`() {
        val busan = listOf(kp("미미식당", "부산 해운대구 1", "busan", 35.16, 129.16))
        val p = AssistantOrchestrator.toRecommendedPlace("미미식당", busan, city = "서울")
        assertNull(p.geoPoint)
        assertEquals("", p.address)
    }

    @Test
    fun `매칭 - 동명 가게가 여러 시도에 있으면 모임 시도 가게를 고른다`() {
        val both = listOf(kp("미미식당", "부산 해운대구 1", "busan", 35.16, 129.16), kp("미미식당", "서울 강남구 1", "seoul", 37.50, 127.03))
        val p = AssistantOrchestrator.toRecommendedPlace("미미식당", both, city = "강남")
        assertEquals("seoul", p.placeUrl)
        assertEquals(37.50, p.latitude!!, 1e-9)
    }

    @Test
    fun `매칭 - 모임 지역 미정이면 지역 검사 없이 이름으로 고른다`() {
        val busan = listOf(kp("미미식당", "부산 해운대구 1", "busan", 35.16, 129.16))
        assertEquals("busan", AssistantOrchestrator.toRecommendedPlace("미미식당", busan, city = "미정").placeUrl)
    }

    @Test
    fun `매칭 - 정확히 같은 이름을 포함 관계보다 먼저 고른다`() {
        val list = listOf(kp("원조 미미식당", "서울 강남구 1", "wonjo"), kp("미미식당", "서울 강남구 2", "exact"))
        assertEquals("exact", AssistantOrchestrator.toRecommendedPlace("미미식당", list, city = "서울").placeUrl)
    }

    @Test
    fun `매칭 - 지점명이 붙은 검색 결과도 같은 가게로 본다`() {
        val list = listOf(kp("스타벅스 홍대입구역점", "서울 마포구 1", "sb"))
        assertEquals("sb", AssistantOrchestrator.toRecommendedPlace("스타벅스 홍대점", list, city = "홍대").placeUrl)
    }

    // ── Guardrail 매칭 가게로 보충 (fillFromGuardrail) ─────────────────────────

    private val gMimi = kp("미미식당", "서울 강남구 역삼동 1", "https://place.map.kakao.com/10", 37.50, 127.03)

    @Test
    fun `보충 - searchPlace 매칭이 없으면 Guardrail 가게로 주소·링크·좌표를 채운다`() {
        val p = AssistantOrchestrator.fillFromGuardrail(RecommendedPlace(name = "미미식당", reason = "r"), mapOf("미미식당" to gMimi))
        assertEquals("서울 강남구 역삼동 1", p.address)
        assertEquals("https://place.map.kakao.com/10", p.placeUrl)
        assertEquals(37.50, p.latitude!!, 1e-9)
        assertEquals("r", p.reason)
    }

    @Test
    fun `보충 - searchPlace 매칭이 있으면 다른 가게로 덮어쓰지 않는다`() {
        val fromSearch = RecommendedPlace(name = "미미식당", address = "서울 서초구 2", placeUrl = "https://place.map.kakao.com/99", latitude = 37.48, longitude = 127.01)
        assertEquals(fromSearch, AssistantOrchestrator.fillFromGuardrail(fromSearch, mapOf("미미식당" to gMimi)))
    }

    @Test
    fun `보충 - 같은 가게(같은 링크)인데 좌표만 빠졌으면 좌표만 채운다`() {
        val noGeo = RecommendedPlace(name = "미미식당", address = "검색 주소", placeUrl = "https://place.map.kakao.com/10")
        val p = AssistantOrchestrator.fillFromGuardrail(noGeo, mapOf("미미식당" to gMimi))
        assertEquals("검색 주소", p.address)
        assertEquals(127.03, p.longitude!!, 1e-9)
    }

    @Test
    fun `보충 - 다른 가게 링크인데 좌표가 빠졌으면 섞지 않는다`() {
        val other = RecommendedPlace(name = "미미식당", address = "검색 주소", placeUrl = "https://place.map.kakao.com/77")
        assertNull(AssistantOrchestrator.fillFromGuardrail(other, mapOf("미미식당" to gMimi)).geoPoint)
    }

    @Test
    fun `보충 - Guardrail 매칭이 없으면(UNKNOWN·CLOSED) 그대로`() {
        val bare = RecommendedPlace(name = "미미식당")
        assertEquals(bare, AssistantOrchestrator.fillFromGuardrail(bare, emptyMap()))
    }

    @Test
    fun `보충 - Guardrail 가게에 좌표가 없으면 주소·링크만 채운다`() {
        val p = AssistantOrchestrator.fillFromGuardrail(
            RecommendedPlace(name = "미미식당"), mapOf("미미식당" to gMimi.copy(latitude = null, longitude = null)),
        )
        assertEquals("서울 강남구 역삼동 1", p.address)
        assertNull(p.geoPoint)
    }
}
