package com.navoodi.morimi.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.data.pipeline.EmbeddingGemmaRetriever
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.GemmaOnDeviceLlm
import com.navoodi.morimi.data.pipeline.KeywordFallbackRetriever
import com.navoodi.morimi.data.repository.FeedbackEntry
import com.navoodi.morimi.service.AssistantEvent
import com.navoodi.morimi.service.AssistantEventTracker
import com.navoodi.morimi.service.AssistantOrchestrator
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import com.navoodi.morimi.service.GuardrailService
import com.navoodi.morimi.service.OrchestratorResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * [평가 실행기] 추천 파이프라인 전체(Gemma 요약 → 스크러버 → 후기 회수 → Gemini FC → Guardrail·Reflection 재시도)를
 * 시나리오 × 후기 조건(none/keyword/semantic)으로 돌리고 **모든 AssistantEvent** 를 JSONL로 남긴다. 판정 없음.
 *
 * 검증 유무 비교(Reflection·Guardrail)는 코드 변경 없이 "1회차 결과 vs 최종 결과"로 채점한다
 * (1회차 프롬프트 = 검증 피드백 없는 기본 프롬프트). 채점: scripts/eval/score_recommend.py
 *
 * 입력: 모델 폴더의 시나리오 파일(기본 synthetic.json — gold.likes/dislikes/availability 로 userStatus 구성),
 *       feedback_corpus.json (후기 40건, 메모리 DAO — 기기 후기 테이블은 건드리지 않음)
 * 전제: Firebase 로그인 상태(프록시 인증). 미로그인이면 스킵.
 * 인자(-e): scenarioFile, conditions(쉼표: none,keyword,semantic), limit, offset, reps
 */
@RunWith(AndroidJUnit4::class)
class RecommendationEvalRunner {

    companion object { private const val TAG = "RecommendEval" }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as MoimApp
    private val args = InstrumentationRegistry.getArguments()

