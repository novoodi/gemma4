package com.navoodi.morimi.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class GuardrailServiceTest {

    private fun place(name: String, address: String = "서울 마포구 서교동 1") =
        KakaoPlace(name = name, category = "", phone = "", address = address, roadAddress = "", url = "")

    /** 검색어 → 결과 고정 응답. 등록되지 않은 검색어는 결과 없음. 호출 기록을 남긴다. */
    private class FakeSearch(private val table: Map<String, PlaceSearchResult>) {
        val calls: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val fn: suspend (String) -> PlaceSearchResult = { q ->
            calls += q
            table[q] ?: PlaceSearchResult.Found(emptyList())
        }
    }

    private fun service(vararg entries: Pair<String, PlaceSearchResult>) =
        GuardrailService(FakeSearch(entries.toMap()).fn)

    private fun found(vararg places: KakaoPlace) = PlaceSearchResult.Found(places.toList())

    private fun statuses(r: GuardrailResult) = r.verifiedPlaces.associate { it.name to it.status }

    // ── 정상 ──────────────────────────────────────────────

    @Test
    fun `정상 - 이름과 시도가 일치하면 OPEN 통과`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당"))).verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
        assertEquals("", r.feedbackForRetry)
    }

    @Test
    fun `정상 - 지점명이 붙은 검색 결과도 OPEN`() = runBlocking {
        val r = service("스타벅스 홍대점" to found(place("스타벅스 홍대입구역점")))
            .verify(listOf("스타벅스 홍대점"), "홍대")
        assertEquals(PlaceStatus.OPEN, statuses(r)["스타벅스 홍대점"])
    }

    @Test
    fun `정상 - 인접 동네 추천은 같은 시도라 OPEN`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당", "서울 서대문구 창천동 1")))
            .verify(listOf("미미식당"), "홍대")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
    }

    @Test
    fun `정상 - 도시 미정이면 지역 검사 생략`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당", "부산 해운대구 1"))).verify(listOf("미미식당"), "미정")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
    }

    @Test
    fun `정상 - 후보가 없으면 통과`() = runBlocking {
        val r = service().verify(emptyList(), "서울")
        assertTrue(r.passed)
        assertTrue(r.verifiedPlaces.isEmpty())
    }

    // ── 비정상 ────────────────────────────────────────────

    @Test
    fun `비정상 - 지어낸 이름에 비슷한 가게만 검색되면 CLOSED`() = runBlocking {
        val r = service("미미식당" to found(place("미미분식"), place("미소식당")))
            .verify(listOf("미미식당"), "서울")
        assertFalse(r.passed)
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당"])
        assertTrue(r.feedbackForRetry.contains("존재하지 않는"))
        assertFalse(r.feedbackForRetry.contains("모임 지역"))
    }

    @Test
    fun `비정상 - 검색 결과 없음은 CLOSED`() = runBlocking {
        val r = service().verify(listOf("유령식당"), "서울")
        assertFalse(r.passed)
        assertEquals(PlaceStatus.CLOSED, statuses(r)["유령식당"])
    }

    @Test
    fun `비정상 - 다른 시도의 가게는 CLOSED이고 지역 밖 피드백`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당", "부산 해운대구 1")))
            .verify(listOf("미미식당"), "서울")
        assertFalse(r.passed)
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당"])
        assertTrue(r.feedbackForRetry.contains("모임 지역(서울)"))
        assertFalse(r.feedbackForRetry.contains("존재하지 않는"))
    }

    @Test
    fun `비정상 - 존재하지 않음과 지역 밖을 구분해 함께 피드백`() = runBlocking {
        val r = service(
            "미미식당" to found(place("미미식당", "부산 해운대구 1")),
            "달빛정원" to found(),
        ).verify(listOf("미미식당", "달빛정원"), "서울")
        assertFalse(r.passed)
        val existence = r.feedbackForRetry.substringBefore("모임 지역")
        assertTrue(existence.contains("달빛정원"))
        assertFalse(existence.contains("미미식당"))
        assertTrue(r.feedbackForRetry.substringAfter("모임 지역").contains("미미식당"))
    }

    @Test
    fun `비정상 - 하나라도 CLOSED면 실패하고 나머지 상태는 보존`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당")))
            .verify(listOf("미미식당", "유령식당"), "서울")
        assertFalse(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
        assertEquals(PlaceStatus.CLOSED, statuses(r)["유령식당"])
    }

    // ── 정상에 가까운 오류 ────────────────────────────────

    @Test
    fun `근접 오류 - 한 글자 다른 상호는 CLOSED`() = runBlocking {
        val r = service("미미식당" to found(place("미니식당"))).verify(listOf("미미식당"), "서울")
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당"])
    }

    @Test
    fun `근접 오류 - 지점명만 같고 상호가 다르면 CLOSED`() = runBlocking {
        val r = service("미미식당 홍대점" to found(place("투썸플레이스 홍대점")))
            .verify(listOf("미미식당 홍대점"), "서울")
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당 홍대점"])
    }

    @Test
    fun `근접 오류 - 같은 이름이 여러 시도에 있으면 모임 시도 것으로 OPEN`() = runBlocking {
        val r = service(
            "미미식당" to found(place("미미식당", "부산 해운대구 1"), place("미미식당", "서울 마포구 1")),
        ).verify(listOf("미미식당"), "서울")
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
    }

    @Test
    fun `근접 오류 - 같은 이름의 경기도 가게는 서울 모임에서 CLOSED`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당", "경기 고양시 일산동구 1")))
            .verify(listOf("미미식당"), "서울 강남")
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당"])
    }

    @Test
    fun `근접 오류 - 추천명 앞 지역 토큰이 있어도 상호가 맞으면 OPEN`() = runBlocking {
        val r = service("홍대 미미식당" to found(place("미미식당"))).verify(listOf("홍대 미미식당"), "서울")
        assertEquals(PlaceStatus.OPEN, statuses(r)["홍대 미미식당"])
    }

    // ── 이상 입력 ─────────────────────────────────────────

    @Test
    fun `이상 입력 - 길이 범위 밖 이름은 검색하지 않음`() = runBlocking {
        val fake = FakeSearch(emptyMap())
        val r = GuardrailService(fake.fn).verify(listOf("A", "가".repeat(26), "   "), "서울")
        assertTrue(r.passed)
        assertTrue(r.verifiedPlaces.isEmpty())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `이상 입력 - 공백 트림 후 중복 제거`() = runBlocking {
        val fake = FakeSearch(mapOf("미미식당" to found(place("미미식당"))))
        val r = GuardrailService(fake.fn).verify(listOf(" 미미식당 ", "미미식당"), "서울")
        assertEquals(1, r.verifiedPlaces.size)
        assertEquals(setOf("미미식당"), fake.calls)
    }

    @Test
    fun `이상 입력 - 후보는 최대 5건만 검증`() = runBlocking {
        val fake = FakeSearch(emptyMap())
        val r = GuardrailService(fake.fn).verify((1..8).map { "식당$it" }, "서울")
        assertEquals(5, r.verifiedPlaces.size)
        assertEquals(5, fake.calls.size)
    }

    @Test
    fun `이상 입력 - 모르는 도시명은 지역 검사 생략`() = runBlocking {
        val r = service("미미식당" to found(place("미미식당", "제주특별자치도 제주시 1")))
            .verify(listOf("미미식당"), "@@##")
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
    }

    @Test
    fun `이상 입력 - 검색 결과의 이름이 비어 있으면 일치로 보지 않음`() = runBlocking {
        val r = service("미미식당" to found(place(""))).verify(listOf("미미식당"), "서울")
        assertEquals(PlaceStatus.CLOSED, statuses(r)["미미식당"])
    }

    // ── 서버 장애 ─────────────────────────────────────────

    @Test
    fun `서버 장애 - 프록시 실패는 UNKNOWN이고 통과`() = runBlocking {
        val r = service("미미식당" to PlaceSearchResult.Failed("UNAVAILABLE")).verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.UNKNOWN, statuses(r)["미미식당"])
        assertEquals("", r.feedbackForRetry)
    }

    @Test
    fun `서버 장애 - 검색 함수 예외도 UNKNOWN`() = runBlocking {
        val r = GuardrailService { throw RuntimeException("timeout") }.verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.UNKNOWN, statuses(r)["미미식당"])
    }

    @Test
    fun `서버 장애 - 오류와 결과 없음을 구분`() = runBlocking {
        val r = service(
            "미미식당" to PlaceSearchResult.Failed("DEADLINE_EXCEEDED"),
            "유령식당" to found(),
        ).verify(listOf("미미식당", "유령식당"), "서울")
        assertEquals(PlaceStatus.UNKNOWN, statuses(r)["미미식당"])
        assertEquals(PlaceStatus.CLOSED, statuses(r)["유령식당"])
        assertFalse(r.passed)
        assertFalse(r.feedbackForRetry.contains("미미식당"))
    }

    @Test
    fun `서버 장애 - 일부만 실패해도 나머지는 정상 판정`() = runBlocking {
        val r = service(
            "미미식당" to PlaceSearchResult.Failed("INTERNAL"),
            "소담식당" to found(place("소담식당")),
        ).verify(listOf("미미식당", "소담식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.UNKNOWN, statuses(r)["미미식당"])
        assertEquals(PlaceStatus.OPEN, statuses(r)["소담식당"])
    }

    @Test
    fun `서버 장애 - 전부 실패하면 전부 UNKNOWN이고 재시도 피드백 없음`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Failed("UNAVAILABLE") }
            .verify(listOf("미미식당", "소담식당", "달빛정원"), "서울")
        assertTrue(r.passed)
        assertTrue(r.verifiedPlaces.all { it.status == PlaceStatus.UNKNOWN })
        assertEquals("", r.feedbackForRetry)
    }
}
