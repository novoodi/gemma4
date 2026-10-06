package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusDao
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.pipeline.StatusCompressionPipeline
import com.navoodi.morimi.data.repository.UserStatusRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 결함 탐색 — 시나리오 1(참가자 수) · 2(중간 퇴장) · 3(메시지 순서·동시 발화).
 * 실패한 케이스는 @Ignore("결함: …")로 남겨 둔다(증거). 표: docs/eval/DEFECT_TEST_2026-10.md
 */
class AgentFlowParticipantsDefectTest {

    private val okGemini get() = ScriptedGemini.of(Gem.final(listOf("미미식당", "소담식당")))

    // ═══ 시나리오 1 — 참가자 1명 / 2명 / 5명 ═══════════════════════════════════

    @Test
    fun `S1-1 정상 - 1명 대화도 추천이 나오고 인원은 지어내지 않는다`() {
        val r = AgentFlow.run(listOf(msg("김민수", "토요일 강남에서 저녁 먹을 곳 찾아줘", 1)), okGemini)
        assertNotNull(r.success)
        assertEquals("미정", r.slot("인원"))
        assertFalse(r.gemini.allSentText().contains("민수"))
    }

    @Test
    fun `S1-2 정상 - 2명 대화는 발화자 수로 인원 2명`() {
        val r = AgentFlow.run(
            listOf(msg("김민수", "토요일 강남 저녁 어때", 1), msg("이지영", "좋아 밥 먹자", 2)),
            okGemini,
        )
        assertEquals("2명 (대화 참여자 기준)", r.slot("인원"))
        assertEquals(1, r.success!!.attempts)
    }

    @Test
    fun `S1-3 정상 - 5명 대화, Gemma 요약이 이름을 흘려도 경계에서 전부 마스킹`() {
        val names = listOf("김민수", "이지영", "박서준", "최유나", "정하늘")
        val msgs = names.mapIndexed { i, n -> msg(n, "토요일 강남 저녁 좋아 ${i + 1}", i + 1L) }
        val leaky = FakeOnDeviceLlm { "김민수, 이지영, 박서준, 최유나, 정하늘이 토요일 강남에서 저녁을 먹기로 했다." }
        val r = AgentFlow.run(msgs, okGemini, llm = leaky)
        assertEquals("5명 (대화 참여자 기준)", r.slot("인원"))
        val sent = r.gemini.allSentText()
        names.forEach { assertFalse("$it 누출", sent.contains(it) || sent.contains(it.substring(1))) }
    }

    @Test
    fun `S1-4 경계 - 5명이 말하지만 명시된 인원 4명이 우선`() {
        val msgs = listOf("김민수", "이지영", "박서준", "최유나").mapIndexed { i, n -> msg(n, "토요일 저녁 ㄱㄱ", i + 1L) } +
            msg("정하늘", "난 못 가, 우리 4명이서 가", 9)
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("4명", r.slot("인원"))
    }

