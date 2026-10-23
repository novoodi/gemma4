package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 메시지 순서·동시 입력 결함(DEFECT_TEST_2026-10 S3) — 정본 순서([MessageOrder])와
 * 증분 압축 스케줄([CompressionPlanner])을 기기·Gemma 없이 검증한다.
 */
class MessageOrderTest {

    private fun m(id: String, t: Long, text: String = id, pending: Boolean = false) =
        Message(id = id, roomId = "r", senderId = "u-$id", senderName = id, content = text, timestamp = t, pending = pending)

    private fun ids(list: List<Message>) = list.map { it.id }

    // ══ 정본 순서 ══════════════════════════════════════════════════════════

    @Test
    fun `정렬 - 리스트 순서와 무관하게 서버 시각 순`() {
        val msgs = listOf(m("c", 300), m("a", 100), m("b", 200))
        assertEquals(listOf("a", "b", "c"), ids(MessageOrder.canonical(msgs)))
    }

    @Test
    fun `정렬 - 같은 시각은 문서 id 순(입력 순서가 달라도 같은 결과)`() {
        val a = m("doc-A", 500); val b = m("doc-B", 500); val c = m("doc-C", 500)
        val expected = listOf("doc-A", "doc-B", "doc-C")
        listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, c, a), listOf(c, a, b)).forEach {
            assertEquals(expected, ids(MessageOrder.canonical(it)))
        }
    }

    @Test
    fun `정렬 - 시각이 없거나 이상한(0·음수) 메시지는 가장 오래된 것으로`() {
        val msgs = listOf(m("new", 200), m("zero", 0), m("neg", -5), m("old", 100))
        assertEquals(listOf("neg", "zero", "old", "new"), ids(MessageOrder.canonical(msgs)))
    }

    @Test
    fun `정렬 - 서버 미확정(pending) 내 메시지는 기기 시계가 늦어도 맨 뒤`() {
        val msgs = listOf(m("mine", 50, pending = true), m("other", 100), m("other2", 200))
        assertEquals(listOf("other", "other2", "mine"), ids(MessageOrder.canonical(msgs)))
    }

    @Test
    fun `정렬 - 늦게 도착한 옛 메시지는 시각 자리로 들어간다`() {
        val arrived = listOf(m("t1", 100), m("t3", 300), m("t4", 400), m("t2-late", 200))
        assertEquals(listOf("t1", "t2-late", "t3", "t4"), ids(MessageOrder.canonical(arrived)))
    }

    @Test
    fun `정렬 - 최대 시각·빈 목록·한 건도 안전`() {
        assertEquals(listOf("a", "max"), ids(MessageOrder.canonical(listOf(m("max", Long.MAX_VALUE), m("a", 1)))))
        assertTrue(MessageOrder.canonical(emptyList()).isEmpty())
        assertEquals(listOf("x"), ids(MessageOrder.canonical(listOf(m("x", 0)))))
    }

    @Test
    fun `맥락 인용 - 리스트가 섞여도 바로 앞 말은 시각 기준`() {
        val out = MemberScope.scope(listOf(m("b", 20, "나도"), m("a", 10, "술집은 싫어")), setOf("u-b"))
        assertTrue(out.single().content.endsWith("(앞 사람 말: 술집은 싫어)"))
    }

    // ══ 증분 압축 스케줄 — 동시 입력 ═════════════════════════════════════════

    private fun conv(n: Int, from: Int = 1) = (from until from + n).map { m("m%03d".format(it), it * 10L) }

    @Test
    fun `압축 - 아직 안 된 메시지가 10개 미만이면 기다린다, 10개면 압축`() {
        assertNull(CompressionPlanner.plan(conv(9), emptySet()))
        val plan = CompressionPlanner.plan(conv(10), emptySet())!!
        assertEquals(10, plan.coveredIds.size)
    }

    @Test
    fun `압축 - 누가 보냈든 개수만 본다(내 전송 시점이 10의 배수가 아니어도)`() {
        // 예전: 내가 보낸 순간 총 개수가 정확히 10의 배수여야만 압축 → 11·12·13…에서는 영영 안 됨
        val done = conv(9).map { it.id }.toSet()
        assertNull(CompressionPlanner.plan(conv(18), done)) // 남은 9개
        assertEquals(10, CompressionPlanner.plan(conv(19), done)!!.coveredIds.size)
    }

    @Test
    fun `압축 - 사이에 15개를 넘게 쌓여도 전부 압축(창 여러 개)`() {
        val plan = CompressionPlanner.plan(conv(40), emptySet())!!
        assertEquals(40, plan.coveredIds.size)
        assertEquals(3, plan.batches.size)
        assertEquals(40, plan.batches.flatten().map { it.id }.distinct().size)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `압축 도중 도착 - 이번 회차에 없던 메시지는 다음 회차에 빠짐없이 잡힌다`() {
        val first = conv(10)
        val plan1 = CompressionPlanner.plan(first, emptySet())!!
        // 압축하는 동안 새 메시지 10개 도착(같은 시각 동시 입력 포함)
        val during = conv(10, from = 11) + m("same-a", 200) + m("same-b", 200)
        val done = plan1.coveredIds
        val plan2 = CompressionPlanner.plan(first + during, done)!!
        assertEquals((during.map { it.id }).toSet(), plan2.coveredIds)
        assertTrue((plan1.coveredIds intersect plan2.coveredIds).isEmpty())
    }

    @Test
    fun `늦게 도착한 옛 메시지 - 이미 지난 시각이어도 id로 잡힌다(시각 워터마크였다면 누락)`() {
        val done = conv(20).map { it.id }.toSet()
        val late = m("late-old", 15) // 압축 범위 한가운데 시각
        val more = conv(9, from = 21)
        val plan = CompressionPlanner.plan(conv(20) + more + late, done)!!
        assertTrue(plan.coveredIds.contains("late-old"))
        // 정본 순서로 들어가 맥락이 자연스럽다
        assertEquals("late-old", plan.batches.single().first { !it.id.startsWith("m00") }.id)
    }

    @Test
    fun `압축 - 각 창 앞에 이미 압축된 앞 말 2개를 맥락으로 붙인다`() {
        val done = conv(10).map { it.id }.toSet()
        val plan = CompressionPlanner.plan(conv(20), done)!!
        assertEquals(listOf("m009", "m010"), plan.batches.single().take(2).map { it.id })
        assertEquals(12, plan.batches.single().size)
    }

    @Test
    fun `압축 상한 - 오래 비운 방은 최근 창 3개만 압축하고 나머지는 건너뜀으로 표시`() {
        val plan = CompressionPlanner.plan(conv(100), emptySet())!!
        assertEquals(CompressionPlanner.MAX_CATCHUP_WINDOWS, plan.batches.size)
        assertEquals(100, plan.coveredIds.size)
        assertEquals(60, plan.skipped) // 창은 앞에서부터 15개씩 — 마지막 창 3개(15+15+10)만 압축
    }

    @Test
    fun `추천 직전 - force면 10개 미만이어도 남은 것을 압축`() {
        val done = conv(10).map { it.id }.toSet()
        assertNull(CompressionPlanner.plan(conv(13), done))
        assertEquals(setOf("m011", "m012", "m013"), CompressionPlanner.plan(conv(13), done, force = true)!!.coveredIds)
        assertNull(CompressionPlanner.plan(conv(10), done, force = true))
    }

    @Test
    fun `화면 재진입 - 프로필 저장 시각 이전 메시지는 압축된 것으로 시작`() {
        val msgs = conv(20) // 시각 10..200
        val done = CompressionPlanner.initiallyCompressed(msgs, profileUpdatedAt = 105)
        assertEquals(conv(10).map { it.id }.toSet(), done)
        assertTrue(CompressionPlanner.initiallyCompressed(msgs, null).isEmpty())
        assertTrue("시각 없는 메시지는 압축됐다고 가정하지 않는다",
            CompressionPlanner.initiallyCompressed(listOf(m("z", 0)), 1_000).isEmpty())
    }

    @Test
    fun `압축 - 서버 미확정 메시지도 id가 고정이라 한 번만 압축된다`() {
        val msgs = conv(9) + m("mine", 0, pending = true)
        val plan = CompressionPlanner.plan(msgs, emptySet())!!
        assertEquals("mine", plan.batches.single().last().id)
        // 확정돼 시각이 바뀌어도 같은 id → 다시 압축하지 않는다
        val confirmed = conv(9) + m("mine", 95)
        assertNull(CompressionPlanner.plan(confirmed, plan.coveredIds, force = true))
    }
}
