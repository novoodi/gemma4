package com.example.gemma4.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface UserStatusDao {

    @Upsert
    suspend fun upsert(entity: UserStatusEntity)

    @Query("SELECT * FROM user_status WHERE roomId = :roomId")
    suspend fun getByRoomId(roomId: String): UserStatusEntity?

    @Query("DELETE FROM user_status WHERE roomId = :roomId")
    suspend fun deleteByRoomId(roomId: String)
}