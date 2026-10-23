package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.local.UserStatusDao
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.repository.UserStatusRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 나간 사람 반영 결함(DEFECT_TEST_2026-10 S2) — 멤버 범위([MemberScope])와 프로필 재구성
 * ([StatusCompressionPipeline.rebuild]·compress)을 Gemma 없이 검증한다.
 */
class MemberScopeTest {

    private fun m(uid: String, text: String, t: Long, name: String = uid) =
        Message(id = "$uid@$t", roomId = "r", senderId = uid, senderName = name, content = text, timestamp = t)

    /** 결정론 압축기: 메시지 문장의 키워드로 선호를 뽑는다(누가 말했는지는 입력에 들어온 메시지로만 결정) */
    private class KeywordLlm(private val failOn: (List<Message>) -> Boolean = { false }) : OnDeviceLlmPort {
        val calls: MutableList<List<Message>> = CopyOnWriteArrayList()
        override suspend fun compress(messages: List<Message>): String {
            calls += messages
            if (failOn(messages)) throw IllegalStateException("Gemma 실패")
            val prefs = mutableListOf<String>()
            messages.forEach { msg ->
                val c = msg.content
                if ("술집" in c && "싫" in c) prefs += "싫어요: 술집"
                if ("회" in c && "좋" in c) prefs += "좋아요: 회"
                if ("조용" in c) prefs += "좋아요: 조용한 곳"
                if ("고기" in c) prefs += "좋아요: 고기"
            }
            return JSONObject()
                .put("participants", JSONArray(messages.map { it.senderName }.distinct()))
                .put("preferences", JSONArray(prefs.distinct()))
                .put("availability", JSONArray())
                .toString()
        }
        override suspend fun summarizeForPrivacy(messages: List<Message>): String = ""
    }

    private class MemDao(var row: UserStatusEntity? = null) : UserStatusDao {
        override suspend fun upsert(entity: UserStatusEntity) { row = entity }
        override suspend fun getByRoomId(roomId: String): UserStatusEntity? = row
        override suspend fun deleteByRoomId(roomId: String) { row = null }
    }

    private fun pipeline(llm: OnDeviceLlmPort, dao: MemDao) = StatusCompressionPipeline(llm, UserStatusRepository(dao))

    /** 5명(a~e) 대화 — e가 회·술집 취향을 말하고 나감. a는 조용한 곳, b는 고기. */
    private val five = listOf(
        m("e", "난 회가 좋아. 술집은 싫어", 1),
        m("a", "조용한 데 가자", 2),
        m("b", "고기 먹자", 3),
        m("c", "토요일 콜", 4),
        m("d", "강남 좋아", 5),
        m("e", "나 빠질게", 6),
    )
    private val remaining = setOf("a", "b", "c", "d")

    // ══ 멤버 범위(scope) ═══════════════════════════════════════════════════

    @Test
    fun `범위 - 5명 중 1명 이탈하면 그 사람 메시지만 빠지고 나머지 순서·내용 유지`() {
        val out = MemberScope.scope(five, remaining)
        assertEquals(listOf("a@2", "b@3", "c@4", "d@5"), out.map { it.id })
        assertEquals(five.filter { it.senderId != "e" }, out)
    }

    @Test
    fun `범위 - 멤버 정보가 없으면(null) 아무것도 거르지 않는다`() {
        assertEquals(five, MemberScope.scope(five, null))
    }

    @Test
    fun `범위 - 전원 이탈(빈 멤버)이면 남는 메시지가 없다`() {
        assertTrue(MemberScope.scope(five, emptySet()).isEmpty())
    }

    @Test
    fun `범위 - 동명이인은 uid로 구분(같은 이름의 나간 사람만 빠짐)`() {
        val msgs = listOf(m("u1", "고기 먹자", 1, name = "민수"), m("u2", "회 좋아", 2, name = "민수"))
        assertEquals(listOf("u1@1"), MemberScope.scope(msgs, setOf("u1")).map { it.id })
    }

    @Test
    fun `맥락 - 나간 사람 말에 남은 사람이 '나도 싫어'로 동조하면 인용으로 보존`() {
        val msgs = listOf(m("e", "술집은 싫어", 1), m("a", "나도 싫어", 2))
        val out = MemberScope.scope(msgs, setOf("a")).single()
        assertEquals("나도 싫어 (앞 사람 말: 술집은 싫어)", out.content)
        assertEquals("a", out.senderId)
    }

    @Test
    fun `맥락 - 동조가 아닌 다음 말·긴 문장·멤버의 앞 말에는 인용을 붙이지 않는다`() {
        val msgs = listOf(
            m("e", "술집은 싫어", 1), m("a", "토요일 저녁 어때", 2),
            m("e", "회 좋아", 3), m("b", "나도 그건 좀 생각해 봐야 할 것 같은데 일단 보류", 4),
            m("c", "조용한 데", 5), m("d", "나도", 6),
        )
        val out = MemberScope.scope(msgs, setOf("a", "b", "c", "d"))
        assertTrue(out.none { it.content.contains(MemberScope.QUOTE_PREFIX) })
    }

