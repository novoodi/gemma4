package com.navoodi.morimi.data.repository

import android.content.Context
import android.util.Log
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class FeedbackEntry(
    val date: String,
    val feedback: String,
    val roomId: String = ""
)

/**
 * 모임 후기 저장소 — Room 영속(구 JSON 파일에서 이전).
 * 저장 시 임베딩 모델이 있으면 EmbeddingGemma 벡터를 함께 인덱싱(문서 프리픽스) →
 * [com.navoodi.morimi.data.pipeline.EmbeddingGemmaRetriever]가 시맨틱 검색에 사용.
 */
class FeedbackRepository(context: Context) {

    private val dao: FeedbackDao = AppDatabase.getInstance(context).feedbackDao()
    private val embedder = EmbeddingGemmaEmbedder(context)

    val feedbackDao: FeedbackDao get() = dao
    val embeddingEmbedder: EmbeddingGemmaEmbedder get() = embedder

    /** 후기 저장. 임베딩 모델이 준비돼 있으면 벡터를 함께 계산·영속화(로드→사용→해제). */
    suspend fun append(feedback: String, roomId: String = "") = withContext(Dispatchers.IO) {
        if (feedback.isBlank()) return@withContext
        val embedding = if (embedder.isAvailable) {
            runCatching { embedder.embedDocuments(listOf(feedback)).first() }
                .onFailure { Log.e("FeedbackRepository", "임베딩 실패 — 텍스트만 저장", it) }
                .getOrNull()
        } else null

        dao.insert(
            FeedbackEntity(
                roomId = roomId,
                date = LocalDate.now().toString(),
                feedback = feedback,
                embedding = embedding,
            )
        )
        Log.d("FeedbackRepository", "후기 저장 [$roomId] 임베딩=${embedding != null}: ${feedback.take(30)}")
    }

    suspend fun loadAll(): List<FeedbackEntry> = withContext(Dispatchers.IO) {
        dao.getAll().map { FeedbackEntry(it.date, it.feedback, it.roomId) }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clear() }
}
