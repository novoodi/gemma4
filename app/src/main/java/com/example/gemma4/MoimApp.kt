package com.example.gemma4

import android.app.Application
import com.example.gemma4.data.local.AppDatabase
import com.example.gemma4.data.pipeline.GemmaOnDeviceLlm
import com.example.gemma4.data.pipeline.MockOnDeviceLlm
import com.example.gemma4.data.pipeline.StatusCompressionPipeline
import com.example.gemma4.data.repository.FeedbackRepository
import com.example.gemma4.data.repository.UserStatusRepository
import com.example.gemma4.service.AgentOrchestrator
import com.example.gemma4.service.GuardrailService
import com.example.gemma4.service.LlmService

class MoimApp : Application() {
    val llmService: LlmService by lazy { LlmService(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val userStatusRepository: UserStatusRepository by lazy {
        UserStatusRepository(database.userStatusDao())
    }
    val feedbackRepository: FeedbackRepository by lazy { FeedbackRepository(this) }

    // Phase 2: 온디바이스 상태 압축 파이프라인
    val compressionPipeline: StatusCompressionPipeline by lazy {
        val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
        StatusCompressionPipeline(
            llmPort = llmPort,
            repository = userStatusRepository
        )
    }

    // Phase 3: 오케스트레이터 + Guardrail 하네스
    val guardrailService: GuardrailService by lazy { GuardrailService() }
    val agentOrchestrator: AgentOrchestrator by lazy {
        val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
        AgentOrchestrator(
            guardrailService = guardrailService,
            feedbackRepository = feedbackRepository,
            onDeviceLlm = llmPort
        )
    }
}