    @Test
    fun `맥락 - 리스트 순서가 아니라 시각 순으로 앞 말을 찾는다`() {
        val msgs = listOf(m("a", "ㅇㅇ 나도", 20), m("e", "술집은 싫어", 10))
        assertTrue(MemberScope.scope(msgs, setOf("a")).single().content.endsWith("(앞 사람 말: 술집은 싫어)"))
    }

    // ══ 프로필 사용 가능 여부 ═══════════════════════════════════════════════

    private val senders = five.map { it.senderId }.toSet()

    @Test
    fun `프로필 - 출처 멤버가 모두 남아 있으면 사용, 하나라도 나갔으면 차단`() {
        assertTrue(MemberScope.profileUsable(UserStatusEntity("r", sourceMemberIds = listOf("a", "b")), remaining, senders))
        assertFalse(MemberScope.profileUsable(UserStatusEntity("r", sourceMemberIds = listOf("a", "e")), remaining, senders))
    }

    @Test
    fun `프로필 - 출처 미기록(v7) 프로필은 아무도 안 나갔을 때만 사용`() {
        val legacy = UserStatusEntity("r", preferences = listOf("싫어요: 술집"))
        assertFalse(MemberScope.profileUsable(legacy, remaining, senders))
        assertTrue(MemberScope.profileUsable(legacy, senders, senders))
    }

    @Test
    fun `프로필 - 멤버 정보 없음·프로필 없음은 판단 보류(사용)`() {
        assertTrue(MemberScope.profileUsable(UserStatusEntity("r", sourceMemberIds = listOf("e")), null, senders))
        assertTrue(MemberScope.profileUsable(null, remaining, senders))
    }

    @Test
    fun `재구성 필요 - 이탈·재입장은 필요, 말 안 한 새 멤버·프로필 없음은 불필요`() {
        val stamped = UserStatusEntity("r", sourceMemberIds = listOf("a", "b", "c", "d"))
        assertTrue("이탈", MemberScope.needsRebuild(UserStatusEntity("r", sourceMemberIds = listOf("a", "e")), remaining, senders))
        assertTrue("재입장(e가 돌아옴, 예전 발언 있음)", MemberScope.needsRebuild(stamped, remaining + "e", senders))
        assertFalse("새 멤버 f(발언 없음)", MemberScope.needsRebuild(stamped, remaining + "f", senders))
        assertFalse(MemberScope.needsRebuild(null, remaining, senders))
    }

    // ══ 재구성(rebuild) ═══════════════════════════════════════════════════

    @Test
    fun `재구성 - 5명 중 1명 이탈하면 그 사람 선호·불호·이름이 빠지고 남은 사람 선호는 유지`() = runBlocking {
        val dao = MemDao(UserStatusEntity("r", participants = listOf("a", "e"), preferences = listOf("싫어요: 술집", "좋아요: 회")))
        val result = pipeline(KeywordLlm(), dao).rebuild("r", five, remaining)
        assertEquals(StatusCompressionPipeline.RebuildResult.REBUILT, result)
        val p = dao.row!!
        assertEquals(setOf("좋아요: 조용한 곳", "좋아요: 고기"), p.preferences.toSet())
        assertFalse(p.participants.contains("e"))
        assertEquals(listOf("a", "b", "c", "d"), p.sourceMemberIds)
    }

    @Test
    fun `재구성 - 나간 사람만 불호를 말했으면 불호가 사라진다`() = runBlocking {
        val dao = MemDao()
        pipeline(KeywordLlm(), dao).rebuild("r", listOf(m("e", "술집은 싫어", 1), m("a", "고기 먹자", 2)), setOf("a"))
        assertFalse(dao.row!!.preferences.contains("싫어요: 술집"))
    }

    @Test
    fun `재구성 - 남은 사람이 동조한 불호는 맥락 인용으로 유지된다`() = runBlocking {
        val dao = MemDao()
        pipeline(KeywordLlm(), dao).rebuild("r", listOf(m("e", "술집은 싫어", 1), m("a", "나도 싫어", 2)), setOf("a"))
        assertTrue(dao.row!!.preferences.contains("싫어요: 술집"))
    }

    @Test
    fun `재구성 - 2명 중 1명 이탈하면 남은 1명 발언만`() = runBlocking {
        val dao = MemDao()
        val llm = KeywordLlm()
        pipeline(llm, dao).rebuild("r", listOf(m("a", "조용한 곳", 1), m("b", "회 좋아", 2)), setOf("a"))
        assertEquals(listOf("좋아요: 조용한 곳"), dao.row!!.preferences)
        assertTrue(llm.calls.flatten().all { it.senderId == "a" })
    }

    @Test
    fun `재구성 - 전원 이탈(남은 멤버 발언 없음)이면 Gemma 호출 없이 빈 프로필`() = runBlocking {
        val dao = MemDao(UserStatusEntity("r", preferences = listOf("싫어요: 술집")))
        val llm = KeywordLlm()
        val result = pipeline(llm, dao).rebuild("r", five, setOf("z"))
        assertEquals(StatusCompressionPipeline.RebuildResult.EMPTY, result)
        assertTrue(dao.row!!.preferences.isEmpty())
        assertTrue(llm.calls.isEmpty())
    }