    private class MemDao(val items: MutableList<FeedbackEntity>) : FeedbackDao {
        override suspend fun insert(entity: FeedbackEntity): Long { items += entity; return entity.id }
        override suspend fun updateEmbedding(id: Long, embedding: ByteArray?) {}
        override suspend fun getByRoom(roomId: String) = items.filter { it.roomId == roomId }
        override suspend fun getAll() = items.toList()
        override suspend fun getMissingEmbeddings() = items.filter { it.embedding == null }
        override suspend fun deleteByRoom(roomId: String) {}
        // 고도화 이식(2026-09-23): 평점·후기 팝업 정책이 DAO에 추가돼 가짜 구현도 맞춘다
        override suspend fun updateRating(id: Long, rating: Int) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(rating = rating)
        }
        override suspend fun getRated() = items.filter { it.rating > 0 }
        override suspend fun latestFeedbackTimestamp(roomId: String) =
            items.filter { it.roomId == roomId }.maxOfOrNull { it.createdAt }
        override suspend fun countByRoom(roomId: String) = items.count { it.roomId == roomId }
        override suspend fun clear() { items.clear() }
    }

    private object NoFeedback : FeedbackRetriever {
        override suspend fun retrieve(query: String, topK: Int): List<FeedbackEntry> = emptyList()
    }

    private fun AssistantEvent.toJson(): JSONObject = JSONObject().put("event", this::class.java.simpleName).also { o ->
        when (this) {
            is AssistantEvent.OrchestrationStarted -> o.put("messageCount", messageCount)
            is AssistantEvent.GemmaSummaryCompleted -> o.put("summary", summary).put("redactions", redactions).put("byCategory", JSONObject(redactionsByCategory))
            is AssistantEvent.PromptGenerated -> o.put("attempt", attempt).put("hasRag", prompt.contains("[과거 피드백 이력]")).put("promptChars", prompt.length)
            is AssistantEvent.ToolCalled -> o.put("name", name).put("args", JSONObject(args.mapValues { it.value?.toString() })).put("resultChars", result.length).put("resultHead", result.take(120))
            is AssistantEvent.JsonParsed -> o.put("rawJson", rawJson)
            is AssistantEvent.GuardrailEvaluated -> o.put("attempt", attempt).put("passed", passed).put("feedback", feedback).put("unknownCount", unknownCount)
            is AssistantEvent.ReflectionEvaluated -> o.put("attempt", attempt).put("passed", passed).put("violations", JSONArray(violations))
            is AssistantEvent.OrchestrationFinished -> o.put("success", success).put("attempts", attempts).put("reason", reason ?: JSONObject.NULL)
            // 고도화로 추가된 이벤트들 - 이 러너의 관심사가 아니다
            else -> Unit
        }
    }

    @Test
    fun runRecommendationEval() = runBlocking {
        assumeTrue("Firebase 미로그인 — 프록시 호출 불가", FirebaseAuth.getInstance().currentUser != null)
        assumeTrue("Gemma 미준비", app.llmService.isModelAvailable)
        val scenarioName = args.getString("scenarioFile") ?: "synthetic.json"
        val scenarioFile = File(ctx.getExternalFilesDir("models"), scenarioName)
        val corpusFile = File(ctx.getExternalFilesDir("models"), "feedback_corpus.json")
        assumeTrue("시나리오 없음: $scenarioFile", scenarioFile.exists())
        assumeTrue("후기 코퍼스 없음: $corpusFile", corpusFile.exists())
        val conditions = (args.getString("conditions") ?: "none,keyword,semantic").split(",").map { it.trim() }
        val limit = args.getString("limit")?.toIntOrNull() ?: Int.MAX_VALUE
        val offset = args.getString("offset")?.toIntOrNull() ?: 0
        val reps = args.getString("reps")?.toIntOrNull() ?: 1

        // 후기 코퍼스 → 메모리 DAO (semantic 조건은 임베딩 포함)
        val fbArr = JSONObject(corpusFile.readText()).getJSONArray("feedbacks")
        val fbTexts = List(fbArr.length()) { fbArr.getJSONObject(it).getString("text") }
        val embedder = EmbeddingGemmaEmbedder(ctx)
        val vectors = if ("semantic" in conditions && embedder.isAvailable) embedder.embedDocuments(fbTexts) else null
        fun dao(withEmb: Boolean) = MemDao(fbTexts.indices.map { i ->
            FeedbackEntity(id = (i + 1).toLong(), roomId = "__eval__", date = "2026-08-01", feedback = fbTexts[i],
                embedding = if (withEmb) vectors?.get(i) else null)
        }.toMutableList())
        val retrievers: Map<String, FeedbackRetriever?> = mapOf(
            "none" to NoFeedback,
            "keyword" to KeywordFallbackRetriever(dao(false)),
            "semantic" to if (vectors != null) EmbeddingGemmaRetriever(embedder, dao(true)) else null,
        )
        val llmPort = GemmaOnDeviceLlm(app.llmService)

        val dialogues = JSONObject(scenarioFile.readText()).getJSONArray("dialogues")
        val end = minOf(dialogues.length(), offset + limit)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(File(ctx.getExternalFilesDir("eval"), "").apply { mkdirs() }, "recommend_${scenarioName.removeSuffix(".json")}_$stamp.jsonl")
        out.appendText(JSONObject().put("type", "meta").put("scenarioFile", scenarioName).put("conditions", JSONArray(conditions))
            .put("offset", offset).put("end", end).put("reps", reps).put("feedbacks", fbTexts.size)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("startedAt", stamp).toString() + "\n")
        Log.i(TAG, "추천 평가 시작: $scenarioName [$offset,$end) × $conditions × $reps → $out")

        for (i in offset until end) {
            val d = dialogues.getJSONObject(i)
            val id = d.getString("id")
            val msgs = d.getJSONArray("messages").let { a ->
                List(a.length()) { k ->
                    val m = a.getJSONObject(k)
                    Message(roomId = "__eval__$id", senderId = m.getString("pid"), senderName = m.getString("sender"), content = m.getString("text"),
                        // 정본 순서(서버 시각 → 문서 id)를 따르므로 대화 순서를 시각으로 명시한다(2026-10 S3)
                        timestamp = 1_000L + k)
                }
            }
            val g = d.getJSONObject("gold")
            fun list(key: String) = g.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val participants = d.getJSONArray("participants").let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } }
            // 성향 프로필: 압축 파이프라인의 출력 형식("좋아요:"/"싫어요:" 접두사)과 동일하게 구성. 주석 "(2명)" 제거.
            val status = UserStatusEntity(
                roomId = "__eval__$id", participants = participants,
                preferences = list("likes").map { "좋아요: ${it.replace(Regex("\\([^)]*\\)"), "").trim()}" } +
                    list("dislikes").map { "싫어요: ${it.replace(Regex("\\([^)]*\\)"), "").trim()}" },
                availability = list("availability"),
            )
            val chatDate = runCatching { LocalDate.parse(d.getString("chat_date")) }.getOrDefault(LocalDate.now())

            for (cond in conditions) {
                val retriever = retrievers[cond] ?: run { Log.w(TAG, "조건 $cond 비가용 — 스킵"); null } ?: continue
                val orchestrator = AssistantOrchestrator(GuardrailService(), retriever, llmPort)
                for (rep in 1..reps) {
                    val events = JSONArray()
                    val tracker = object : AssistantEventTracker { override fun onEvent(event: AssistantEvent) { events.put(event.toJson()) } }
                    val row = JSONObject().put("type", "result").put("id", id).put("condition", cond).put("rep", rep)
                        .put("goldDateAbsolute", g.optString("date_absolute", null) ?: JSONObject.NULL)
                    val t0 = System.nanoTime()
                    try {
                        val res = orchestrator.orchestrate("__eval__$id", msgs, status, chatDate, tracker)
                        when (res) {
                            is OrchestratorResult.Success -> row.put("success", true).put("attempts", res.attempts)
                                .put("meetingDate", res.summary.meetingDate).put("location", res.summary.location)
                                .put("places", JSONArray(res.summary.places.map { JSONObject().put("name", it.name).put("reason", it.reason).put("verification", it.verification.name) }))
                                .put("activities", JSONArray(res.summary.activities))
                            is OrchestratorResult.Failed -> row.put("success", false).put("attempts", res.attempts).put("reason", res.reason)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[$id/$cond/$rep] 예외", e); row.put("error", e.toString())
                    }
                    row.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000).put("events", events)
                    out.appendText(row.toString() + "\n")
                    Log.i(TAG, "[$id/$cond/$rep] success=${row.opt("success")} attempts=${row.opt("attempts")} ${row.opt("elapsedMs")}ms")
                }
            }
        }
        out.appendText(JSONObject().put("type", "meta").put("finishedAt", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).toString() + "\n")
        Log.i(TAG, "추천 평가 완료 → $out")
        assertTrue(out.exists())
    }
}
