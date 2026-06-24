package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message

interface OnDeviceLlmPort {
    suspend fun compress(messages: List<Message>): String
    suspend fun summarizeForPrivacy(messages: List<Message>): String
}
