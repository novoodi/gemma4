package com.navoodi.morimi.embedding

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.service.AgentEvent
import com.navoodi.morimi.service.AgentEventTracker
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import com.navoodi.morimi.service.OrchestratorResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [RAG E2E] 실제 MoimApp DI로 프로덕션 경로를 그대로 태워 검증:
 * 후기 저장(임베딩 인덱싱) → 시맨틱 회수 → Gemini 프롬프트 주입 → 추천, 그리고 폴백 전환.
 * ⚠️ 실기기 전용(Gemma litertlm + EmbeddingGemma litert + 실제 Gemini). 수십 초 소요.
 */
@RunWith(AndroidJUnit4::class)
class RagE2ETest {

    companion object {
        private const val TAG = "RagE2E"
        private const val ROOM = "__rag_e2e__"
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as MoimApp

    private fun msgs(vararg c: String) = c.mapIndexed { i, t ->
        Message(roomId = ROOM, senderId = "u${i % 2}", senderName = if (i % 2 == 0) "철수" else "영희", content = t)
    }

    private fun capture() = mutableListOf<AgentEvent>().let { list ->
        list to object : AgentEventTracker { override fun onEvent(event: AgentEvent) { list += event } }
    }

    @Test
    fun ragE2E_semanticRetrievalFlowsIntoRecommendation_andFallsBack() = runBlocking {
        runE2E()
    }

    private suspend fun runE2E() {
        val embedder = EmbeddingGemmaEmbedder(ctx)
        assumeTrue("임베딩 모델 미준비", embedder.isAvailable)
        assumeTrue("Gemma 미준비", app.llmService.isModelAvailable)

        val repo = app.feedbackRepository
        // 검색이 방 무관(전체 후기)이므로 격리를 위해 전체 정리. ⚠️ 이 기기의 후기 전부 삭제됨(테스트 전용).
        repo.clearAll()

        // ── STEP 1-2: 후기 저장 (임베딩 인덱싱) ──
        Log.i(TAG, "STEP 1-2: 후기 저장·임베딩 인덱싱")
        repo.append("홍대 조용한 카페에서 도란도란 얘기 나눠서 좋았어요", ROOM)
        repo.append("강남 술집은 너무 시끄러워서 대화가 안 됐어요", ROOM)
        repo.append("주말에 북한산 등산 갔는데 힘들었지만 뿌듯했어요", ROOM)
        val stored = repo.feedbackDao.getByRoom(ROOM)
        Log.i(TAG, "저장 ${stored.size}건, 임베딩 보유 ${stored.count { it.embedding != null }}건")
        assertEquals(3, stored.size)
        assertTrue("모든 후기 임베딩 인덱싱", stored.all { it.embedding != null })

        // ── STEP 3: 시맨틱 회수 순위 + orchestrate 프롬프트 주입 + 추천 ──
        Log.i(TAG, "STEP 3: 시맨틱 회수 → 추천")
        app.reinitializePipelines()   // 모델 존재 → EmbeddingGemmaRetriever

        val ranked = app.feedbackRetriever.retrieve("조용한 카페에서 대화 나누기", topK = 3)
        Log.i(TAG, "시맨틱 회수 순위: ${ranked.map { it.feedback.take(14) }}")
        assertTrue("카페 후기가 top-1이어야", ranked.first().feedback.contains("카페"))

        val (events, tracker) = capture()
        val result = app.agentOrchestrator.orchestrate(
            roomId = ROOM,
            messages = msgs(
                "이번주 토요일에 조용한 카페에서 모여서 얘기하는 거 어때?",
                "좋아 지난번처럼 시끄럽지 않은 데로 가자",
                "카페 추천 좀 해줘",
            ),
            userStatus = null,
            eventTracker = tracker,
        )
        val prompt = events.filterIsInstance<AgentEvent.PromptGenerated>().first().prompt
        Log.i(TAG, "프롬프트 주입 — 카페후기=${prompt.contains("조용한 카페")}")
        assertTrue("회수된 카페 후기가 Gemini 프롬프트에 주입돼야", prompt.contains("조용한 카페"))
        when (result) {
            is OrchestratorResult.Success ->
                Log.i(TAG, "추천 성공(${result.attempts}회): ${result.summary.recommendation.replace("\n", " ").take(220)}")
            is OrchestratorResult.Failed ->
                Log.w(TAG, "추천 실패(네트워크/키): ${result.reason}")
        }

        // ── STEP 4: 임베딩 모델 제거 → 키워드 폴백 자동 전환 ──
        Log.i(TAG, "STEP 4: 임베딩 모델 제거 → 키워드 폴백")
        val tflite = embedder.modelFile
        val bak = File(tflite.parentFile, tflite.name + ".bak")
        assertTrue("모델 임시 이동 실패", tflite.renameTo(bak))
        try {
            app.reinitializePipelines()  // 모델 부재 → KeywordFallbackRetriever
            assertFalse("임베더 비가용이어야", EmbeddingGemmaEmbedder(ctx).isAvailable)

            val fbRanked = app.feedbackRetriever.retrieve("조용한 카페", topK = 3)
            Log.i(TAG, "폴백(키워드) 회수: ${fbRanked.map { it.feedback.take(14) }}")
            assertTrue("키워드 폴백도 카페 후기 회수", fbRanked.any { it.feedback.contains("카페") })

            val (events2, tracker2) = capture()
            app.agentOrchestrator.orchestrate(ROOM, msgs("조용한 카페에서 만나자", "카페 추천"), null, eventTracker = tracker2)
            val prompt2 = events2.filterIsInstance<AgentEvent.PromptGenerated>().first().prompt
            Log.i(TAG, "폴백 프롬프트 카페후기 주입=${prompt2.contains("카페")}")
            assertTrue("폴백 경로도 프롬프트에 후기 주입", prompt2.contains("카페"))
        } finally {
            assertTrue("모델 원복 실패", bak.renameTo(tflite))
            app.reinitializePipelines()
            repo.feedbackDao.deleteByRoom(ROOM)
        }
        Log.i(TAG, "RAG E2E 완료 ✅")
    }
}
