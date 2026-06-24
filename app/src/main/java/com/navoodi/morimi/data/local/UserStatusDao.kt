package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserStatusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserStatusEntity)

    @Query("SELECT * FROM user_status WHERE roomId = :roomId")
    suspend fun getByRoomId(roomId: String): UserStatusEntity?

    @Query("DELETE FROM user_status WHERE roomId = :roomId")
    suspend fun deleteByRoomId(roomId: String)
}