package com.example.gemma4.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_status")
data class UserStatusEntity(
    @PrimaryKey
    val roomId: String,
    val participants: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val availability: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)
