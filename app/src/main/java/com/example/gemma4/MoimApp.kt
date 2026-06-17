package com.example.gemma4

import android.app.Application
import com.example.gemma4.data.local.AppDatabase
import com.example.gemma4.data.repository.UserStatusRepository
import com.example.gemma4.service.LlmService

class MoimApp : Application() {
    val llmService: LlmService by lazy { LlmService(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val userStatusRepository: UserStatusRepository by lazy {
        UserStatusRepository(database.userStatusDao())
    }
}