    @Test
    fun `S1-5 이상 - 동명이인(다른 senderId)은 서로 다른 사람으로 센다`() {
        val msgs = listOf(
            msg("민수", "토요일 저녁 어때", 1, senderId = "uid-1"),
            msg("민수", "나도 좋아", 2, senderId = "uid-2"),
            msg("지영", "강남 콜", 3, senderId = "uid-3"),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("3명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S1-6 이상 - 이름이 빈 발화자는 인원에서 빠지고 흐름은 계속된다`() {
        val msgs = listOf(
            msg("", "토요일 강남", 1, senderId = "ghost-1"),
            msg("   ", "저녁 먹자", 2, senderId = "ghost-2"),
            msg("김민수", "ㅇㅋ", 3),
            msg("이지영", "좋아", 4),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertNotNull(r.success)
        assertEquals("2명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S1-7 근접 오류 - 프로필에 있는 비발화 참가자 이름은 마스킹된다`() {
        val msgs = listOf(msg("김민수", "토요일 강남 저녁, 지훈이도 온대", 1), msg("이지영", "좋아", 2))
        val leaky = FakeOnDeviceLlm { "지훈이도 함께 토요일 강남에서 저녁을 먹는다." }
        val status = UserStatusEntity(AgentFlow.ROOM, participants = listOf("김민수", "이지영", "한지훈"))
        val r = AgentFlow.run(msgs, okGemini, userStatus = status, llm = leaky)
        assertFalse(r.gemini.allSentText().contains("지훈"))
    }

    @Test
    fun `S1-8 근접 오류 - 프로필 압축 전(userStatus 없음) 대화 속 제3자 이름도 마스킹돼야 한다`() {
        val msgs = listOf(msg("김민수", "토요일 강남 저녁, 지훈이도 온대", 1), msg("이지영", "좋아", 2))
        val leaky = FakeOnDeviceLlm { "지훈이도 함께 토요일 강남에서 저녁을 먹는다." }
        val r = AgentFlow.run(msgs, okGemini, userStatus = null, llm = leaky)
        assertFalse("비발화 참가자 이름이 Gemini로 전송됨", r.gemini.allSentText().contains("지훈"))
    }

    // ═══ 시나리오 2 — 5명 중 1명(정하늘)이 중간에 나감 ═════════════════════════

    private val stay = listOf("김민수", "이지영", "박서준", "최유나")
    private val stayIds = stay.toSet() // msg()의 senderId = 이름
    private val leaver = "정하늘"

    /** 정하늘이 회·술집 취향을 말한 뒤 퇴장. 남은 4명은 토요일 강남 저녁으로 합의. */
    private fun leaveConversation(leaverLast: String = "나 이번엔 빠질게, 방 나간다"): List<Message> = listOf(
        msg(leaver, "난 회가 좋아. 술집은 싫어", 1),
        msg("김민수", "토요일 강남에서 저녁 먹자", 2),
        msg("이지영", "좋아", 3),
        msg(leaver, leaverLast, 4),
        msg("박서준", "그럼 우리끼리 가자", 5),
        msg("최유나", "강남 저녁 ㄱㄱ", 6),
    )

    /** 퇴장 전 압축으로 누적된 프로필 — participants·preferences 모두 합집합이라 정하늘 몫이 남아 있다 */
    private val profileWithLeaver = UserStatusEntity(
        roomId = AgentFlow.ROOM,
        participants = stay + leaver,
        preferences = listOf("좋아요: 회", "싫어요: 술집", "좋아요: 조용한 곳"),
    )

    @Test
    fun `S2-1 비정상 - 퇴장 후 인원은 남은 4명이어야 한다`() {
        val r = AgentFlow.run(leaveConversation(), okGemini, userStatus = profileWithLeaver, memberIds = stayIds)
        assertEquals("4명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S2-2 비정상 - 나간 사람의 선호(회)는 선호 슬롯에서 빠져야 한다`() {
        val r = AgentFlow.run(leaveConversation(), okGemini, userStatus = profileWithLeaver, memberIds = stayIds)
        assertFalse(r.slot("선호").contains("회"))
    }

    @Test
    fun `S2-3 비정상 - 나간 사람의 불호(술집) 때문에 재시도가 일어나면 안 된다`() {
        val gemini = ScriptedGemini.of(Gem.final(listOf("달빛술집 — 분위기 좋은 술집", "소담식당")))
        val r = AgentFlow.run(leaveConversation(), gemini, userStatus = profileWithLeaver, memberIds = stayIds)
        assertEquals(1, r.success!!.attempts)
    }

    @Test
    fun `S2-4 비정상 - 퇴장 후 프로필은 남은 멤버 대화로 재구성돼 퇴장자 이름·선호가 빠진다`() = runBlocking {
        // 설계: 델타 병합(mergeStatus)은 퇴장을 알 수 없다 → 멤버 변화 시 rebuild가 남은 멤버 대화로 다시 만든다
        val dao = object : UserStatusDao {
            var row: UserStatusEntity? = profileWithLeaver
            override suspend fun upsert(entity: UserStatusEntity) { row = entity }
            override suspend fun getByRoomId(roomId: String): UserStatusEntity? = row
            override suspend fun deleteByRoomId(roomId: String) { row = null }
        }
        val pipeline = StatusCompressionPipeline(FakeOnDeviceLlm(), UserStatusRepository(dao))
        pipeline.rebuild(AgentFlow.ROOM, leaveConversation(), stayIds)
        val rebuilt = dao.row!!
        assertFalse(rebuilt.participants.contains(leaver))
        assertEquals(stay.sorted(), rebuilt.sourceMemberIds)
    }

    @Test
    fun `S2-5 정상 - 나간 사람 이름도 여전히 경계에서 마스킹된다`() {
        val leaky = FakeOnDeviceLlm { "정하늘은 빠지고 나머지가 토요일 강남에서 저녁을 먹는다." }
        val r = AgentFlow.run(leaveConversation(), okGemini, userStatus = profileWithLeaver, llm = leaky, memberIds = stayIds)
        assertFalse(r.gemini.allSentText().contains("하늘"))
    }

    @Test
    fun `S2-6 경계 - 나간 사람이 마지막에 언급한 지역이 모임 지역을 덮으면 안 된다`() {
        val msgs = listOf(
            msg("김민수", "토요일 강남에서 저녁 먹자", 1),
            msg("이지영", "강남 좋아", 2),
            msg(leaver, "나 그날 해운대 가야 돼서 빠질게", 3),
        )
        val r = AgentFlow.run(msgs, okGemini, memberIds = setOf("김민수", "이지영"))
        assertEquals("강남", r.slot("지역"))
    }

    @Test
    fun `S2-7 정상 - 남은 사람들의 합의(토요일)는 그대로 반영된다`() {
        val r = AgentFlow.run(leaveConversation(), okGemini, userStatus = profileWithLeaver)
        assertTrue(r.slot("일시").startsWith("2026-10-03"))
        assertEquals("강남", r.slot("지역"))
    }

    // ═══ 시나리오 3 — 순서 뒤섞임 · 동시 발화 ════════════════════════════════

    @Test
    fun `S3-1 비정상 - 리스트 순서가 뒤섞여도 시각상 마지막 지역 변경이 반영된다`() {
        val inOrder = listOf(
            msg("김민수", "토요일 홍대에서 저녁 먹자", 1_000),
            msg("이지영", "아 홍대 말고 강남으로 하자", 2_000),
        )
        val r = AgentFlow.run(inOrder.reversed(), okGemini)
        assertEquals("강남", r.slot("지역"))
    }

    @Test
    fun `S3-2 비정상 - 리스트 순서가 뒤섞여도 시각상 마지막 날짜 변경이 반영된다`() {
        val inOrder = listOf(
            msg("김민수", "토요일에 보자", 1_000),
            msg("이지영", "일요일로 바꾸자", 2_000),
        )
        val r = AgentFlow.run(inOrder.reversed(), okGemini)
        assertTrue(r.slot("일시"), r.slot("일시").startsWith("2026-10-04"))
    }

    @Test
    fun `S3-3 정상 - 같은 시각 5명 동시 발화도 누락 없이 전달된다`() {
        val names = listOf("김민수", "이지영", "박서준", "최유나", "정하늘")
        val msgs = names.map { msg(it, "토요일 강남 저녁 좋아", 5_000) }
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals(5, r.events.filterIsInstance<AssistantEvent.OrchestrationStarted>().single().messageCount)
        assertEquals(5, r.llm.received.single().size)
        assertEquals("5명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S3-4 근접 오류 - 같은 시각 같은 내용(ㅇㅋ)도 덮어쓰지 않고 모두 전달된다`() {
        val msgs = listOf(
            msg("김민수", "토요일 강남 저녁?", 1),
            msg("이지영", "ㅇㅋ", 2),
            msg("박서준", "ㅇㅋ", 2),
            msg("최유나", "ㅇㅋ", 2),
        )
        val r = AgentFlow.run(msgs, okGemini)
        // 모두 전달된다(누락·덮어쓰기 없음). 순서는 정본 순서 — 같은 시각은 문서 id 순(2026-10 S3)
        assertEquals(com.navoodi.morimi.data.pipeline.MessageOrder.canonical(msgs).map { it.id }, r.llm.received.single().map { it.id })
        assertEquals(4, r.llm.received.single().size)
        assertEquals("4명 (대화 참여자 기준)", r.slot("인원"))
    }

    @Test
    fun `S3-5 경계 - 같은 시각 상충 발화는 입력 순서와 무관하게 같은 결과여야 한다`() {
        val a = msg("김민수", "토요일 강남 가자", 7_000)
        val b = msg("이지영", "토요일 홍대 가자", 7_000)
        val r1 = AgentFlow.run(listOf(a, b), okGemini)
        val r2 = AgentFlow.run(listOf(b, a), okGemini)
        assertEquals(r1.slot("지역"), r2.slot("지역"))
    }

    @Test
    fun `S3-6 이상 - 0·음수·최대 timestamp도 크래시 없이 처리된다`() {
        val msgs = listOf(
            msg("김민수", "토요일 강남 저녁", 0),
            msg("이지영", "좋아", -5),
            msg("박서준", "ㄱㄱ", Long.MAX_VALUE),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertNotNull(r.success)
    }

    @Test
    fun `S3-7 정상 - 정렬된 입력에서 동시 발화 후 확정 메시지가 반영된다`() {
        val msgs = listOf(
            msg("김민수", "토요일 홍대?", 1),
            msg("이지영", "토요일 신촌?", 1),
            msg("박서준", "그냥 강남으로 확정", 2),
        )
        val r = AgentFlow.run(msgs, okGemini)
        assertEquals("강남", r.slot("지역"))
        assertTrue(r.places.all { it.verification == VerificationStatus.VERIFIED })
    }
}
