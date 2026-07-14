package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * AI 추천 결과(MeetingSummary) 영속본 — 앱 재시작 후에도 "지난 추천 보기"로 재확인 가능.
 *
 * places 등 중첩 구조가 있어 정규화 대신 JSON 직렬화 컬럼으로 저장한다
 * ([MeetingSummaryJson] 참조). 방당 최신 1건만 유지(roomId PK, upsert).
 */
@Entity(tableName = "meeting_summary")
data class MeetingSummaryEntity(
    @PrimaryKey val roomId: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface MeetingSummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MeetingSummaryEntity)

    @Query("SELECT * FROM meeting_summary")
    suspend fun getAll(): List<MeetingSummaryEntity>

    @Query("DELETE FROM meeting_summary WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("DELETE FROM meeting_summary")
    suspend fun clear()
}
