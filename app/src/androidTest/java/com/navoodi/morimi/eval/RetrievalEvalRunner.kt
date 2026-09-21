package com.navoodi.morimi.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.data.local.FeedbackDao
import com.navoodi.morimi.data.local.FeedbackEntity
import com.navoodi.morimi.data.pipeline.EmbeddingGemmaRetriever
import com.navoodi.morimi.data.pipeline.FeedbackRetriever
import com.navoodi.morimi.data.pipeline.KeywordFallbackRetriever
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [평가 실행기] 후기 검색 — 키워드 폴백 vs EmbeddingGemma 시맨틱을 같은 코퍼스·질의로 비교.
 * 기기의 실제 후기 테이블은 건드리지 않는다(메모리 DAO). 판정 없음 — 채점은 `scripts/eval/score_retrieval.py`.
 *
 * 입력: 모델 폴더의 feedback_corpus.json (scripts/eval/feedback_corpus.py 산출)
 * 출력: files/eval/retrieval_<시각>.jsonl  — 질의별 두 리트리버의 top-3 id·소요시간
 */
@RunWith(AndroidJUnit4::class)
class RetrievalEvalRunner {

    companion object { private const val TAG = "RetrievalEval"; private const val TOP_K = 3 }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 메모리 DAO — 실제 Room 테이블 격리 */
    private class MemDao(val items: MutableList<FeedbackEntity>) : FeedbackDao {
        override suspend fun insert(entity: FeedbackEntity): Long { items += entity; return entity.id }
        override suspend fun updateEmbedding(id: Long, embedding: ByteArray?) {}
        override suspend fun getByRoom(roomId: String) = items.filter { it.roomId == roomId }
        override suspend fun getAll() = items.toList()
        override suspend fun getMissingEmbeddings() = items.filter { it.embedding == null }
        override suspend fun deleteByRoom(roomId: String) {}
        override suspend fun clear() { items.clear() }
    }

    @Test
    fun runRetrievalEval() = runBlocking {
        val corpusFile = File(ctx.getExternalFilesDir("models"), "feedback_corpus.json")
        assumeTrue("코퍼스 없음: $corpusFile", corpusFile.exists())
        val embedder = EmbeddingGemmaEmbedder(ctx)
        assumeTrue("임베딩 모델 미준비", embedder.isAvailable)

        val root = JSONObject(corpusFile.readText())
        val fbArr = root.getJSONArray("feedbacks")
        val qArr = root.getJSONArray("queries")
        val ids = List(fbArr.length()) { fbArr.getJSONObject(it).getString("id") }
        val texts = List(fbArr.length()) { fbArr.getJSONObject(it).getString("text") }

        // 인덱싱(문서 프리픽스) — 실제 FeedbackRepository.append 와 같은 임베더 API
        val tIdx0 = System.nanoTime()
        val vectors = embedder.embedDocuments(texts)
        val indexMs = (System.nanoTime() - tIdx0) / 1_000_000
        val textToId = texts.zip(ids).toMap()
        val items = texts.indices.map { i ->
            FeedbackEntity(id = (i + 1).toLong(), roomId = "__eval__", date = "2026-09-13", feedback = texts[i], embedding = vectors[i])
        }.toMutableList()
        val dao = MemDao(items)
        val retrievers: Map<String, FeedbackRetriever> = mapOf(
            "keyword" to KeywordFallbackRetriever(dao),
            "semantic" to EmbeddingGemmaRetriever(embedder, dao),
        )

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(File(ctx.getExternalFilesDir("eval"), "").apply { mkdirs() }, "retrieval_$stamp.jsonl")
        out.appendText(JSONObject().put("type", "meta").put("feedbacks", texts.size).put("queries", qArr.length())
            .put("indexMs", indexMs).put("topK", TOP_K)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").toString() + "\n")
        Log.i(TAG, "코퍼스 ${texts.size}건 인덱싱 ${indexMs}ms, 질의 ${qArr.length()}건")

        for (i in 0 until qArr.length()) {
            val q = qArr.getJSONObject(i)
            val row = JSONObject().put("type", "result").put("qid", q.getString("id")).put("kind", q.getString("kind"))
                .put("gold", q.getString("gold"))
            for ((name, r) in retrievers) {
                val t0 = System.nanoTime()
                val got = try { r.retrieve(q.getString("text"), TOP_K) } catch (e: Throwable) {
                    Log.e(TAG, "$name 실패 ${q.getString("id")}", e); row.put("${name}Error", e.toString()); emptyList()
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                row.put(name, JSONArray(got.map { textToId[it.feedback] ?: "?" })).put("${name}Ms", ms)
            }
            out.appendText(row.toString() + "\n")
        }
        out.appendText(JSONObject().put("type", "meta").put("finished", true).toString() + "\n")
        Log.i(TAG, "검색 평가 완료 → $out")
        assertTrue(out.exists())
    }
}
