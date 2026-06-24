package com.navoodi.morimi.data.pipeline

import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.service.LlmService

class GemmaOnDeviceLlm(private val llmService: LlmService) : OnDeviceLlmPort {

    override suspend fun compress(messages: List<Message>): String {
        llmService.initialize()
        return llmService.compressChatToStatus(messages)
    }

    override suspend fun summarizeForPrivacy(messages: List<Message>): String {
        llmService.initialize()
        return llmService.summarizeForPrivacy(messages)
    }
}
