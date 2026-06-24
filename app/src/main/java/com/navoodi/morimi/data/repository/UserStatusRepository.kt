package com.navoodi.morimi.data.repository

import com.navoodi.morimi.data.local.UserStatusDao
import com.navoodi.morimi.data.local.UserStatusEntity

class UserStatusRepository(private val dao: UserStatusDao) {

    suspend fun upsert(entity: UserStatusEntity) = dao.upsert(entity)

    suspend fun getStatus(roomId: String): UserStatusEntity? = dao.getByRoomId(roomId)
}