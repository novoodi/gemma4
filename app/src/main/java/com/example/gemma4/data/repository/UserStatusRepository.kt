package com.example.gemma4.data.repository

import com.example.gemma4.data.local.UserStatusDao
import com.example.gemma4.data.local.UserStatusEntity

class UserStatusRepository(private val dao: UserStatusDao) {

    suspend fun upsert(entity: UserStatusEntity) = dao.upsert(entity)

    suspend fun getStatus(roomId: String): UserStatusEntity? = dao.getByRoomId(roomId)
}