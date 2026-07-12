package com.navoodi.morimi.service

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * EmbeddingGemma 300m 온디바이스 임베더 (raw LiteRT + DJL 토크나이저).
 *
 * 스파이크 1 실측 기준: 입력 INT32 [1, SEQ_LEN], 출력 FLOAT32 [1, DIM].
 * LiteRT-LM(생성)과 별개 런타임 — 후기 시맨틱 검색 전용. 완전 온디바이스라
 * "피드백조차 기기 밖으로 안 나감" 서사와 정합.
 *
 * **프리픽스 비대칭(CLAUDE.md 필수)**: 문서 인덱싱과 쿼리의 프리픽스가 다르다.
 * 이를 API로 강제하기 위해 [embedDocuments] / [embedQuery]만 노출한다 —
 * 원시 [embed]는 private. 프리픽스를 빼먹으면 검색 품질이 조용히 저하되기 때문.
 *
 * **상시 상주 금지(CLAUDE.md 엔진 동시성)**: 작업 단위로 로드→사용→해제.
 * Mutex로 보호(Interpreter는 thread-safe 아님). Gemma E2B와 메모리 경합 방지.
 */
class EmbeddingGemmaEmbedder(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingGemma"
        const val SEQ_LEN = 512
        const val DIM = 768
        const val MODEL_FILENAME = "embeddinggemma_seq512.tflite"
        const val TOKENIZER_FILENAME = "tokenizer_embeddinggemma.json"

        // 프리픽스 비대칭 — 절대 교차/누락 금지 (CLAUDE.md RAG 규칙)
        private const val DOC_PREFIX = "title: none | text: "
        private const val QUERY_PREFIX = "task: search result | query: "
    }

    private val mutex = Mutex()

    val modelFile: File get() = File(context.getExternalFilesDir("models"), MODEL_FILENAME)
    val tokenizerFile: File get() = File(context.getExternalFilesDir("models"), TOKENIZER_FILENAME)

    /** 모델·토크나이저 파일이 모두 있어야 임베딩 가능. 없으면 폴백 리트리버를 써야 함. */
    val isAvailable: Boolean get() = modelFile.exists() && tokenizerFile.exists()

    /** 후기 등 문서 인덱싱용 — DOC_PREFIX 적용 */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        embed(texts.map { DOC_PREFIX + it })

    /** 검색 쿼리용 — QUERY_PREFIX 적용 */
    suspend fun embedQuery(text: String): FloatArray =
        embed(listOf(QUERY_PREFIX + text)).first()

    /** 원시 임베딩 — 프리픽스는 호출측(문서/쿼리 메서드)에서 이미 부착됨 */
    private suspend fun embed(prefixedTexts: List<String>): List<FloatArray> = mutex.withLock {
        withContext(Dispatchers.Default) {
            require(isAvailable) { "임베딩 모델/토크나이저 미준비: $modelFile / $tokenizerFile" }
            if (prefixedTexts.isEmpty()) return@withContext emptyList()

            val tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile.toPath())
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optPadding(true)
                .optMaxLength(SEQ_LEN)
                .build()
            val interpreter = Interpreter(modelFile, Interpreter.Options())
            try {
                prefixedTexts.map { text ->
                    val ids = tokenizer.encode(text).ids            // long[] (패딩·절단으로 SEQ_LEN)
                    val input = Array(1) { IntArray(SEQ_LEN) { i -> ids.getOrElse(i) { 0L }.toInt() } }
                    val output = Array(1) { FloatArray(DIM) }
                    interpreter.run(input, output)
                    output[0].copyOf()
                }
            } finally {
                interpreter.close()
                tokenizer.close()
                Log.d(TAG, "임베딩 ${prefixedTexts.size}건 완료 — 엔진 해제")
            }
        }
    }
}
