package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.repository.FeedbackEntry
import com.navoodi.morimi.data.repository.toEntry

/**
 * 과거 모임 후기 검색 추상화 포트 (RAG의 R).
 *
 * 온디바이스 LLM처럼 런타임 교체 가능하게 설계 — 임베딩 모델이 있으면
 * [EmbeddingGemmaRetriever](시맨틱), 없으면 [KeywordFallbackRetriever](키워드).
 * (CLAUDE.md 포트-어댑터 컨벤션: OnDeviceLlmPort + Gemma/Mock 패턴과 동일)
 *
 * **방을 넘어 개인 취향을 누적한다**: 후기는 폰 로컬(=이 사용자)에 저장되므로,
 * 특정 방으로 좁히지 않고 이 사용자의 **모든 과거 후기**에서 관련성 높은 것을 회수한다.
 * ("지난 모임에서 좋았던 곳" 취향이 새 톡방 추천에도 반영됨)
 */
interface FeedbackRetriever {
    /** [query]와 관련성 높은 이 사용자의 과거 후기를 상위 [topK]건 반환(방 무관) */
    suspend fun retrieve(query: String, topK: Int = 3): List<FeedbackEntry>

    /**
     * 최근 불만 후기(별점 1~2) 최대 [limit]건 — **검색 점수와 무관하게** 늘 회수한다.
     * 쿼리는 장소명이 없는 익명 요약문이라, 특정 장소에 대한 불만은 유사도 검색으로 잘 안 걸린다(S5-8).
     */
    suspend fun complaints(limit: Int = 5): List<FeedbackEntry> = emptyList()
}

/** 별점 1~2 후기 중 최근 것부터 — 두 리트리버가 같은 규칙을 쓴다. */
internal suspend fun recentComplaints(dao: com.navoodi.morimi.data.local.FeedbackDao, limit: Int): List<FeedbackEntry> =
    dao.getRated()
        .filter { it.rating in 1..com.navoodi.morimi.service.ComplaintGate.MAX_RATING }
        .sortedByDescending { it.id }
        .take(limit)
        .map { it.toEntry() }

/** 벡터 유사도 유틸 — 순수 함수, JVM 단위 테스트 가능 */
object VectorMath {
    /** 코사인 유사도. 크기가 다르거나 0벡터면 0.0 반환(방어적) */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)))
    }
}
