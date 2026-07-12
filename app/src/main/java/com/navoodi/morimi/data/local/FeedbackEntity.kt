package com.navoodi.morimi.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 모임 후기 1건. RAG 검색 대상.
 *
 * [embedding]은 EmbeddingGemma 768차원 벡터(문서 프리픽스로 인덱싱).
 * null이면 저장 시점에 임베딩 모델이 없었던 것 — 시맨틱 검색 불가, 키워드 폴백만 가능.
 */
@Entity(tableName = "feedback")
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val date: String,
    val feedback: String,
    val embedding: FloatArray? = null,
) {
    // FloatArray는 참조 동등성이라 data class 자동 구현이 부적절 — 내용 비교로 재정의
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackEntity) return false
        return id == other.id && roomId == other.roomId && date == other.date &&
            feedback == other.feedback && (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + roomId.hashCode()
        result = 31 * result + date.hashCode()
        result = 31 * result + feedback.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
