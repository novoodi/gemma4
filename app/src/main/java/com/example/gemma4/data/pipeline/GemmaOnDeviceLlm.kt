package com.example.gemma4.data.pipeline

import com.example.gemma4.data.model.Message
import com.example.gemma4.service.LlmService

class GemmaOnDeviceLlm(private val llmService: LlmService) : OnDeviceLlmPort {

    override suspend fun compress(messages: List<Message>): String {
        llmService.initialize()
        return llmService.compressChatToStatus(messages)
    }
}
