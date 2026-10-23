package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarEventDao {

    /** 추가된 순서대로 복원 — 인메모리 목록의 순서를 재시작 후에도 유지한다 */
    @Query("SELECT * FROM calendar_event ORDER BY createdAt ASC")
    suspend fun getAll(): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarEventEntity)

    @Query("DELETE FROM calendar_event WHERE id = :id")
    suspend fun deleteById(id: String)
}
