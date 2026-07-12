package com.navoodi.morimi.embedding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.pipeline.EmbeddingGemmaRetriever
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [2단계-5 통합] EmbeddingGemmaRetriever end-to-end 실기기 검증 —
 * 실제 임베딩 저장(문서 프리픽스) → 쿼리 임베딩 → roomId 필터 + 코사인 top-k.
 * in-memory Room으로 격리(실기기 데이터 오염 없음).
 */
@RunWith(AndroidJUnit4::class)
class FeedbackRetrieverIntegrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun semanticRetrieve_returnsRelevantFeedback_andFiltersByRoom() = runBlocking {
        val embedder = EmbeddingGemmaEmbedder(ctx)
        assumeTrue("임베딩 모델/토크나이저 미준비", embedder.isAvailable)

        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val dao = db.feedbackDao()
            // r1 후기 3건 + 다른 방 r2 1건(제외돼야) — 각각 문서 임베딩과 함께 저장
            val seed = listOf(
                Triple("r1", "홍대 조용한 카페에서 만나 대화하기 좋았어요", "cafe"),
                Triple("r1", "강남 술집이 너무 시끄러웠어요", "bar"),
                Triple("r1", "한강에서 자전거 타서 상쾌했어요", "bike"),
                Triple("r2", "성수 조용한 북카페 최고", "other-room"),
            )
            for ((room, text, _) in seed) {
                val emb = embedder.embedDocuments(listOf(text)).first()
                dao.insert(FeedbackEntity(roomId = room, date = "2026-01-01", feedback = text, embedding = emb))
            }

            val retriever = EmbeddingGemmaRetriever(embedder, dao)
            val top = retriever.retrieve(query = "조용한 카페 추천해줘", roomId = "r1", topK = 1)

            assertEquals("top-1 한 건이어야 함", 1, top.size)
            assertTrue("의미상 조용한 카페 후기가 top-1이어야 함: ${top.first().feedback}",
                top.first().feedback.contains("카페"))
            assertTrue("다른 방(r2) 후기가 새면 안 됨",
                retriever.retrieve("조용한 카페", "r1", 3).none { it.feedback.contains("북카페") })
        } finally {
            db.close()
        }
    }
}
