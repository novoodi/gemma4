package com.navoodi.morimi.spike

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [스파이크 1 / 작업 2] DJL 토크나이저 실기기 런타임 검증.
 *
 * 빌드 통과 ≠ 동작: DJL tokenizers는 Rust 네이티브(libdjl_tokenizer.so)를 쓰므로
 * 이 테스트가 실기기에서 UnsatisfiedLinkError 없이 한국어 문장을 인코딩해야
 * 스파이크 통과다 (DEVLOG 2026-07-12 판정 기준).
 *
 * 사전 준비 (tokenizer.json은 repo에 커밋하지 않음 — 19MB):
 *   adb push tokenizer_embeddinggemma.json \
 *     /sdcard/Android/data/com.navoodi.morimi/files/models/tokenizer_embeddinggemma.json
 *
 * 실행:
 *   gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.navoodi.morimi.spike.EmbeddingTokenizerSpikeTest
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingTokenizerSpikeTest {

    companion object {
        private const val TAG = "EmbeddingSpike"
        // EmbeddingGemma 검색 쿼리 프리픽스 — 인덱싱 프리픽스("title: none | text: ")와 비대칭 (CLAUDE.md 참조)
        private const val QUERY_PREFIX = "task: search result | query: "
    }

    @Test
    fun djlTokenizer_loadsAndEncodesKorean_onDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tokenizerFile = File(ctx.getExternalFilesDir("models"), "tokenizer_embeddinggemma.json")
        assumeTrue(
            "tokenizer.json 미푸시 — 클래스 주석의 adb push 명령 실행 후 재시도",
            tokenizerFile.exists()
        )

        // 1) 네이티브 로드 판정 지점: 여기서 UnsatisfiedLinkError가 나면 스파이크 실패
        val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())

        // 2) 한국어 문장 인코딩
        val sentence = QUERY_PREFIX + "이번 토요일 홍대에서 저녁 모임"
        val encoding = tokenizer.encode(sentence)
        val ids = encoding.ids.toList()

        Log.i(TAG, "입력: $sentence")
        Log.i(TAG, "토큰 수: ${ids.size}")
        Log.i(TAG, "토큰 ID: $ids")
        Log.i(TAG, "토큰 문자열: ${encoding.tokens.toList()}")

        assertTrue("토큰 ID가 비어 있음", ids.isNotEmpty())
        // Gemma 어휘 크기(262144) 범위 내 ID여야 정상 vocab 로드
        assertTrue("vocab 범위 밖 토큰 ID 존재", ids.all { it in 0 until 262144 })
    }
}
