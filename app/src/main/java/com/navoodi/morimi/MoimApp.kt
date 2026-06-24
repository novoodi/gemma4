package com.navoodi.morimi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.pipeline.GemmaOnDeviceLlm
import com.navoodi.morimi.data.pipeline.MockOnDeviceLlm
import com.navoodi.morimi.data.pipeline.StatusCompressionPipeline
import com.navoodi.morimi.data.repository.FeedbackRepository
import com.navoodi.morimi.data.repository.UserStatusRepository
import com.navoodi.morimi.service.AgentOrchestrator
import com.navoodi.morimi.service.FcmService
import com.navoodi.morimi.service.GuardrailService
import com.navoodi.morimi.service.LlmService

class MoimApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 앱 시작 시 채널 미리 등록 — FcmService 기동 전에도 notify() 가능하도록
        FcmService.createNotificationChannel(this)
    }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val llmService: LlmService by lazy { LlmService(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val userStatusRepository: UserStatusRepository by lazy {
        UserStatusRepository(database.userStatusDao())
    }
    val feedbackRepository: FeedbackRepository by lazy { FeedbackRepository(this) }

    // Phase 2: 온디바이스 상태 압축 파이프라인
    // lazy 대신 nullable backing field — 모델 다운로드 후 reinitializePipelines()로 재생성 가능
    private var _compressionPipeline: StatusCompressionPipeline? = null
    val compressionPipeline: StatusCompressionPipeline
        get() = _compressionPipeline ?: run {
            val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
            StatusCompressionPipeline(llmPort = llmPort, repository = userStatusRepository)
                .also { _compressionPipeline = it }
        }

    // Phase 3: 오케스트레이터 + Guardrail 하네스
    val guardrailService: GuardrailService by lazy { GuardrailService() }
    private var _agentOrchestrator: AgentOrchestrator? = null
    val agentOrchestrator: AgentOrchestrator
        get() = _agentOrchestrator ?: run {
            val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
            AgentOrchestrator(
                guardrailService = guardrailService,
                feedbackRepository = feedbackRepository,
                onDeviceLlm = llmPort,
            ).also { _agentOrchestrator = it }
        }

    /** 모델 다운로드 완료 후 호출 — 다음 접근 시 GemmaOnDeviceLlm으로 재생성됨 */
    fun reinitializePipelines() {
        _compressionPipeline = null
        _agentOrchestrator = null
    }
}
