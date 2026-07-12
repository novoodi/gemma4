package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.repository.FeedbackEntry

/**
 * 폴백 리트리버 — 임베딩 모델 미다운로드 시 사용.
 * 신경망 임베딩 없이 roomId 필터 + 키워드 겹침(Jaccard) 점수로 top-k.
 * 현재 덤프(takeLast(10))보다는 나은, 관련성 기반 검색. 순수 로직 — 단위 테스트 가능.
 */
class KeywordFallbackRetriever(
    private val feedbackDao: FeedbackDao,
) : FeedbackRetriever {

    override suspend fun retrieve(query: String, topK: Int): List<FeedbackEntry> {
        // 방 무관 — 이 사용자의 전체 후기에서 키워드 관련성 검색 (개인 취향 누적)
        val candidates = feedbackDao.getAll()
        if (candidates.isEmpty()) return emptyList()

        val qTokens = tokenize(query)
        // 쿼리에서 유의미한 토큰을 못 뽑으면 최근순 폴백(getByRoom이 id DESC 정렬)
        if (qTokens.isEmpty()) {
            return candidates.take(topK).map { FeedbackEntry(it.date, it.feedback, it.roomId) }
        }

        return candidates
            .map { it to jaccard(qTokens, tokenize(it.feedback)) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (e, _) -> FeedbackEntry(e.date, e.feedback, e.roomId) }
    }

    companion object {
        // 한글/영숫자 2글자 이상 토큰 추출(조사·기호 제거 근사)
        private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")

        internal fun tokenize(text: String): Set<String> =
            TOKEN.findAll(text.lowercase()).map { it.value }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.count { it in b }
            val union = a.size + b.size - inter
            return if (union == 0) 0.0 else inter.toDouble() / union
        }
    }
}
