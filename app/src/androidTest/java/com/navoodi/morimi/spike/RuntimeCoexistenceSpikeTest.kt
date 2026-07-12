package com.navoodi.morimi.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.service.LlmService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * [스파이크 1 / 작업 1] 두 온디바이스 런타임 동시 상주 판정.
 *
 * 핵심 리스크: litertlm(요약·생성)과 litert(임베딩)이 한 프로세스에서 실제로
 * 초기화되어 공존하는가. 빌드/로드 공존은 확인됨 — 여기서 실제 초기화·추론을 태운다.
 *
 * 사전 준비:
 *   - gemma-4-E2B-it.litertlm (앱에서 다운로드) — 실기기 models 디렉토리에 존재해야 함
 *   - embeddinggemma_seq512.tflite — adb push 로 주입
 *
 * ⚠️ litertlm은 GPU 백엔드 + 2.58GB 모델이라 에뮬레이터 불가, 실기기 필수.
 *    초기화가 느리므로 러너 타임아웃 넉넉히.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeCoexistenceSpikeTest {

    companion object { private const val TAG = "CoexistSpike" }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun modelsDir() = ctx.getExternalFilesDir("models")!!
    private fun tfliteFile() = File(modelsDir(), "embeddinggemma_seq512.tflite")

    /** 작업 1의 명시 기준: 기존 Gemma 파이프라인(litertlm)이 신규 네이티브와 함께 여전히 동작 */
    @Test
    fun litertlm_engine_initializes_and_summarizes() = runBlocking {
        val llm = LlmService(ctx)
        assumeTrue("gemma-4 모델 미다운로드", llm.isModelAvailable)

        llm.initialize()
        Log.i(TAG, "litertlm Engine 초기화 완료")
        val msgs = listOf(
            Message(roomId = "r", senderId = "s1", senderName = "철수", content = "이번 토요일에 홍대에서 저녁 먹자"),
            Message(roomId = "r", senderId = "s2", senderName = "영희", content = "좋아 6시에 보자"),
        )
        val summary = llm.summarizeForPrivacy(msgs)
        Log.i(TAG, "익명화 요약: $summary")
        llm.release()

        assertTrue("요약이 비어 있음", summary.isNotBlank())
    }

    /** litert(raw TFLite) 인터프리터가 embeddinggemma .tflite를 로드하는가 */
    @Test
    fun litert_interpreter_loads_embeddinggemma() {
        val tflite = tfliteFile()
        assumeTrue("embeddinggemma .tflite 미푸시", tflite.exists())

        val interpreter = Interpreter(tflite, Interpreter.Options())
        for (i in 0 until interpreter.inputTensorCount) {
            val t = interpreter.getInputTensor(i)
            Log.i(TAG, "입력[$i] name=${t.name()} dtype=${t.dataType()} shape=${t.shape().toList()}")
        }
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            Log.i(TAG, "출력[$i] name=${t.name()} dtype=${t.dataType()} shape=${t.shape().toList()}")
        }
        val outShape = interpreter.getOutputTensor(0).shape().toList()
        interpreter.close()

        assertTrue("출력 텐서 없음", outShape.isNotEmpty())
    }

    /** 핵심: 한 프로세스에서 litertlm + litert 동시 초기화 → litertlm 추론 지속 확인 */
    @Test
    fun both_runtimes_coexist_in_one_process() = runBlocking {
        val llm = LlmService(ctx)
        val tflite = tfliteFile()
        assumeTrue("모델 부재", llm.isModelAvailable && tflite.exists())

        llm.initialize()
        Log.i(TAG, "① litertlm 초기화 OK")

        val interpreter = Interpreter(tflite, Interpreter.Options())
        Log.i(TAG, "② 같은 프로세스에서 litert Interpreter 초기화 OK (동시 상주)")

        // 동시 상주 상태에서 litertlm 추론이 여전히 정상인지
        val summary = llm.summarizeForPrivacy(
            listOf(Message(roomId = "r", senderId = "s1", senderName = "철수", content = "토요일 저녁 홍대 모임 어때"))
        )
        Log.i(TAG, "③ 동시 상주 중 litertlm 요약: $summary")

        interpreter.close()
        llm.release()
        assertTrue("동시 상주 중 요약 실패", summary.isNotBlank())
    }
}
