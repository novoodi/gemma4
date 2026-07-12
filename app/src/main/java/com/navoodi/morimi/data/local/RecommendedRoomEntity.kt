package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * AI 추천(이야기 정리)을 한 번 이상 받은 방 표시.
 * "모임이 성사된 방"의 근거 — 재진입 시 후기 팝업 트리거 판단에 쓰인다(요약 전체는 영속하지 않음).
 */
@Entity(tableName = "recommended_room")
data class RecommendedRoomEntity(
    @PrimaryKey val roomId: String,
    val recommendedAt: Long = System.currentTimeMillis(),
)

@Dao
interface RecommendedRoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecommendedRoomEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM recommended_room WHERE roomId = :roomId)")
    suspend fun exists(roomId: String): Boolean
}
