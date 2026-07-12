package com.navoodi.morimi.embedding

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.data.pipeline.VectorMath
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 골든 테스트 — 온디바이스 tflite 임베딩이 Python sentence-transformers 기준과
 * 얼마나 일치하는지 코사인으로 실측 (CLAUDE.md RAG 규칙: 골든 테스트 의무).
 *
 * 안드로이드는 mixed-precision 양자화 tflite, Python 기준은 원본 float 모델 →
 * 양자화 오차만큼 1.0 미만. **첫 실측값을 보고 임계값을 확정**한다(현재는 하한만).
 *
 * 사전: golden_embeddings.json (make_golden_embeddings.py 산출)을 models 디렉토리에 push
 *   adb push golden_embeddings.json /sdcard/Android/data/com.navoodi.morimi/files/models/
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingGoldenTest {

    companion object { private const val TAG = "EmbeddingGolden" }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun androidEmbeddings_vs_pythonReference() = runBlocking {
        val embedder = EmbeddingGemmaEmbedder(ctx)
        assumeTrue("임베딩 모델/토크나이저 미준비", embedder.isAvailable)
        val golden = File(embedder.modelFile.parentFile, "golden_embeddings.json")
        assumeTrue("golden_embeddings.json 미푸시", golden.exists())

        val root = JSONObject(golden.readText())
        val allCos = mutableListOf<Float>()

        suspend fun run(kind: String, arr: JSONArray, embed: suspend (String) -> FloatArray) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.getString("text")
                val ref = o.getJSONArray("vector").toFloatArray()
                val got = embed(text)
                val cos = VectorMath.cosine(got, ref)
                allCos += cos
                Log.i(TAG, "[$kind] cos=%.4f :: %s".format(cos, text))
            }
        }

        run("doc", root.getJSONArray("documents")) { embedder.embedDocuments(listOf(it)).first() }
        run("query", root.getJSONArray("queries")) { embedder.embedQuery(it) }

        val min = allCos.min()
        val avg = allCos.average()
        Log.i(TAG, "═══ 실측: 평균 cos=%.4f, 최소 cos=%.4f (n=%d) ═══".format(avg, min, allCos.size))

        // 확정 임계값 0.93 (2026-07-12 실측 min 0.9466, unsloth 미러 int4 기준)
        assertTrue("온디바이스 임베딩이 기준과 임계값 미만(min cos=$min < 0.93)", min >= 0.93f)
    }

    /**
     * 랭킹 일치 — 각 쿼리에 대해 온디바이스 파이프라인의 top-1 문서가
     * Python 기준 모델의 top-1과 동일한가. 실제 검색 품질의 직접 증명(전부 일치해야 통과).
     */
    @Test
    fun ranking_top1_matchesPythonReference() = runBlocking {
        val embedder = EmbeddingGemmaEmbedder(ctx)
        assumeTrue("임베딩 모델/토크나이저 미준비", embedder.isAvailable)
        val golden = File(embedder.modelFile.parentFile, "golden_embeddings.json")
        assumeTrue("golden_embeddings.json 미푸시", golden.exists())

        val root = JSONObject(golden.readText())
        val docsArr = root.getJSONArray("documents")
        val docTexts = (0 until docsArr.length()).map { docsArr.getJSONObject(it).getString("text") }
        val docVecs = embedder.embedDocuments(docTexts)   // 온디바이스 문서 임베딩

        val queries = root.getJSONArray("queries")
        val mismatches = mutableListOf<String>()
        for (i in 0 until queries.length()) {
            val q = queries.getJSONObject(i)
            val qText = q.getString("text")
            val expected = q.getInt("top1")
            val qv = embedder.embedQuery(qText)
            val got = docVecs.indices.maxByOrNull { VectorMath.cosine(qv, docVecs[it]) }!!
            val mark = if (got == expected) "OK" else "MISMATCH"
            Log.i(TAG, "[rank] '$qText' 기준top1=$expected 온디바이스top1=$got $mark")
            if (got != expected) mismatches += "'$qText' expected=$expected got=$got"
        }
        assertTrue("랭킹 불일치: $mismatches", mismatches.isEmpty())
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
}
