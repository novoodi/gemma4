package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시맨틱 검색 폴백/유틸의 순수 로직 검증 (JVM). EmbeddingGemmaRetriever는
 * litert 네이티브가 필요해 골든/랭킹 계측 테스트가 담당.
 */
class RetrieverUnitTest {

    // ── VectorMath.cosine ─────────────────────────────────────────────────────

    @Test fun `동일 벡터 코사인 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, VectorMath.cosine(v, v), 1e-4f)
    }

    @Test fun `직교 벡터 코사인 0`() {
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-4f)
    }

    @Test fun `반대 방향 코사인 -1`() {
        assertEquals(-1.0f, VectorMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-4f)
    }

    @Test fun `크기 다르거나 0벡터면 0 반환`() {
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0f)
        assertEquals(0.0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    // ── KeywordFallbackRetriever 로직 ─────────────────────────────────────────

    @Test fun `tokenize는 2글자 이상 토큰만 소문자로 추출`() {
        val t = KeywordFallbackRetriever.tokenize("홍대 조용한 Cafe 가!")
        assertTrue("홍대" in t && "조용한" in t && "cafe" in t)
        assertTrue("가" !in t)   // 1글자 제외
    }

    @Test fun `jaccard 교집합-합집합 비율`() {
        val a = setOf("조용한", "카페")
        val b = setOf("조용한", "카페", "홍대")
        assertEquals(2.0 / 3.0, KeywordFallbackRetriever.jaccard(a, b), 1e-9)
        assertEquals(0.0, KeywordFallbackRetriever.jaccard(a, emptySet()), 0.0)
    }

    private fun fakeDao(vararg items: FeedbackEntity) = object : FeedbackDao {
        override suspend fun insert(entity: FeedbackEntity) = 0L
        override suspend fun getByRoom(roomId: String) = items.filter { it.roomId == roomId }
        override suspend fun getAll() = items.toList()
        override suspend fun clear() {}
    }

    @Test fun `roomId 필터 후 키워드 관련성 순으로 top-k`() = runBlocking {
        val dao = fakeDao(
            FeedbackEntity(1, "r1", "2026-01-01", "조용한 카페에서 대화 좋았어요"),
            FeedbackEntity(2, "r1", "2026-01-02", "시끄러운 술집은 별로였어요"),
            FeedbackEntity(3, "r1", "2026-01-03", "조용한 카페 커피 맛있었어요"),
            FeedbackEntity(4, "r2", "2026-01-04", "조용한 카페 최고"),   // 다른 방 — 제외돼야
        )
        val result = KeywordFallbackRetriever(dao).retrieve("조용한 카페 추천", roomId = "r1", topK = 2)

        assertEquals(2, result.size)                             // topK 준수
        assertTrue(result.all { it.roomId == "r1" })            // roomId 필터
        assertTrue(result.all { "카페" in it.feedback })         // "술집" 후기는 관련성 0 → 제외
    }

    @Test fun `쿼리 토큰이 없으면 최근순 폴백`() = runBlocking {
        val dao = fakeDao(
            FeedbackEntity(1, "r1", "2026-01-01", "첫 후기"),
            FeedbackEntity(2, "r1", "2026-01-02", "둘째 후기"),
        )
        val result = KeywordFallbackRetriever(dao).retrieve("!", roomId = "r1", topK = 5)
        assertEquals(2, result.size)   // 토큰 없음 → getByRoom(id DESC) 순 반환
    }
}
