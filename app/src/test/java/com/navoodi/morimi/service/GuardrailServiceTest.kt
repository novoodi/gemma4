package com.navoodi.morimi.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
    fun `이상 입력 - 길이로 거르지 않고 1자·26자 이름도 검색한다 (공백만 제외)`() = runBlocking {
        val fake = FakeSearch(emptyMap())
        val r = GuardrailService(fake.fn).verify(listOf("A", "가".repeat(26), "   "), "서울")
        assertFalse(r.passed)
        assertEquals(setOf("A", "가".repeat(26)), fake.calls)
        assertTrue(r.verifiedPlaces.all { it.status == PlaceStatus.CLOSED })
    }

    @Test
    fun `이상 입력 - 공백 트림 후 중복 제거`() = runBlocking {
        val fake = FakeSearch(mapOf("미미식당" to found(place("미미식당"))))
        val r = GuardrailService(fake.fn).verify(listOf(" 미미식당 ", "미미식당"), "서울")
        assertEquals(1, r.verifiedPlaces.size)
        assertEquals(setOf("미미식당"), fake.calls)
    }

    @Test
    fun `이상 입력 - 후보 개수 상한 없이 8건 모두 검증`() = runBlocking {
        val fake = FakeSearch(emptyMap())
        val r = GuardrailService(fake.fn).verify((1..8).map { "식당$it" }, "서울")
        assertEquals(8, r.verifiedPlaces.size)
        assertEquals(8, fake.calls.size)
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

    // ══ 결함 수정 보강 (DEFECT_TEST_2026-10 S6-8·S6-9·S6-12·S6-13) ══════════════

    /** 검색어를 순서대로 모두 기록(중복 포함). [real]에 있는 이름만 서울에 실존. */
    private class RecordingSearch(vararg real: String) {
        private val places = real.map { KakaoPlace(it, "", "", "서울 강남구 1", "", "") }
        val queries: MutableList<String> = CopyOnWriteArrayList()
        val fn: suspend (String) -> PlaceSearchResult = { q ->
            queries += q
            PlaceSearchResult.Found(places.filter { PlaceMatcher.nameMatches(q, it.name) })
        }
    }

    // ── 결함 1: 추천 0곳이 성공 처리됨 ─────────────────────────────────────

    @Test
    fun `0곳 - 빈 목록은 실패이고 1곳 이상 추천하라는 피드백`() = runBlocking {
        val search = RecordingSearch()
        val r = GuardrailService(search.fn).verify(emptyList(), "서울")
        assertFalse(r.passed)
        assertTrue(r.verifiedPlaces.isEmpty())
        assertTrue(r.feedbackForRetry.contains("1곳 이상"))
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `0곳 - 공백 이름만 있으면 0곳과 같다`() = runBlocking {
        val search = RecordingSearch()
        val r = GuardrailService(search.fn).verify(listOf("", "   ", "\t\n"), "서울")
        assertFalse(r.passed)
        assertEquals(GuardrailService.EMPTY_FEEDBACK, r.feedbackForRetry)
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun `0곳 - 피드백은 존재하지 않음·지역 밖 문구와 섞이지 않는다`() = runBlocking {
        val r = GuardrailService(RecordingSearch().fn).verify(emptyList(), "서울")
        assertFalse(r.feedbackForRetry.contains("존재하지 않는"))
        assertFalse(r.feedbackForRetry.contains("모임 지역"))
    }

    @Test
    fun `0곳 경계 - 실존 1곳이면 통과`() = runBlocking {
        val r = GuardrailService(RecordingSearch("미미식당").fn).verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)["미미식당"])
    }

    @Test
    fun `0곳 경계 - 1곳이 검증 불가(장애)면 0곳이 아니라 UNKNOWN 통과`() = runBlocking {
        val r = GuardrailService { PlaceSearchResult.Failed("UNAVAILABLE") }.verify(listOf("미미식당"), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.UNKNOWN, statuses(r)["미미식당"])
    }

    // ── 결함 2: 6번째 이후 장소 미검증 ─────────────────────────────────────

    @Test
    fun `전부 검증 - 8곳 모두 실존이면 8곳 모두 OPEN`() = runBlocking {
        val names = (1..8).map { "가게$it" }
        val search = RecordingSearch(*names.toTypedArray())
        val r = GuardrailService(search.fn).verify(names, "서울")
        assertTrue(r.passed)
        assertEquals(8, search.queries.size)
        assertTrue(r.verifiedPlaces.all { it.status == PlaceStatus.OPEN })
    }

    @Test
    fun `전부 검증 - 6번째가 지어낸 이름이면 CLOSED로 실패`() = runBlocking {
        val real = listOf("미미식당", "소담식당", "서울식당", "달빛술집", "조용한찻집")
        val r = GuardrailService(RecordingSearch(*real.toTypedArray()).fn).verify(real + "유령식당", "서울")
        assertFalse(r.passed)
        assertEquals(PlaceStatus.CLOSED, statuses(r)["유령식당"])
        assertTrue(r.feedbackForRetry.contains("유령식당"))
    }

    @Test
    fun `전부 검증 - 결과 순서는 입력 순서를 유지`() = runBlocking {
        val names = (1..12).map { "가게$it" }
        val r = GuardrailService(RecordingSearch(*names.toTypedArray()).fn).verify(names, "서울")
        assertEquals(names, r.verifiedPlaces.map { it.name })
    }

    @Test
    fun `전부 검증 - 20곳이어도 동시 검색은 상한 이하`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val searched = CopyOnWriteArrayList<String>()
        val svc = GuardrailService { q ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(20)
            searched += q
            inFlight.decrementAndGet()
            PlaceSearchResult.Found(listOf(KakaoPlace(q, "", "", "서울 강남구 1", "", "")))
        }
        val names = (1..20).map { "가게$it" }
        val r = svc.verify(names, "서울")
        assertTrue(r.passed)
        assertEquals(20, searched.size)
        assertTrue("동시 ${peak.get()}건", peak.get() in 2..GuardrailService.MAX_CONCURRENT_SEARCHES)
    }

    @Test
    fun `전부 검증 - 같은 장소명은 한 번만 검색`() = runBlocking {
        val search = RecordingSearch("미미식당")
        val r = GuardrailService(search.fn).verify(
            listOf("미미식당", "미미식당 - 분위기 좋음", "미미식당 — 맛집", " 미미식당 "), "서울",
        )
        assertEquals(listOf("미미식당"), search.queries)
        assertEquals(3, r.verifiedPlaces.size) // " 미미식당 "은 트림 후 중복
        assertTrue(r.verifiedPlaces.all { it.status == PlaceStatus.OPEN })
    }

    @Test
    fun `전부 검증 - 12곳 중 일부 장애는 그 장소만 UNKNOWN`() = runBlocking {
        val names = (1..12).map { "가게$it" }
        val svc = GuardrailService { q ->
            if (q == "가게7" || q == "가게11") PlaceSearchResult.Failed("DEADLINE_EXCEEDED")
            else PlaceSearchResult.Found(listOf(KakaoPlace(q, "", "", "서울 강남구 1", "", "")))
        }
        val r = svc.verify(names, "서울")
        assertTrue(r.passed)
        assertEquals(
            setOf("가게7", "가게11"),
            r.verifiedPlaces.filter { it.status == PlaceStatus.UNKNOWN }.map { it.name }.toSet(),
        )
        assertEquals(10, r.verifiedPlaces.count { it.status == PlaceStatus.OPEN })
    }

    // ── 결함 3: 25자 넘는 이름 미검증 / 이름 뒤 이유 ──────────────────────────

    @Test
    fun `긴 이름 - 26자 넘는 지어낸 이름도 검색해서 CLOSED`() = runBlocking {
        val fake = "강남역 앞 아주 오래된 전통 한정식 코스 요리 전문점 별관"
        val search = RecordingSearch("미미식당")
        val r = GuardrailService(search.fn).verify(listOf(fake), "서울")
        assertTrue(fake.length > 25)
        assertEquals(listOf(fake), search.queries)
        assertFalse(r.passed)
        assertEquals(PlaceStatus.CLOSED, statuses(r)[fake])
    }

    @Test
    fun `긴 이름 - 26자 넘는 실존 상호는 OPEN`() = runBlocking {
        val longReal = "더 그레이트 코리안 바비큐 하우스 앤 키친 강남"
        assertTrue(longReal.length > 25)
        val r = GuardrailService(RecordingSearch(longReal).fn).verify(listOf(longReal), "서울")
        assertTrue(r.passed)
        assertEquals(PlaceStatus.OPEN, statuses(r)[longReal])
    }

    @Test
    fun `이유 분리 - 하이픈 뒤 이유는 떼고 장소명만 검색, 결과 이름은 원문 유지`() = runBlocking {
        val raw = "미미식당 - 분위기 좋음"
        val search = RecordingSearch("미미식당")
        val r = GuardrailService(search.fn).verify(listOf(raw), "서울")
        assertEquals(listOf("미미식당"), search.queries)
        assertEquals(PlaceStatus.OPEN, statuses(r)[raw])
    }

    @Test
    fun `이유 분리 - 대시·콜론·괄호 주소 형태도 장소명만 검색`() = runBlocking {
        val raws = listOf("소담식당 — 조용함", "서울식당: 가성비", "달빛술집 (서울 마포구 서교동)")
        val search = RecordingSearch("소담식당", "서울식당", "달빛술집")
        val r = GuardrailService(search.fn).verify(raws, "서울")
        assertEquals(setOf("소담식당", "서울식당", "달빛술집"), search.queries.toSet())
        assertTrue(r.passed)
    }

    @Test
    fun `이유 분리 - 지어낸 이름에 이유가 붙으면 피드백에는 장소명만`() = runBlocking {
        val r = GuardrailService(RecordingSearch("미미식당").fn).verify(listOf("유령식당 - 분위기 좋음"), "서울")
        assertFalse(r.passed)
        assertTrue(r.feedbackForRetry.contains("유령식당"))
        assertFalse(r.feedbackForRetry.contains("분위기 좋음"))
    }

    @Test
    fun `긴 이름 - 상호명 상한을 넘는 이름은 검색 없이 존재하지 않음`() = runBlocking {
        val absurd = "가".repeat(GuardrailService.MAX_PLACE_NAME_LEN + 1)
        val search = RecordingSearch()
        val r = GuardrailService(search.fn).verify(listOf(absurd), "서울")
        assertTrue(search.queries.isEmpty())
        assertEquals(PlaceStatus.CLOSED, statuses(r)[absurd])
    }

    @Test
    fun `이유 분리 - 공백 없는 하이픈은 이름의 일부로 둔다`() = runBlocking {
        val search = RecordingSearch("W-카페")
        val r = GuardrailService(search.fn).verify(listOf("W-카페"), "서울")
        assertEquals(listOf("W-카페"), search.queries)
        assertTrue(r.passed)
    }
}
