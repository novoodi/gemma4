package com.navoodi.morimi.data.model

import java.util.UUID

data class ChatRoom(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val participantUids: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val inviteCode: String = "",
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
)