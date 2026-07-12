package com.navoodi.morimi.data.pipeline

import android.util.Log
import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.repository.FeedbackEntry
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder

/**
 * 시맨틱 검색 리트리버 — 쿼리를 EmbeddingGemma로 임베딩해 roomId 내 후기 벡터와
 * brute-force 코사인 top-k. 임베딩이 없는(구버전 저장) 후기는 검색 대상에서 제외.
 */
class EmbeddingGemmaRetriever(
    private val embedder: EmbeddingGemmaEmbedder,
    private val feedbackDao: FeedbackDao,
) : FeedbackRetriever {

    override suspend fun retrieve(query: String, topK: Int): List<FeedbackEntry> {
        // 방 무관 — 이 사용자의 전체 후기에서 시맨틱 검색 (개인 취향 누적)
        val candidates = feedbackDao.getAll().filter { it.embedding != null }
        if (candidates.isEmpty() || query.isBlank()) return emptyList()

        val qv = embedder.embedQuery(query)
        return candidates
            .map { it to VectorMath.cosine(qv, it.embedding!!) }
            .sortedByDescending { it.second }
            .take(topK)
            .also { top -> Log.d("EmbeddingGemmaRetriever", "전체 후보 ${candidates.size} → top${top.size} (최고 cos=${top.firstOrNull()?.second})") }
            .map { (e, _) -> FeedbackEntry(date = e.date, feedback = e.feedback, roomId = e.roomId) }
    }
}
