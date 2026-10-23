package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.pipeline.MemberScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 나간 사람 반영 결함(DEFECT_TEST_2026-10 S2) — 오케스트레이터 전체 흐름.
 * 인원·요약 입력·선호·불호·지역·재시도·클라우드 전송까지 현재 멤버 기준인지 본다.
 */
class MemberLeaveFlowTest {

    private val okGemini get() = ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당")))

    private val stay = setOf("김민수", "이지영", "박서준", "최유나")
    private val leaver = "정하늘"

    private val five = listOf(
        msg(leaver, "난 회가 좋아. 술집은 싫어", 1),
        msg("김민수", "토요일 강남에서 저녁 먹자", 2),
        msg("이지영", "좋아 조용한 데로", 3),
        msg(leaver, "나 해운대 가야 돼서 빠질게", 4),
        msg("박서준", "그럼 우리끼리", 5),
        msg("최유나", "강남 저녁 ㄱㄱ", 6),
    )

    /** 남은 멤버로 재구성된 프로필(출처 기록 있음) */
    private val rebuilt = UserStatusEntity(
        "r", participants = stay.toList(), preferences = listOf("좋아요: 조용한 곳"), sourceMemberIds = stay.sorted(),
    )
    /** 퇴장 전 프로필(출처 미기록, 나간 사람 취향 포함) */
    private val legacy = UserStatusEntity(
        "r", participants = stay.toList() + leaver, preferences = listOf("좋아요: 회", "싫어요: 술집", "좋아요: 조용한 곳"),
    )

    private fun scoped(r: FlowRun) = r.events.filterIsInstance<AssistantEvent.MembersScoped>().single()

    // ── S2-1 인원 ─────────────────────────────────────────────────────────

    @Test
    fun `인원 - 5명 중 1명 이탈하면 4명`() {
        assertEquals("4명 (대화 참여자 기준)", AgentFlow.run(five, okGemini, memberIds = stay).slot("인원"))
    }

    @Test
    fun `인원 - 2명 중 1명 이탈하면 1명 남아 인원은 지어내지 않는다(미정)`() {
        val msgs = listOf(msg("김민수", "토요일 강남 저녁", 1), msg("이지영", "나 빠질게", 2))
        val r = AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수"))
        assertEquals("미정", r.slot("인원"))
        assertTrue(r.success != null)
    }

    @Test
    fun `인원 - 나갔다 다시 들어온 사람은 다시 센다`() {
        assertEquals("5명 (대화 참여자 기준)", AgentFlow.run(five, okGemini, memberIds = stay + leaver).slot("인원"))
    }

    @Test
    fun `인원 - 동명이인 중 한 명만 나가면 남은 한 명은 센다`() {
        val msgs = listOf(
            msg("민수", "토요일 저녁", 1, senderId = "u1"), msg("민수", "나 빠질게", 2, senderId = "u2"),
            msg("지영", "강남 콜", 3, senderId = "u3"),
        )
        val r = AgentFlow.run(msgs, okGemini, memberIds = setOf("u1", "u3"))
        assertEquals("2명 (대화 참여자 기준)", r.slot("인원"))
        assertEquals(listOf("u1", "u3"), r.llm.received.single().map { it.senderId })
    }

    @Test
    fun `인원 - 멤버 정보가 없으면 이전 동작(전원 반영)`() {
        assertEquals("5명 (대화 참여자 기준)", AgentFlow.run(five, okGemini).slot("인원"))
    }

    // ── S2-2 선호 / 요약 ─────────────────────────────────────────────────

    @Test
    fun `선호 - 재구성된 프로필(남은 멤버)의 선호는 쓰고 나간 사람 선호는 없다`() {
        val r = AgentFlow.run(five, okGemini, userStatus = rebuilt, memberIds = stay)
        assertTrue(r.slot("선호").contains("조용한 곳"))
        assertFalse(r.slot("선호").contains("회"))
        assertTrue(scoped(r).profileUsed)
    }

    @Test
    fun `선호 - 재구성 전(나간 사람이 섞인 프로필)은 선호·불호를 쓰지 않는다(즉시 차단)`() {
        val r = AgentFlow.run(five, okGemini, userStatus = legacy, memberIds = stay)
        assertEquals("미정", r.slot("선호"))
        assertEquals("미정", r.slot("제약(배제 조건)"))
        assertFalse(scoped(r).profileUsed)
    }

    @Test
    fun `요약 - 나간 사람 메시지는 온디바이스 요약 입력에서 빠지고 다른 사람 발언은 전부 유지`() {
        val r = AgentFlow.run(five, okGemini, memberIds = stay)
        val received = r.llm.received.single()
        assertEquals(five.filter { it.senderName != leaver }.map { it.id }, received.map { it.id })
        assertEquals(2, scoped(r).droppedMessages)
        assertEquals(1, scoped(r).departedSenders)
    }

    @Test
    fun `요약 - 나간 사람 말에 동조한 남은 사람의 맥락은 인용으로 남는다`() {
        val msgs = listOf(msg(leaver, "술집은 싫어", 1), msg("김민수", "나도 싫어", 2), msg("이지영", "토요일 강남", 3))
        val r = AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수", "이지영"))
        assertTrue(r.llm.received.single().first().content.contains("술집은 싫어"))
    }

