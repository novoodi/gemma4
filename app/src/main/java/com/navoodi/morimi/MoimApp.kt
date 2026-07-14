package com.navoodi.morimi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.pipeline.EmbeddingGemmaRetriever
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.GemmaOnDeviceLlm
import com.navoodi.morimi.data.pipeline.KeywordFallbackRetriever
import com.navoodi.morimi.data.pipeline.MockOnDeviceLlm
import com.navoodi.morimi.data.pipeline.StatusCompressionPipeline
import com.navoodi.morimi.data.repository.ChatRepository
import com.navoodi.morimi.data.repository.FeedbackRepository
import com.navoodi.morimi.data.repository.SummaryRepository
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
        reindexFeedbackEmbeddings()
        // 지난 추천 결과 복원 — 앱 재시작 후에도 "지난 추천 보기" 가능
        applicationScope.launch {
            ChatRepository.hydrateSummaries(summaryRepository.loadAll())
        }
    }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 임베딩 누락 후기 백그라운드 재인덱싱 — 앱 시작·모델 다운로드 완료 시 */
    private fun reindexFeedbackEmbeddings() {
        applicationScope.launch { feedbackRepository.reindexMissingEmbeddings() }
    }

    val llmService: LlmService by lazy { LlmService(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val userStatusRepository: UserStatusRepository by lazy {
        UserStatusRepository(database.userStatusDao())
    }
    val feedbackRepository: FeedbackRepository by lazy { FeedbackRepository(this) }
    val summaryRepository: SummaryRepository by lazy { SummaryRepository(this) }

    // Phase 2: 온디바이스 상태 압축 파이프라인
    // lazy 대신 nullable backing field — 모델 다운로드 후 reinitializePipelines()로 재생성 가능
    private var _compressionPipeline: StatusCompressionPipeline? = null
    val compressionPipeline: StatusCompressionPipeline
        get() = _compressionPipeline ?: run {
            val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
            StatusCompressionPipeline(llmPort = llmPort, repository = userStatusRepository)
                .also { _compressionPipeline = it }
        }

    // 후기 검색 리트리버 — 임베딩 모델이 있으면 시맨틱, 없으면 키워드 폴백 (런타임 교체)
    private var _feedbackRetriever: FeedbackRetriever? = null
    val feedbackRetriever: FeedbackRetriever
        get() = _feedbackRetriever ?: run {
            val embedder = feedbackRepository.embeddingEmbedder
            val dao = feedbackRepository.feedbackDao
            val retriever = if (embedder.isAvailable) EmbeddingGemmaRetriever(embedder, dao)
                else KeywordFallbackRetriever(dao)
            retriever.also { _feedbackRetriever = it }
        }

    // Phase 3: 오케스트레이터 + Guardrail 하네스
    val guardrailService: GuardrailService by lazy { GuardrailService() }
    private var _agentOrchestrator: AgentOrchestrator? = null
    val agentOrchestrator: AgentOrchestrator
        get() = _agentOrchestrator ?: run {
            val llmPort = if (llmService.isModelAvailable) GemmaOnDeviceLlm(llmService) else MockOnDeviceLlm()
            AgentOrchestrator(
                guardrailService = guardrailService,
                feedbackRetriever = feedbackRetriever,
                onDeviceLlm = llmPort,
            ).also { _agentOrchestrator = it }
        }

    /** 모델 다운로드 완료 후 호출 — 다음 접근 시 온디바이스 구현으로 재생성됨 */
    fun reinitializePipelines() {
        _compressionPipeline = null
        _agentOrchestrator = null
        _feedbackRetriever = null
        // 임베딩 모델이 방금 생겼을 수 있음 — 모델 부재로 못 인덱싱한 후기 복구
        reindexFeedbackEmbeddings()
    }
}
