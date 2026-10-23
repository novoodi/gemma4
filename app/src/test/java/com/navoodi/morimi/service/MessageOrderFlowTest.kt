package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 메시지 순서 결함(DEFECT_TEST_2026-10 S3-1·S3-2·S3-5) — 오케스트레이터 전체 흐름.
 * 리스트가 섞이거나 같은 시각에 여러 명이 말해도 "마지막 결정"이 같은지, 누락이 없는지 본다.
 */
class MessageOrderFlowTest {

    private val okGemini get() = ScriptedGemini.of(Gem.final(listOf("미미식당")))

    private fun msgAt(sender: String, content: String, t: Long, id: String = "$sender@$t", pending: Boolean = false) =
        Message(id = id, roomId = AgentFlow.ROOM, senderId = sender, senderName = sender, content = content, timestamp = t, pending = pending)

    private val areaChange = listOf(
        msgAt("김민수", "토요일 홍대에서 저녁 먹자", 1_000),
        msgAt("이지영", "좋아", 1_500),
        msgAt("박서준", "아 홍대 말고 강남으로 하자", 2_000),
    )

    // ── S3-1 지역 ─────────────────────────────────────────────────────────

    @Test
    fun `S3-1 정상 - 순서대로 오면 마지막 결정(강남)`() {
        assertEquals("강남", AgentFlow.run(areaChange, okGemini).slot("지역"))
    }

    @Test
    fun `S3-1 비정상 - 모든 순열에서 같은 결과(강남)`() {
        val perms = listOf(areaChange.reversed(), listOf(areaChange[2], areaChange[0], areaChange[1]), listOf(areaChange[1], areaChange[2], areaChange[0]))
        perms.forEach { assertEquals(it.map { m -> m.id }.toString(), "강남", AgentFlow.run(it, okGemini).slot("지역")) }
    }

    @Test
    fun `S3-1 이상 - 시각 없는 메시지(0)는 최신 결정을 덮지 못한다`() {
        val msgs = areaChange + msgAt("최유나", "홍대 가자", 0)
        assertEquals("강남", AgentFlow.run(msgs.shuffled(java.util.Random(7)), okGemini).slot("지역"))
    }

    @Test
    fun `S3-1 경계 - 방금 보낸 내 메시지(서버 미확정)는 기기 시계가 늦어도 최신으로`() {
        val mine = msgAt("나", "그냥 성수로 하자", 500, pending = true) // 기기 시계 추정치가 과거
        assertEquals("성수", AgentFlow.run(listOf(mine) + areaChange, okGemini).slot("지역"))
    }

    @Test
    fun `S3-1 경계 - 늦게 도착한 옛 메시지는 시각 자리로 들어가 최신 결정을 바꾸지 않는다`() {
        val late = msgAt("최유나", "신촌은 어때", 1_200) // 강남 결정(2000)보다 앞선 말이 뒤늦게 도착
        assertEquals("강남", AgentFlow.run(areaChange + late, okGemini).slot("지역"))
    }

    @Test
    fun `S3-1 정상 - 온디바이스 요약에도 시각 순으로 들어간다`() {
        val r = AgentFlow.run(areaChange.reversed(), okGemini)
        assertEquals(areaChange.map { it.id }, r.llm.received.single().map { it.id })
    }

    // ── S3-2 날짜 ─────────────────────────────────────────────────────────

    private val dateChange = listOf(
        msgAt("김민수", "토요일에 보자", 1_000),
        msgAt("이지영", "일요일로 바꾸자", 2_000),
    )

    @Test
    fun `S3-2 정상 - 순서대로면 일요일(10-04)`() {
        assertTrue(AgentFlow.run(dateChange, okGemini).slot("일시").startsWith("2026-10-04"))
    }

    @Test
    fun `S3-2 비정상 - 역순이어도 일요일`() {
        assertTrue(AgentFlow.run(dateChange.reversed(), okGemini).slot("일시").startsWith("2026-10-04"))
    }

    @Test
    fun `S3-2 비정상 - 날짜를 두 번 바꾼 뒤 섞여도 마지막(금요일 10-02)`() {
        val msgs = dateChange + msgAt("박서준", "아니 금요일로 하자", 3_000)
        assertTrue(AgentFlow.run(msgs.shuffled(java.util.Random(3)), okGemini).slot("일시").startsWith("2026-10-02"))
    }

    @Test
    fun `S3-2 이상 - 시각 없는 날짜 언급은 확정 날짜를 덮지 않는다`() {
        val msgs = listOf(msgAt("최유나", "10월 20일 어때", -1)) + dateChange
        assertTrue(AgentFlow.run(msgs.reversed(), okGemini).slot("일시").startsWith("2026-10-04"))
    }

    @Test
    fun `S3-2 경계 - 같은 시각의 날짜 변경 두 건은 입력 순서와 무관하게 같은 결과`() {
        val a = msgAt("김민수", "토요일로 하자", 5_000); val b = msgAt("이지영", "일요일로 하자", 5_000)
        assertEquals(AgentFlow.run(listOf(a, b), okGemini).slot("일시"), AgentFlow.run(listOf(b, a), okGemini).slot("일시"))
    }

    // ── S3-5 동시 발화 ───────────────────────────────────────────────────

    private val tied = listOf(
        msgAt("김민수", "토요일 강남 가자", 7_000),
        msgAt("이지영", "토요일 홍대 가자", 7_000),
        msgAt("박서준", "토요일 성수 가자", 7_000),
    )

    @Test
    fun `S3-5 비정상 - 세 명이 같은 시각에 다른 지역을 말해도 모든 순열에서 같은 결과`() {
        val results = listOf(tied, tied.reversed(), listOf(tied[1], tied[2], tied[0]), listOf(tied[2], tied[0], tied[1]))
            .map { AgentFlow.run(it, okGemini).slot("지역") }.toSet()
        assertEquals(results.toString(), 1, results.size)
    }

    @Test
    fun `S3-5 정상 - 동률은 문서 id 순으로 정해 화면 순서와 같다`() {
        // Firestore orderBy(timestamp)도 동률을 문서 id로 가른다 — "이지영@7000" < "박서준@7000"? 유니코드 순
        val expectedLast = tied.maxByOrNull { it.id }!!.content
        val r = AgentFlow.run(tied.reversed(), okGemini)
        assertTrue(expectedLast.contains(r.slot("지역")))
    }

    @Test
    fun `S3-5 정상 - 동시 발화 3건 모두 요약 입력에 들어간다(누락·덮어쓰기 없음)`() {
        val r = AgentFlow.run(tied, okGemini)
        assertEquals(tied.map { it.id }.toSet(), r.llm.received.single().map { it.id }.toSet())
        assertEquals("3명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S3-5 경계 - 동시 발화 뒤 나중에 확정한 말이 이긴다`() {
        val msgs = tied + msgAt("최유나", "그냥 망원으로 확정", 8_000)
        assertEquals("망원", AgentFlow.run(msgs.reversed(), okGemini).slot("지역"))
    }

    @Test
    fun `S3-5 이상 - 같은 id가 두 번 들어와도(중복 수신) 결과가 같다`() {
        val dup = areaChange + areaChange[2]
        assertEquals("강남", AgentFlow.run(dup.reversed(), okGemini).slot("지역"))
    }
}
