package com.example.gemma4.data.pipeline

import com.example.gemma4.data.model.Message

interface OnDeviceLlmPort {
    suspend fun compress(messages: List<Message>): String
}
