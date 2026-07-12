package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FeedbackDao {

    @Insert
    suspend fun insert(entity: FeedbackEntity): Long

    /** 특정 방의 후기 — 시맨틱/키워드 검색 대상(roomId 필터가 검색의 1차 관문) */
    @Query("SELECT * FROM feedback WHERE roomId = :roomId ORDER BY id DESC")
    suspend fun getByRoom(roomId: String): List<FeedbackEntity>

    @Query("SELECT * FROM feedback ORDER BY id DESC")
    suspend fun getAll(): List<FeedbackEntity>

    @Query("DELETE FROM feedback WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("DELETE FROM feedback")
    suspend fun clear()
}