    @Test
    fun `재구성 - 다시 들어온 사람의 예전 발언도 포함(입장 시각 기록이 없으므로)`() = runBlocking {
        val dao = MemDao()
        pipeline(KeywordLlm(), dao).rebuild("r", five, remaining + "e")
        assertTrue(dao.row!!.preferences.containsAll(listOf("좋아요: 회", "싫어요: 술집")))
    }

    @Test
    fun `재구성 실패 - Gemma 전부 실패하면 저장된 프로필을 건드리지 않는다(사용은 차단 상태 유지)`() = runBlocking {
        val stale = UserStatusEntity("r", preferences = listOf("싫어요: 술집"))
        val dao = MemDao(stale)
        val result = pipeline(KeywordLlm(failOn = { true }), dao).rebuild("r", five, remaining)
        assertEquals(StatusCompressionPipeline.RebuildResult.FAILED, result)
        assertEquals(stale, dao.row)
        assertFalse(MemberScope.profileUsable(dao.row, remaining, senders))
    }

    @Test
    fun `재구성 부분 실패 - 성공한 청크로 만든다`() = runBlocking {
        val msgs = (1..40).map { i -> m(if (i % 2 == 0) "a" else "b", if (i <= 15) "고기 먹자" else "조용한 곳", i.toLong()) }
        val dao = MemDao()
        val result = pipeline(KeywordLlm(failOn = { chunk -> chunk.any { it.content == "고기 먹자" } }), dao).rebuild("r", msgs, setOf("a", "b"))
        assertEquals(StatusCompressionPipeline.RebuildResult.REBUILT, result)
        assertEquals(listOf("좋아요: 조용한 곳"), dao.row!!.preferences)
    }

    @Test
    fun `재구성 상한 - 최근 300개 메시지까지만 본다(더 오래된 발언의 선호는 빠짐)`() = runBlocking {
        val old = m("a", "고기 먹자", 0)
        val recent = (1..330).map { i -> m("a", "ㅋㅋ $i", i.toLong()) }
        val llm = KeywordLlm()
        val dao = MemDao()
        pipeline(llm, dao).rebuild("r", listOf(old) + recent, setOf("a"))
        assertEquals(StatusCompressionPipeline.MAX_REBUILD_CHUNKS, llm.calls.size)
        assertFalse("가장 오래된 발언은 잘린다(알려진 한계)", dao.row!!.preferences.contains("좋아요: 고기"))
    }

    @Test
    fun `재구성 청크 - 겹침으로 경계의 앞 말 맥락을 다시 보여준다`() {
        val p = pipeline(KeywordLlm(), MemDao())
        val chunks = p.rebuildChunks((1..31).map { m("a", "x$it", it.toLong()) })
        assertEquals(3, chunks.size)
        assertEquals("a@14", chunks[1].first().id) // 16번째 메시지부터 + 겹침 2
        assertEquals(31, chunks.flatten().map { it.id }.distinct().size)
    }

    // ══ 증분 압축(compress)과 멤버 ═══════════════════════════════════════════

    @Test
    fun `압축 - 나간 사람 메시지는 Gemma 입력에서 빠지고 출처가 기록된다`() = runBlocking {
        val llm = KeywordLlm()
        val dao = MemDao()
        pipeline(llm, dao).compress("r", five, remaining)
        assertTrue(llm.calls.single().none { it.senderId == "e" })
        assertEquals(listOf("a", "b", "c", "d"), dao.row!!.sourceMemberIds)
    }

    @Test
    fun `압축 - 나간 사람이 섞인 기존 프로필에는 병합하지 않는다`() = runBlocking {
        val dao = MemDao(UserStatusEntity("r", preferences = listOf("싫어요: 술집"), sourceMemberIds = listOf("a", "e")))
        pipeline(KeywordLlm(), dao).compress("r", listOf(m("a", "고기 먹자", 1)), remaining)
        assertEquals(listOf("좋아요: 고기"), dao.row!!.preferences)
    }

    @Test
    fun `압축 - 출처 미기록(v7) 프로필에 병합할 때는 출처를 새로 찍지 않는다(세탁 방지)`() = runBlocking {
        val dao = MemDao(UserStatusEntity("r", preferences = listOf("싫어요: 술집")))
        pipeline(KeywordLlm(), dao).compress("r", listOf(m("a", "고기 먹자", 1)), remaining)
        assertTrue(dao.row!!.sourceMemberIds.isEmpty())
        assertFalse(MemberScope.profileUsable(dao.row, remaining, senders))
    }

    @Test
    fun `압축 - 멤버 메시지가 없으면 아무것도 쓰지 않는다`() = runBlocking {
        val dao = MemDao()
        pipeline(KeywordLlm(), dao).compress("r", listOf(m("e", "회 좋아", 1)), remaining)
        assertNull(dao.row)
    }
}
