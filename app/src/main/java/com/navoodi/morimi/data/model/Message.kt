package com.navoodi.morimi.data.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 서버에 아직 확정되지 않은 내 메시지(Firestore hasPendingWrites). 이때 [timestamp]는 기기 시계 추정치라
     * 정본 순서([com.navoodi.morimi.data.pipeline.MessageOrder])에서 맨 뒤에 둔다.
     */
    val pending: Boolean = false,
)
