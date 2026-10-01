package com.navoodi.morimi.service

import com.navoodi.morimi.service.PlaceMatcher.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMatcherTest {

    private fun place(name: String, address: String = "서울 마포구 서교동 1-1", road: String = "") =
        KakaoPlace(name = name, category = "", phone = "", address = address, roadAddress = road, url = "")

    // ── 정상 ──────────────────────────────────────────────

    @Test
    fun `정상 - 이름 완전 일치`() {
        assertTrue(PlaceMatcher.nameMatches("미미식당", "미미식당"))
    }

    @Test
    fun `정상 - 공백과 기호 무시`() {
        assertTrue(PlaceMatcher.nameMatches("카페 & 바 · 무브", "카페바무브"))
        assertTrue(PlaceMatcher.nameMatches("Cafe Move!", "cafemove"))
    }

    @Test
    fun `정상 - 끝의 지점명 무시`() {
        assertTrue(PlaceMatcher.nameMatches("스타벅스 홍대점", "스타벅스 홍대입구역점"))
    }

    @Test
    fun `정상 - 추천명 앞 지역명 토큰 제거`() {
        assertTrue(PlaceMatcher.nameMatches("홍대 미미식당", "미미식당"))
        assertTrue(PlaceMatcher.nameMatches("강남역 미미식당", "미미식당 본관"))
    }

    @Test
    fun `정상 - 포함 관계 허용`() {
        assertTrue(PlaceMatcher.nameMatches("미미식당", "원조 미미식당"))
    }

    @Test
    fun `정상 - 같은 시도의 인접 동네는 MATCHED`() {
        // 모임은 홍대, 가게는 신촌(서대문구) — 같은 서울
        val r = PlaceMatcher.evaluate("미미식당", listOf(place("미미식당", "서울 서대문구 창천동 1")), "홍대")
        assertEquals(Outcome.MATCHED, r)
    }

    @Test
    fun `정상 - 여러 결과 중 하나만 맞아도 MATCHED`() {
        val results = listOf(
            place("미미분식", "서울 마포구 1"),
            place("미미식당", "부산 해운대구 1"),
            place("미미식당", "서울특별시 마포구 2"),
        )
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, "서울"))
    }

    // ── 비정상 ────────────────────────────────────────────

    @Test
    fun `비정상 - 지어낸 이름에 비슷한 가게만 검색되면 NOT_FOUND`() {
        val results = listOf(place("미미분식"), place("미소식당"), place("미미네 국수"))
        assertEquals(Outcome.NOT_FOUND, PlaceMatcher.evaluate("미미식당", results, "서울"))
    }

    @Test
    fun `비정상 - 이름은 맞지만 다른 시도면 OUT_OF_REGION`() {
        val results = listOf(place("미미식당", "부산 해운대구 우동 1"))
        assertEquals(Outcome.OUT_OF_REGION, PlaceMatcher.evaluate("미미식당", results, "서울"))
    }

    @Test
    fun `비정상 - 동네명 모임도 시도로 환원해 지역 밖 판정`() {
        val results = listOf(place("미미식당", "경기 성남시 분당구 1"))
        assertEquals(Outcome.OUT_OF_REGION, PlaceMatcher.evaluate("미미식당", results, "해운대"))
    }

    @Test
    fun `비정상 - 검색 결과 없음은 NOT_FOUND`() {
        assertEquals(Outcome.NOT_FOUND, PlaceMatcher.evaluate("미미식당", emptyList(), "서울"))
    }

    @Test
    fun `비정상 - 전혀 다른 이름`() {
        assertFalse(PlaceMatcher.nameMatches("달빛정원", "스타벅스"))
    }

    // ── 정상에 가까운 오류 ────────────────────────────────

    @Test
    fun `근접 오류 - 한 글자 포함은 일치로 보지 않는다`() {
        assertFalse(PlaceMatcher.nameMatches("소", "소담식당"))
    }

    @Test
    fun `근접 오류 - 지점명만 같고 상호가 다르면 불일치`() {
        assertFalse(PlaceMatcher.nameMatches("미미식당 홍대점", "투썸플레이스 홍대점"))
    }

    @Test
    fun `근접 오류 - 한 글자 다른 상호는 불일치`() {
        assertFalse(PlaceMatcher.nameMatches("미미식당", "미니식당"))
    }

    @Test
    fun `근접 오류 - 지역 토큰을 떼도 상호가 다르면 불일치`() {
        assertFalse(PlaceMatcher.nameMatches("홍대 미미식당", "홍대 포차"))
    }

    @Test
    fun `근접 오류 - 단독 토큰의 점은 지점명으로 지우지 않는다`() {
        assertEquals("본점", PlaceMatcher.normalizeName("본점"))
        assertEquals("미미식당", PlaceMatcher.normalizeName("미미식당 연남점"))
    }

    // ── 이상 입력 ─────────────────────────────────────────

    @Test
    fun `이상 입력 - 빈 이름과 기호만 있는 이름`() {
        assertFalse(PlaceMatcher.nameMatches("", "미미식당"))
        assertFalse(PlaceMatcher.nameMatches("미미식당", ""))
        assertFalse(PlaceMatcher.nameMatches("!!!", "★☆"))
    }

    @Test
    fun `이상 입력 - 미정 빈값 모르는 지역은 지역 검사 생략`() {
        val results = listOf(place("미미식당", "부산 해운대구 1"))
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, "미정"))
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, ""))
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, "아틀란티스"))
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, null))
    }

    @Test
    fun `이상 입력 - 주소가 비어 시도를 알 수 없는 결과는 지역 불일치로 단정하지 않음`() {
        val results = listOf(place("미미식당", address = "", road = ""))
        assertEquals(Outcome.MATCHED, PlaceMatcher.evaluate("미미식당", results, "서울"))
    }

    @Test
    fun `이상 입력 - 지번 주소 없으면 도로명 주소로 판정`() {
        val results = listOf(place("미미식당", address = "", road = "부산 해운대구 해운대로 1"))
        assertEquals(Outcome.OUT_OF_REGION, PlaceMatcher.evaluate("미미식당", results, "서울"))
    }

    @Test
    fun `이상 입력 - 시도 정식 약식 표기와 접미사 처리`() {
        assertEquals("서울", PlaceMatcher.provinceOfCity("서울특별시"))
        assertEquals("서울", PlaceMatcher.provinceOfCity("  강남역 "))
        assertEquals("서울", PlaceMatcher.provinceOfCity("익선동"))
        assertEquals("강원", PlaceMatcher.provinceOfAddress("강원특별자치도 강릉시 1"))
        assertEquals("세종", PlaceMatcher.provinceOfAddress("세종특별자치시 나성동 1"))
        assertNull(PlaceMatcher.provinceOfCity("미정"))
        assertNull(PlaceMatcher.provinceOfCity("   "))
        assertNull(PlaceMatcher.provinceOfAddress(null))
    }

    @Test
    fun `이상 입력 - 지역명만 있는 추천명은 토큰 제거하지 않음`() {
        assertNull(PlaceMatcher.withoutLeadingRegion("홍대"))
        assertNull(PlaceMatcher.withoutLeadingRegion("미미식당 본관"))
        assertEquals("미미식당", PlaceMatcher.withoutLeadingRegion("서울 미미식당"))
    }
}
