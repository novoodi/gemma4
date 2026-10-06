package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FeedbackDao {

    @Insert
    suspend fun insert(entity: FeedbackEntity): Long

    /**
     * 임베딩을 나중에 채우기 위한 갱신(텍스트를 먼저 저장하고 임베딩은 백그라운드로 인덱싱).
     * @Query 파라미터엔 TypeConverter가 자동 적용되지 않으므로 ByteArray로 받는다
     * (FloatArray로 받으면 Room이 컬렉션으로 오인해 값마다 바인딩을 펼쳐 SQL 오류가 난다).
     */
    @Query("UPDATE feedback SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray?)

    /** 만족도 평점(1~5). 0은 미평가를 뜻하므로 추이 통계에서 제외된다. */
    @Query("UPDATE feedback SET rating = :rating WHERE id = :id")
    suspend fun updateRating(id: Long, rating: Int)

    /** 평가된 후기만 — 만족도 추이·재학습 증거의 원천 */
    @Query("SELECT * FROM feedback WHERE rating > 0 ORDER BY id ASC")
    suspend fun getRated(): List<FeedbackEntity>

    /** 특정 방의 후기 — 시맨틱/키워드 검색 대상(roomId 필터가 검색의 1차 관문) */
    @Query("SELECT * FROM feedback WHERE roomId = :roomId ORDER BY id DESC")
    suspend fun getByRoom(roomId: String): List<FeedbackEntity>

    /** 이 방의 가장 최근 후기 작성 시각. 후기가 없으면 null (C7 팝업 조건) */
    @Query("SELECT MAX(createdAt) FROM feedback WHERE roomId = :roomId")
    suspend fun latestFeedbackTimestamp(roomId: String): Long?

    @Query("SELECT COUNT(*) FROM feedback WHERE roomId = :roomId")
    suspend fun countByRoom(roomId: String): Int

    @Query("SELECT * FROM feedback ORDER BY id DESC")
    suspend fun getAll(): List<FeedbackEntity>

    /** 임베딩이 비어 있는 후기 — 저장 시점에 모델이 없었거나 인덱싱이 실패한 건. 재인덱싱 대상 */
    @Query("SELECT * FROM feedback WHERE embedding IS NULL ORDER BY id ASC")
    suspend fun getMissingEmbeddings(): List<FeedbackEntity>

    @Query("DELETE FROM feedback WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("DELETE FROM feedback")
    suspend fun clear()
}