    @Test
    fun `요약 - 나간 사람이 섞이지 않은 출처 미기록 프로필은 그대로 쓴다(멤버 변화 없음)`() {
        val noLeave = five.filter { it.senderName != leaver }
        val r = AgentFlow.run(noLeave, okGemini, userStatus = legacy.copy(preferences = listOf("좋아요: 조용한 곳")), memberIds = stay)
        assertTrue(r.slot("선호").contains("조용한 곳"))
    }

    // ── S2-3 불호·재시도 ─────────────────────────────────────────────────

    @Test
    fun `불호 - 나간 사람만 말한 불호(술집)로 재시도하지 않는다`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("달빛술집 — 분위기 좋은 술집", "소담식당")))
        val r = AgentFlow.run(five, gemini, userStatus = legacy, memberIds = stay)
        assertEquals(1, r.success!!.attempts)
    }

    @Test
    fun `불호 - 남은 멤버의 불호는 그대로 지킨다(재구성 프로필)`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("달빛술집 — 분위기 좋은 술집")), Gem.final(listOf("소담식당")))
        val status = rebuilt.copy(preferences = listOf("싫어요: 술집"))
        val r = AgentFlow.run(five, gemini, userStatus = status, memberIds = stay)
        assertEquals(2, r.success!!.attempts)
    }

    @Test
    fun `불호 - 나간 사람의 불호는 정형 블록(클라우드 전송분)에도 없다`() {
        val r = AgentFlow.run(five, okGemini, userStatus = legacy, memberIds = stay)
        assertFalse(r.gemini.allSentText().contains("싫어요: 술집"))
        assertFalse(r.frame.contains("술집"))
    }

    @Test
    fun `불호 - 재입장한 사람의 불호는 다시 반영(재구성 프로필에 포함된 경우)`() {
        val status = rebuilt.copy(preferences = listOf("싫어요: 술집"), sourceMemberIds = (stay + leaver).sorted())
        val r = AgentFlow.run(five, okGemini, userStatus = status, memberIds = stay + leaver)
        assertTrue(r.slot("제약(배제 조건)").contains("술집"))
    }

    @Test
    fun `불호 - 동명이인 중 나간 사람의 프로필 출처가 남아 있으면 차단`() {
        val status = UserStatusEntity("r", preferences = listOf("싫어요: 술집"), sourceMemberIds = listOf("u1", "u2"))
        val msgs = listOf(msg("민수", "토요일 저녁", 1, senderId = "u1"), msg("지영", "콜", 2, senderId = "u3"))
        val r = AgentFlow.run(msgs, okGemini, userStatus = status, memberIds = setOf("u1", "u3"))
        assertEquals("미정", r.slot("제약(배제 조건)"))
    }

    // ── S2-6 지역 · 전원 이탈 · 프라이버시 ────────────────────────────────

    @Test
    fun `지역 - 나간 사람의 마지막 지역 언급(해운대)이 합의 지역(강남)을 덮지 않는다`() {
        assertEquals("강남", AgentFlow.run(five, okGemini, memberIds = stay).slot("지역"))
    }

    @Test
    fun `지역 - 나간 사람만 지역을 말했으면 지역은 미정`() {
        val msgs = listOf(msg(leaver, "해운대 어때", 1), msg("김민수", "토요일 저녁", 2), msg("이지영", "콜", 3))
        assertEquals("미정", AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수", "이지영")).slot("지역"))
    }

    @Test
    fun `지역 - 재입장한 사람의 지역 언급은 반영`() {
        val msgs = listOf(msg("김민수", "토요일 강남 어때", 1), msg(leaver, "해운대 어때", 2))
        assertEquals("해운대", AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수", leaver)).slot("지역"))
        assertEquals("강남", AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수")).slot("지역"))
    }

    @Test
    fun `전원 이탈 - 남은 멤버 대화가 없으면 클라우드 호출 없이 실패`() {
        val r = AgentFlow.run(five, okGemini, memberIds = setOf("새멤버"))
        assertTrue(r.result is OrchestratorResult.Failed)
        assertEquals(0, r.gemini.requests.size)
        assertTrue(r.llm.received.isEmpty())
    }

    @Test
    fun `프라이버시 - 나간 사람 이름은 마스킹 명단에 남아 요약에 등장해도 지워진다`() {
        val leaky = FakeOnDeviceLlm { "정하늘은 빠지고 나머지가 토요일 강남에서 저녁을 먹는다." }
        val r = AgentFlow.run(five, okGemini, memberIds = stay, llm = leaky)
        assertFalse(r.gemini.allSentText().contains("하늘"))
    }

    @Test
    fun `프라이버시 - 나간 사람 메시지 원문은 클라우드로 가지 않는다`() {
        val r = AgentFlow.run(five, okGemini, memberIds = stay)
        val sent = r.gemini.allSentText()
        assertFalse(sent.contains("해운대 가야 돼서"))
        assertFalse(sent.contains("회가 좋아"))
    }

    @Test
    fun `재구성 판단 - 흐름 테스트용 프로필 출처 규칙 확인`() {
        val senders = five.map { it.senderId }.toSet()
        assertTrue(MemberScope.needsRebuild(legacy, stay, senders))
        assertFalse(MemberScope.needsRebuild(rebuilt, stay, senders))
    }
}
