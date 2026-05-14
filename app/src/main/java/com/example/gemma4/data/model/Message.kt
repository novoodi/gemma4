package com.example.gemma4.data.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean = false
)
