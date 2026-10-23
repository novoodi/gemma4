package com.navoodi.morimi.ui.screen.aireport

import com.navoodi.morimi.data.model.RecommendedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지도 표시 결정 로직 — Compose·WebView 없이 JVM에서 검증.
 * 장소 0곳 / 1곳 / 5곳 이상 / 일부만 좌표 / 키 없음 + 주입·링크 이상 입력.
 */
class PlaceMapPlannerTest {

    private val key = "0123456789abcdef0123456789abcdef"

    private fun place(name: String, lat: Double? = 37.5 , lng: Double? = 127.0, address: String = "서울 어딘가", url: String = "") =
        RecommendedPlace(name = name, address = address, placeUrl = url, latitude = lat, longitude = lng)

    private fun ready(state: PlaceMapState) = state as PlaceMapState.Ready
    private fun fallback(state: PlaceMapState) = state as PlaceMapState.Fallback

    // ── 장소 수 ───────────────────────────────────────────

    @Test
    fun `0곳 - 지도 영역을 숨긴다(키가 있어도)`() {
        assertEquals(PlaceMapState.Hidden, PlaceMapPlanner.plan(emptyList(), key))
        assertEquals(PlaceMapState.Hidden, PlaceMapPlanner.plan(emptyList(), null))
    }

    @Test
    fun `1곳 - 핀 1개, 번호 1`() {
        val s = ready(PlaceMapPlanner.plan(listOf(place("미미식당", 37.50, 127.03)), key))
        assertEquals(listOf(MapPin(0, 1, "미미식당", 37.50, 127.03)), s.pins)
        assertEquals(0, s.missingLocation)
    }

    @Test
    fun `5곳 이상 - 전부 한 지도에 핀, 번호는 카드 순서`() {
        val places = (1..7).map { place("가게$it", 37.5 + it * 0.001, 127.0 + it * 0.001) }
        val s = ready(PlaceMapPlanner.plan(places, key))
        assertEquals(7, s.pins.size)
        assertEquals((1..7).toList(), s.pins.map { it.label })
        assertTrue(s.html.contains("\"n\":7"))
    }

    @Test
    fun `일부만 좌표 - 좌표 없는 장소는 핀에서 빠지고 번호는 카드 번호 유지`() {
        val places = listOf(place("A"), place("B", null, null), place("C"), place("D", lat = 37.5, lng = null))
        val s = ready(PlaceMapPlanner.plan(places, key))
        assertEquals(listOf(1, 3), s.pins.map { it.label })
        assertEquals(listOf(0, 2), s.pins.map { it.index })
        assertEquals(2, s.missingLocation)
    }

    @Test
    fun `전부 좌표 없음 - 지도 대신 목록, 모든 장소 포함`() {
        val places = listOf(place("A", null, null), place("B", null, null, address = ""))
        val f = fallback(PlaceMapPlanner.plan(places, key))
        assertEquals(MapFallbackReason.NO_COORDINATES, f.reason)
        assertEquals(listOf("A", "B"), f.entries.map { it.name })
        assertTrue(f.entries.none { it.hasLocation })
    }

    @Test
    fun `범위 밖 좌표만 있으면 좌표 없음과 같다`() {
        val f = fallback(PlaceMapPlanner.plan(listOf(place("A", 127.0, 37.5)), key))
        assertEquals(MapFallbackReason.NO_COORDINATES, f.reason)
    }

    // ── 키 없음 ───────────────────────────────────────────

    @Test
    fun `키 없음 - null·빈 값·공백이면 대체 목록`() {
        listOf(null, "", "   ").forEach { k ->
            val f = fallback(PlaceMapPlanner.plan(listOf(place("A")), k))
            assertEquals(MapFallbackReason.KEY_MISSING, f.reason)
            assertTrue(f.reason.message.contains("지도를 불러올 수 없음"))
        }
    }

