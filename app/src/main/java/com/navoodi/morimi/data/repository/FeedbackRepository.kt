package com.navoodi.morimi.data.repository

import android.content.Context
import android.util.Log
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.Converters
import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.local.RecommendedRoomEntity
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
    private val recommendedDao = AppDatabase.getInstance(context).recommendedRoomDao()
    private val embedder = EmbeddingGemmaEmbedder(context)

    val feedbackDao: FeedbackDao get() = dao
    val embeddingEmbedder: EmbeddingGemmaEmbedder get() = embedder

    /**
     * 후기 저장. **텍스트를 먼저 즉시 저장**하고(팝업 재출현·유실 방지), 임베딩은 그 뒤에 채운다.
     * 임베딩 생성은 수십 초가 걸릴 수 있어 저장을 블로킹하면 안 된다.
     */
    suspend fun append(feedback: String, roomId: String = "") = withContext(Dispatchers.IO) {
        if (feedback.isBlank()) return@withContext

        // 1) 텍스트 즉시 저장 — 이 시점부터 shouldPromptFeedback=false
        val id = dao.insert(
            FeedbackEntity(
                roomId = roomId,
                date = LocalDate.now().toString(),
                feedback = feedback,
                embedding = null,
            )
        )
        Log.d("FeedbackRepository", "후기 저장 [$roomId] id=$id: ${feedback.take(30)}")

        // 2) 임베딩 인덱싱은 뒤따라 (실패해도 텍스트는 이미 저장됨 → 키워드 폴백 가능)
        if (embedder.isAvailable) {
            runCatching { embedder.embedDocuments(listOf(feedback)).first() }
                .onSuccess { emb ->
                    dao.updateEmbedding(id, Converters().fromFloatArray(emb))
                    Log.d("FeedbackRepository", "후기 임베딩 인덱싱 완료 [$roomId] id=$id")
                }
                .onFailure { Log.e("FeedbackRepository", "임베딩 실패 — 텍스트만 유지 [$roomId] id=$id", it) }
        }
    }

    suspend fun loadAll(): List<FeedbackEntry> = withContext(Dispatchers.IO) {
        dao.getAll().map { FeedbackEntry(it.date, it.feedback, it.roomId) }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clear() }

    /** AI 추천을 받은 방으로 표시 — 재진입 시 후기 팝업 트리거 근거 */
    suspend fun markRecommended(roomId: String) = withContext(Dispatchers.IO) {
        recommendedDao.insert(RecommendedRoomEntity(roomId))
    }

    /** 후기 팝업을 띄울지: 추천을 받은 적 있고(모임 성사) 아직 후기가 없는 방 */
    suspend fun shouldPromptFeedback(roomId: String): Boolean = withContext(Dispatchers.IO) {
        recommendedDao.exists(roomId) && dao.getByRoom(roomId).isEmpty()
    }
}
