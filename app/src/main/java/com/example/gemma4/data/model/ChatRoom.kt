package com.example.gemma4.data.model

import java.util.UUID

data class ChatRoom(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val participants: List<String> = listOf("나"),
    val createdAt: Long = System.currentTimeMillis()
)
