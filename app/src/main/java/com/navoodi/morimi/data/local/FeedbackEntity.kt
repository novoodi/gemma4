package com.navoodi.morimi.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 모임 후기 1건. RAG 검색 대상.
 *
 * [embedding]은 EmbeddingGemma 768차원 벡터(문서 프리픽스로 인덱싱).
 * null이면 저장 시점에 임베딩 모델이 없었던 것 — 시맨틱 검색 불가, 키워드 폴백만 가능.
 *
 * [rating]은 5점 척도 만족도(0 = 아직 평가 없음). 자유 텍스트 후기만으로는 "좋았다/나빴다"를
 * 기계적으로 판정할 수 없어 추이 그래프도 재학습도 불가능했다 — 정량 신호를 하나 받는다.
 */
@Entity(tableName = "feedback")
data class FeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val date: String,
    val feedback: String,
    val embedding: FloatArray? = null,
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    /**
     * 작성 시각(epoch ms). [date]는 날짜 문자열이라 "이 후기가 최신 추천보다 나중인가"를
     * 판정할 수 없어 v6에서 추가했다(C7 후기 팝업 조건).
     * v6 이전 행은 마이그레이션에서 [date]를 자정 기준으로 환산해 채운다.
     */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
) {
    // FloatArray는 참조 동등성이라 data class 자동 구현이 부적절 — 내용 비교로 재정의
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackEntity) return false
        return id == other.id && roomId == other.roomId && date == other.date &&
            feedback == other.feedback && rating == other.rating && createdAt == other.createdAt &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + roomId.hashCode()
        result = 31 * result + date.hashCode()
        result = 31 * result + feedback.hashCode()
        result = 31 * result + rating
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
