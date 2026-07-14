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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val reindexRunning = AtomicBoolean(false)

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

    /**
     * 임베딩 누락 후기 재인덱싱 — 저장 시점에 모델이 없었거나 인덱싱이 실패해
     * "텍스트만·벡터 null"로 남은 후기를 일괄 임베딩해 시맨틱 검색 대상으로 복구.
     * 앱 시작·임베딩 모델 다운로드 완료 시 백그라운드로 호출된다.
     *
     * 전체를 한 번의 로드→사용→해제로 임베딩(엔진 반복 로드 방지)하되, 실패하면
     * 이번 실행은 통째로 건너뛴다 — 다음 앱 시작에 다시 시도되므로 안전.
     *
     * @return 재인덱싱된 후기 수 (모델 부재·누락 없음·중복 실행이면 0)
     */
    suspend fun reindexMissingEmbeddings(): Int = withContext(Dispatchers.IO) {
        if (!embedder.isAvailable) return@withContext 0
        if (!reindexRunning.compareAndSet(false, true)) return@withContext 0
        try {
            val missing = dao.getMissingEmbeddings()
            if (missing.isEmpty()) return@withContext 0
            Log.i("FeedbackRepository", "임베딩 누락 후기 ${missing.size}건 재인덱싱 시작")
            var indexed = 0
            runCatching { embedder.embedDocuments(missing.map { it.feedback }) }
                .onSuccess { vectors ->
                    missing.zip(vectors).forEach { (entity, emb) ->
                        dao.updateEmbedding(entity.id, Converters().fromFloatArray(emb))
                        indexed++
                    }
                    Log.i("FeedbackRepository", "재인덱싱 완료 ${indexed}건")
                }
                .onFailure { Log.e("FeedbackRepository", "재인덱싱 실패 — 다음 시작 시 재시도", it) }
            indexed
        } finally {
            reindexRunning.set(false)
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
