package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.repository.FeedbackEntry

/**
 * 과거 모임 후기 검색 추상화 포트 (RAG의 R).
 *
 * 온디바이스 LLM처럼 런타임 교체 가능하게 설계 — 임베딩 모델이 있으면
 * [EmbeddingGemmaRetriever](시맨틱), 없으면 [KeywordFallbackRetriever](키워드).
 * (CLAUDE.md 포트-어댑터 컨벤션: OnDeviceLlmPort + Gemma/Mock 패턴과 동일)
 */
interface FeedbackRetriever {
    /** [query]와 관련성 높은 [roomId]의 과거 후기를 상위 [topK]건 반환 */
    suspend fun retrieve(query: String, roomId: String, topK: Int = 3): List<FeedbackEntry>
}

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
