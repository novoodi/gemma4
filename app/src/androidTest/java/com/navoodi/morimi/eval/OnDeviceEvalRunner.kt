package com.navoodi.morimi.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navoodi.morimi.MoimApp
import com.navoodi.morimi.data.model.Message
import com.navoodi.morimi.service.PiiScrubber
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
 * [평가 실행기] 온디바이스 경로(Gemma 요약 → PiiScrubber, Gemma 성향 압축)를 평가셋 전체에 돌려
 * 결과를 JSONL로 남긴다. **판정은 하지 않는다** — 채점은 PC의 `scripts/eval/score_ondevice.py`.
 *
 * 입력: 모델 폴더의 평가 JSON (`adb push .eval-local/evalset/bulk_ondevice.json …/files/models/`).
 * 출력: `files/eval/ondevice_<파일명>_<시각>.jsonl` — 한 줄씩 flush 하므로 중단돼도 부분 결과가 남는다.
 *
 * 인자(-e): evalFile(기본 bulk_ondevice.json), limit, offset, reps(기본 1), skipCompress(true/false)
 *   adb shell am instrument -w -e class com.navoodi.morimi.eval.OnDeviceEvalRunner -e limit 5 \
 *     com.navoodi.morimi.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 채팅 원문은 기기 안 파일에만 남는다. 이 실행기는 클라우드를 호출하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEvalRunner {

    companion object { private const val TAG = "OnDeviceEval" }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as MoimApp
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun runOnDeviceEval() = runBlocking {
        val evalName = args.getString("evalFile") ?: "bulk_ondevice.json"
        val limit = args.getString("limit")?.toIntOrNull() ?: Int.MAX_VALUE
        val offset = args.getString("offset")?.toIntOrNull() ?: 0
        val reps = args.getString("reps")?.toIntOrNull() ?: 1
        val skipCompress = args.getString("skipCompress")?.toBoolean() ?: false

        val evalFile = File(ctx.getExternalFilesDir("models"), evalName)
        assumeTrue("평가 파일 없음: $evalFile", evalFile.exists())
        val llm = app.llmService
        assumeTrue("Gemma 모델 미준비", llm.isModelAvailable)

        val dialogues = JSONObject(evalFile.readText()).getJSONArray("dialogues")
        val end = minOf(dialogues.length(), offset + limit)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(ctx.getExternalFilesDir("eval"), "").apply { mkdirs() }
        val out = File(outDir, "ondevice_${evalName.removeSuffix(".json")}_$stamp.jsonl")
        Log.i(TAG, "평가 시작: $evalName [$offset, $end) reps=$reps → $out")

        // 모델 로드 시간은 분리 계측
        val tLoad0 = System.nanoTime()
        llm.initialize()
        val loadMs = (System.nanoTime() - tLoad0) / 1_000_000
        out.appendText(JSONObject().put("type", "meta").put("evalFile", evalName).put("offset", offset)
            .put("end", end).put("reps", reps).put("modelLoadMs", loadMs)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("startedAt", stamp).toString() + "\n")

        var done = 0
        for (i in offset until end) {
            val d = dialogues.getJSONObject(i)
            val id = d.getString("id")
            val msgs = d.getJSONArray("messages").let { a ->
                List(a.length()) { k ->
                    val m = a.getJSONObject(k)
                    Message(roomId = "__eval__", senderId = m.getString("pid"), senderName = m.getString("sender"),
                        content = m.getString("text"))
                }
            }
            // AssistantOrchestrator.buildKnownNames 와 동일 규칙(userStatus 없음): 발신자명
            val knownNames = msgs.map { it.senderName.trim() }.filter { it.isNotEmpty() }.distinct()

            for (rep in 1..reps) {
                val row = JSONObject().put("type", "result").put("id", id).put("rep", rep).put("nMessages", msgs.size)
                try {
                    val t0 = System.nanoTime()
                    val summary = llm.summarizeForPrivacy(msgs)
                    val t1 = System.nanoTime()
                    val scrub = PiiScrubber.scrub(summary, knownNames)
                    row.put("summaryRaw", summary).put("summaryScrubbed", scrub.text)
                        .put("redactions", scrub.redactions).put("redactionsByCategory", JSONObject(scrub.byCategory))
                        .put("tSummaryMs", (t1 - t0) / 1_000_000)

                    if (!skipCompress) {
                        val t2 = System.nanoTime()
                        val raw = llm.compressChatToStatus(msgs)
                        val t3 = System.nanoTime()
                        row.put("compressRaw", raw).put("compressPath", llm.lastCompressionPath ?: JSONObject.NULL)
                            .put("tCompressMs", (t3 - t2) / 1_000_000)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "[$id rep$rep] 실패", e)
                    row.put("error", "${e.javaClass.simpleName}: ${e.message}")
                }
                out.appendText(row.toString() + "\n")
            }
            done++
            if (done % 10 == 0) Log.i(TAG, "진행 $done/${end - offset}")
        }
        out.appendText(JSONObject().put("type", "meta").put("finishedAt",
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).put("done", done).toString() + "\n")
        Log.i(TAG, "평가 완료 $done 건 → $out")
        assertTrue(out.exists())
    }
}
