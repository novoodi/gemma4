package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guardrail이 찾은 가게(matchedPlaces)를 추천 장소의 주소·링크·좌표로 재사용 —
 * Gemini가 searchPlace 도구를 쓰지 않아도 지도 핀이 생기는지, 추가 카카오 호출이 없는지.
 */
class GuardrailLocationReuseTest {

    private fun kp(name: String, address: String, url: String, lat: Double? = null, lng: Double? = null) =
        KakaoPlace(name, "", "", address, "", url, lat, lng)

    private val mimi = kp("미미식당", "서울 강남구 역삼동 1", "https://place.map.kakao.com/10", 37.5012, 127.0396)
    private val soda = kp("소담식당", "서울 강남구 논현동 2", "https://place.map.kakao.com/20", 37.5110, 127.0220)
    private val noGeo = kp("서울식당", "서울 종로구 관철동 3", "https://place.map.kakao.com/30")
    private val busanMimi = kp("미미식당", "부산 해운대구 우동 6", "https://place.map.kakao.com/99", 35.1631, 129.1635)

    // ── GuardrailResult.matchedPlaces ─────────────────────────────────────

    @Test
    fun `matched - OPEN 장소만 근거 가게가 담긴다`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Found(listOf(mimi, soda)) }.verify(listOf("미미식당", "유령식당"), "서울")
        assertEquals(mapOf("미미식당" to mimi), r.matchedPlaces)
    }

    @Test
    fun `matched - UNKNOWN(검색 장애)은 담지 않는다`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Failed("UNAVAILABLE") }.verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertTrue(r.matchedPlaces.isEmpty())
    }

    @Test
    fun `matched - 모임 지역 밖(CLOSED)은 담지 않는다`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Found(listOf(busanMimi)) }.verify(listOf("미미식당"), "서울")
        assertFalse(r.passed)
        assertTrue(r.matchedPlaces.isEmpty())
    }

    @Test
    fun `matched - 동명 가게가 여러 시도면 모임 시도 가게`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Found(listOf(busanMimi, mimi)) }.verify(listOf("미미식당"), "강남")
        assertEquals(mimi, r.matchedPlaces["미미식당"])
    }

    @Test
    fun `matched - 이유가 붙은 원문 이름을 키로, 같은 가게를 가리킨다`() = runBlocking {
        val raw = "미미식당 - 조용함"
        val r = GuardrailService { PlaceSearchResult.Found(listOf(mimi)) }.verify(listOf(raw, "미미식당"), "서울")
        assertEquals(mapOf(raw to mimi, "미미식당" to mimi), r.matchedPlaces)
    }

    @Test
    fun `matched - 기존 3필드만 쓰는 생성은 그대로 동작(기본값 emptyMap)`() {
        val r = GuardrailResult(passed = true, verifiedPlaces = emptyList(), feedbackForRetry = "")
        assertTrue(r.matchedPlaces.isEmpty())
    }

    // ── 오케스트레이터 전체 흐름 (도구 미사용) ─────────────────────────────────

    private val dinner = listOf(msg("김민수", "토요일 강남에서 저녁 먹자", 1), msg("이지영", "좋아 밥 먹자", 2))

    private class CountingSearch(private val places: List<KakaoPlace>) {
        val queries: MutableList<String> = CopyOnWriteArrayList()
        val fn: suspend (String) -> PlaceSearchResult = { q -> queries += q; PlaceSearchResult.Found(places) }
    }

    @Test
    fun `흐름 - 도구 없이도 Guardrail 가게로 주소·링크·좌표가 채워진다`() {
        val search = CountingSearch(listOf(mimi, soda))
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당"))), search = search.fn)
        val byName = r.places.associateBy { it.name }
        assertEquals("서울 강남구 역삼동 1", byName.getValue("미미식당").address)
        assertEquals("https://place.map.kakao.com/20", byName.getValue("소담식당").placeUrl)
        assertEquals(37.5012, byName.getValue("미미식당").latitude!!, 1e-9)
        assertTrue(r.places.all { it.verification == VerificationStatus.VERIFIED })
    }

    @Test
    fun `흐름 - 추가 카카오 호출 없음(장소당 Guardrail 검색 1회뿐)`() {
        val search = CountingSearch(listOf(mimi, soda))
        AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당"))), search = search.fn)
        assertEquals(listOf("미미식당", "소담식당"), search.queries.sorted())
    }

    @Test
    fun `흐름 - Guardrail UNKNOWN이면 좌표 없이 통과(지도 핀 없음)`() {
        val r = AgentFlow.run(
            dinner, ScriptedGemini.of(Gem.final(listOf("미미식당"))),
            search = { PlaceSearchResult.Failed("UNAVAILABLE") },
        )
        val p = r.places.single()
        assertEquals(VerificationStatus.UNVERIFIED, p.verification)
        assertNull(p.geoPoint)
        assertEquals("", p.address)
    }

    @Test
    fun `흐름 - 검색 결과에 좌표가 없으면 주소·링크만`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("서울식당"))), search = CountingSearch(listOf(noGeo)).fn)
        val p = r.places.single()
        assertEquals("서울 종로구 관철동 3", p.address)
        assertNull(p.geoPoint)
    }

    @Test
    fun `흐름 - 일부만 찾으면 찾은 장소만 채운다(나머지 UNKNOWN은 비움)`() {
        val r = AgentFlow.run(
            dinner, ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당"))),
            search = { q -> if (q == "소담식당") PlaceSearchResult.Failed("DEADLINE_EXCEEDED") else PlaceSearchResult.Found(listOf(mimi)) },
        )
        val byName = r.places.associateBy { it.name }
        assertEquals(37.5012, byName.getValue("미미식당").latitude!!, 1e-9)
        assertNull(byName.getValue("소담식당").geoPoint)
    }

    @Test
    fun `흐름 - 둘 다 없음(가짜 장소 계속)이면 실패하고 위치도 없다`() {
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("유령식당"))), search = CountingSearch(listOf(mimi)).fn)
        assertTrue(r.result is OrchestratorResult.Failed)
        assertTrue(r.places.isEmpty())
    }

    @Test
    fun `흐름 - 업종명뿐인 추천('카페')에 다른 가게 위치가 붙지 않는다`() {
        val cafeMomo = kp("카페 모모", "서울 강남구 4", "https://place.map.kakao.com/40", 37.50, 127.04)
        val r = AgentFlow.run(dinner, ScriptedGemini.of(Gem.final(listOf("카페"))), search = CountingSearch(listOf(cafeMomo)).fn)
        assertTrue(r.result is OrchestratorResult.Failed) // 실존 확인 불가 → 재시도 소진
        assertTrue(r.guardrails.all { !it.passed })
    }
}
