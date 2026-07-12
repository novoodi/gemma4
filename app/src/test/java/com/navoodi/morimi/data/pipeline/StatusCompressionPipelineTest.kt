package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.local.UserStatusDao
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.repository.UserStatusRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 증분 압축 병합(mergeStatus)과 JSON 추출(extractAndParse) 검증.
 * 심사 지적 핵심 3곳 중 "StatusCompressionPipeline.extractAndParse" + 신규 [A] 병합 로직.
 */
class StatusCompressionPipelineTest {

    // mergeStatus·extractAndParse는 llmPort/repository를 쓰지 않으므로 no-op 페이크로 충분
    private val fakeDao = object : UserStatusDao {
        override suspend fun upsert(entity: UserStatusEntity) {}
        override suspend fun getByRoomId(roomId: String): UserStatusEntity? = null
        override suspend fun deleteByRoomId(roomId: String) {}
    }
    private val fakePort = object : OnDeviceLlmPort {
        override suspend fun compress(messages: List<Message>): String = "{}"
        override suspend fun summarizeForPrivacy(messages: List<Message>): String = ""
    }
    private val pipeline = StatusCompressionPipeline(fakePort, UserStatusRepository(fakeDao))

    // ── 증분 병합 (A) ─────────────────────────────────────────────────────────

    @Test
    fun `직전 상태가 없으면 fresh를 그대로 반환한다`() {
        val fresh = UserStatusEntity("room1", listOf("민수"), listOf("좋아요: 카페"), listOf("토요일 가능"))
        val merged = pipeline.mergeStatus(existing = null, fresh = fresh)
        assertEquals(fresh, merged)
    }

    @Test
    fun `직전 상태와 새 상태를 합집합으로 병합하고 중복을 제거한다`() {
        val existing = UserStatusEntity(
            "room1",
            participants = listOf("민수", "지영"),
            preferences = listOf("좋아요: 카페"),
            availability = listOf("토요일 가능"),
        )
        val fresh = UserStatusEntity(
            "room1",
            participants = listOf("지영", "철수"),          // 지영 중복
            preferences = listOf("좋아요: 카페", "싫어요: 술집"), // 카페 중복
            availability = listOf("일요일 가능"),
        )
        val merged = pipeline.mergeStatus(existing, fresh)

        assertEquals(listOf("민수", "지영", "철수"), merged.participants)
        assertEquals(listOf("좋아요: 카페", "싫어요: 술집"), merged.preferences)
        assertEquals(listOf("토요일 가능", "일요일 가능"), merged.availability)
    }

    @Test
    fun `병합 결과가 상한을 넘으면 오래된 항목부터 폐기한다`() {
        val old = (1..20).map { "old$it" }
        val new = (1..10).map { "new$it" }
        val merged = pipeline.mergeStatus(
            existing = UserStatusEntity("room1", participants = old),
            fresh = UserStatusEntity("room1", participants = new),
        )
        // 총 30개 → 상한 25개로 절삭, 최신(new) 항목은 모두 보존, 가장 오래된 old 5개 폐기
        assertEquals(25, merged.participants.size)
        assertTrue(merged.participants.containsAll(new))
        assertTrue(merged.participants.contains("old6"))
        assertTrue(!merged.participants.contains("old5"))
    }

    @Test
    fun `병합 시 공백 항목은 제거한다`() {
        val merged = pipeline.mergeStatus(
            existing = UserStatusEntity("room1", participants = listOf("민수", "  ")),
            fresh = UserStatusEntity("room1", participants = listOf("", "지영")),
        )
        assertEquals(listOf("민수", "지영"), merged.participants)
    }

    // ── JSON 추출 (extractAndParse) ───────────────────────────────────────────

    @Test
    fun `설명 텍스트와 마크다운이 섞여도 JSON 블록만 추출한다`() {
        val raw = """
            네, 분석했습니다:
            ```json
            {"participants": ["민수"], "preferences": ["좋아요: 카페"], "availability": ["토요일 가능"]}
            ```
            도움이 되었길 바랍니다.
        """.trimIndent()
        val e = pipeline.extractAndParse(raw, "room1")!!
        assertEquals(listOf("민수"), e.participants)
        assertEquals(listOf("좋아요: 카페"), e.preferences)
        assertEquals(listOf("토요일 가능"), e.availability)
    }

    @Test
    fun `trailing comma가 있어도 파싱한다`() {
        val raw = """{"participants": ["민수", "지영",], "preferences": [], "availability": [],}"""
        val e = pipeline.extractAndParse(raw, "room1")!!
        assertEquals(listOf("민수", "지영"), e.participants)
    }

    @Test
    fun `JSON이 없으면 null을 반환한다`() {
        assertNull(pipeline.extractAndParse("죄송합니다, 분석할 수 없습니다.", "room1"))
    }
}