    @Test
    fun `키 이상 - 따옴표·스크립트가 섞인 키는 HTML에 넣지 않는다`() {
        listOf("abc\"><script>alert(1)</script>", "0123456789abcdef&x=1", "short").forEach { k ->
            assertEquals(MapFallbackReason.KEY_MISSING, fallback(PlaceMapPlanner.plan(listOf(place("A")), k)).reason)
        }
    }

    @Test
    fun `키 없음 - 대체 목록에도 주소와 카카오맵 링크가 있다`() {
        val f = fallback(PlaceMapPlanner.plan(listOf(place("미미식당", address = "서울 강남구 1")), ""))
        val e = f.entries.single()
        assertEquals("서울 강남구 1", e.address)
        assertTrue(e.link.startsWith("https://map.kakao.com/link/map/"))
        assertTrue(e.hasLocation)
    }

    @Test
    fun `키 앞뒤 공백은 허용하고 HTML에는 트림된 키`() {
        val s = ready(PlaceMapPlanner.plan(listOf(place("A")), "  $key  "))
        assertTrue(s.html.contains("appkey=$key&autoload=false"))
    }

    @Test
    fun `로드 실패 대체 목록 문구`() {
        val f = PlaceMapPlanner.fallback(listOf(place("A")), MapFallbackReason.LOAD_FAILED)
        assertEquals("지도를 불러올 수 없음", f.reason.message)
        assertEquals(1, f.entries.size)
    }

    // ── HTML 주입 방어 ────────────────────────────────────

    @Test
    fun `주입 - 장소명에 script 종료 태그가 있어도 HTML 블록을 벗어나지 않는다`() {
        val evil = "미미</script><script>alert('x')</script>\"식당"
        val html = ready(PlaceMapPlanner.plan(listOf(place(evil)), key)).html
        assertFalse(html.contains("</script><script>alert"))
        assertFalse(html.contains("alert('x')"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
    }

    @Test
    fun `주입 - 줄바꿈·역슬래시·U+2028도 이스케이프`() {
        assertEquals("\"a\\nb\\\\c\\u2028d\\\"\"", PlaceMapPlanner.jsString("a\nb\\c\u2028d\""))
    }

    // ── 카카오맵 링크 ─────────────────────────────────────

    @Test
    fun `링크 - 장소 상세 URL이 있으면 그대로`() {
        assertEquals("https://place.map.kakao.com/1", PlaceMapPlanner.kakaoMapLink(place("A", url = "https://place.map.kakao.com/1")))
    }

    @Test
    fun `링크 - URL이 없으면 좌표 링크, 이름의 공백·쉼표는 인코딩`() {
        val link = PlaceMapPlanner.kakaoMapLink(place("미미 식당, 본점", 37.5, 127.0))
        assertTrue(link, link.startsWith("https://map.kakao.com/link/map/"))
        assertFalse(link.contains(" "))
        assertTrue(link.endsWith(",37.5,127.0"))
        assertEquals(2, link.count { it == ',' })
    }

    @Test
    fun `링크 - 좌표도 없으면 이름 검색 링크`() {
        val link = PlaceMapPlanner.kakaoMapLink(place("미미식당", null, null))
        assertTrue(link.startsWith("https://map.kakao.com/link/search/"))
    }

    @Test
    fun `링크 이상 - javascript·intent 스킴 URL은 쓰지 않는다`() {
        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a").forEach { u ->
            val link = PlaceMapPlanner.kakaoMapLink(place("A", url = u))
            assertTrue(link, link.startsWith("https://map.kakao.com/"))
        }
    }

    @Test
    fun `링크 이상 - 이름이 비어도 유효한 링크`() {
        val link = PlaceMapPlanner.kakaoMapLink(place("   ", null, null))
        assertTrue(link.startsWith("https://map.kakao.com/link/search/") && link.length > "https://map.kakao.com/link/search/".length)
    }
}
