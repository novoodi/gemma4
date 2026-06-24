package com.navoodi.morimi.data.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
