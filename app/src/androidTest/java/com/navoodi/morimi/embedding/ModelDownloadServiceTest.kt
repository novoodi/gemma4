package com.navoodi.morimi.embedding

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.service.EmbeddingGemmaEmbedder
import com.navoodi.morimi.service.LlmService
import com.navoodi.morimi.service.ModelDownloadService
import com.navoodi.morimi.ui.screen.modeldownload.ModelDownloadUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [후속 1 통합] 다중 파일 다운로드 배선 검증 — 이미 존재하는 Gemma는 건너뛰고
 * 임베딩 모델(.tflite)·토크나이저(.json)만 실제로 내려받는지 실기기에서 확인.
 *
 * ⚠️ 네트워크·대용량(약 199MB) 의존 — 수동/실기기 전용. Gemma가 이미 있어야 skip 검증 가능.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadServiceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun modelsDir() = File(ctx.getExternalFilesDir("models"), "")

    @Test
    fun downloadsEmbeddingFiles_andSkipsExistingGemma() = runBlocking {
        val gemma = File(modelsDir(), LlmService.MODEL_FILENAME)
        assumeTrue("Gemma 미다운로드 — skip 검증 불가", gemma.exists())
        val gemmaMtimeBefore = gemma.lastModified()

        val tflite = File(modelsDir(), EmbeddingGemmaEmbedder.MODEL_FILENAME)
        val tokenizer = File(modelsDir(), EmbeddingGemmaEmbedder.TOKENIZER_FILENAME)
        tflite.delete(); tokenizer.delete()

        // 서비스 시작(앱 프로세스이므로 FGS 시작 권한 OK)
        ctx.startForegroundService(
            Intent(ctx, ModelDownloadService::class.java)
                .apply { action = ModelDownloadService.ACTION_START }
        )

        // 완료(또는 오류)까지 대기 — 199MB, Wi-Fi 기준 수십 초
        val terminal = withTimeout(600_000) {
            ModelDownloadService.state.first {
                it is ModelDownloadUiState.Complete || it is ModelDownloadUiState.Error
            }
        }
        assertTrue("다운로드 실패: $terminal", terminal is ModelDownloadUiState.Complete)

        // 임베딩 2종이 정확한 크기로 받아졌는지
        assertTrue("tflite 미생성", tflite.exists())
        assertEquals("tflite 크기 불일치", 179_132_472L, tflite.length())
        assertTrue("tokenizer 미생성", tokenizer.exists())
        assertEquals("tokenizer 크기 불일치", 20_323_312L, tokenizer.length())

        // Gemma는 재다운로드 없이 그대로(수정시각 불변) — skip 검증
        assertEquals("Gemma가 재다운로드됨(skip 실패)", gemmaMtimeBefore, gemma.lastModified())
    }
}
