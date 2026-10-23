package com.navoodi.morimi.service

import com.navoodi.morimi.data.local.UserStatusEntity
import com.navoodi.morimi.data.model.Message
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * [평가] 상황 분류 + 거름망 슬롯 추출 정량 측정 — **회귀 게이트가 아니라 측정**이다.
 *
 * `PiiScrubberBulkEvalTest`와 같은 방식이다: 임계값을 단언하지 않고, 입력이 없으면 스킵하고,
 * 콘솔 요약 + `build/eval/sieve_bulk_report.json`을 남긴다.
 *
 * ### 왜 JVM에서 도는가
 *
 * `ContextClassifier`·`PromptSieve`는 Android 의존성이 없는 순수 Kotlin이다. 팀원의 온디바이스
 * 평가(`OnDeviceEvalRunner`)는 Gemma가 필요해 실기기에서만 돌지만, 이 둘은 모델이 필요 없어
 * **기기 없이 잴 수 있다.** 그래서 평가 비용이 거의 0이다.
 *
 * ### 입력
 *
 * - `.eval-local/evalset/synthetic.json` — `scripts/eval/synthetic_dialogues.py` 산출물(정답 동봉)
 * - `scripts/eval/context_labels.json` — 상황 정답 라벨
 *
 * ### 이 측정의 한계 (반드시 같이 읽을 것)
 *
 * 1. **합성 대화 14건이다.** 실제 대화가 아니고 표본이 매우 작다.
 * 2. **상황 라벨은 독립적인 사람 라벨링이 아니다.** 합성셋이 원래 상황 분류용이 아니라서,
 *    저자가 `gold.purpose` 문자열에서 유도해 붙였다(분류기 실행 전에 커밋). 그래서
 *    **명확분(clear)과 전체를 나눠** 보고한다 — 모호분 5건은 어느 쪽으로도 읽힌다.
 * 3. 슬롯 정확도는 정답이 객관적이다(`date_absolute`·`places`·`likes`·`dislikes`).
 *    이쪽이 더 믿을 만한 수치다.
 */
class SieveBulkEvalTest {

    private fun evalDir(): File =
        System.getenv("MORIMI_EVAL_DIR")?.let(::File) ?: File(".eval-local/evalset")

    /** 저장소 루트 기준 `scripts/eval` — 테스트 작업 디렉터리가 `app/`일 수도 있어 둘 다 본다. */
    private fun labelsFile(): File =
        listOf(File("scripts/eval/context_labels.json"), File("../scripts/eval/context_labels.json"))
            .firstOrNull { it.exists() } ?: File("scripts/eval/context_labels.json")

    private fun synthetic(): File =
        listOf(File(evalDir(), "synthetic.json"), File("../.eval-local/evalset/synthetic.json"))
            .firstOrNull { it.exists() } ?: File(evalDir(), "synthetic.json")

    @Test
    fun 상황분류와_슬롯추출_정량측정() {
        val syn = synthetic(); val lab = labelsFile()
        assumeTrue("평가 입력 없음: ${syn.absolutePath} — scripts/eval/synthetic_dialogues.py 먼저 실행", syn.exists())
        assumeTrue("라벨 없음: ${lab.absolutePath}", lab.exists())

        val dialogues = JSONObject(syn.readText()).getJSONArray("dialogues")
        val labels = JSONObject(lab.readText()).getJSONArray("labels")
            .let { a -> (0 until a.length()).associate { i -> a.getJSONObject(i).let { it.getString("id") to it } } }

        // ── 집계 ────────────────────────────────────────────────────────────
        var n = 0
        var ctxClearTotal = 0; var ctxClearHit = 0
        var ctxAmbTotal = 0; var ctxAmbHitPrimary = 0; var ctxAmbHitEither = 0
        val confusion = LinkedHashMap<String, Int>()      // "정답>예측" 빈도
        var whenTotal = 0; var whenHit = 0
        var whereTotal = 0; var whereHit = 0
        var prefTotal = 0; var prefHit = 0
        var consTotal = 0; var consHit = 0
        var hourTotal = 0; var hourHit = 0        // 최종 '시각'이 슬롯에 들어갔는가
        var markerTotal = 0; var markerKept = 0   // 대화에 시간대 표현이 있었는데 슬롯이 지켰는가
        val hourMiss = ArrayList<String>()
        val markerMiss = ArrayList<String>()
        var nameLeak = 0                                   // 블록에 참가자 이름이 남은 건수
        var rawLeak = 0                                    // 블록에 채팅 원문 문장이 남은 건수
        val perCase = JSONArray()

        for (i in 0 until dialogues.length()) {
            val d = dialogues.getJSONObject(i)
            val id = d.getString("id")
            val label = labels[id] ?: continue
            n++
            val gold = d.getJSONObject("gold")
            val chatDate = LocalDate.parse(d.getString("chat_date"))

            val names = d.getJSONArray("participants")
                .let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } }
            val msgs = d.getJSONArray("messages").let { a ->
                List(a.length()) {
                    val m = a.getJSONObject(it)
                    Message(
                        roomId = id,
                        senderId = m.getString("pid"),
                        senderName = m.getString("sender"),
                        content = m.getString("text"),
                    )
                }
            }
            val likes = gold.strList("likes")
            val dislikes = gold.strList("dislikes")
            val status = UserStatusEntity(
                roomId = id,
                participants = names,
                preferences = likes.map { "좋아요: $it" } + dislikes.map { "싫어요: $it" },
                availability = gold.strList("availability"),
            )

            // ── 상황 분류 ───────────────────────────────────────────────────
            val c = ContextClassifier.classify(msgs)
            val got = c.context.name
            val primary = label.getString("primary")
            val ambiguous = label.optBoolean("ambiguous", false)
            val alt = label.optString("alt", "")
            confusion.merge("$primary>$got", 1, Int::plus)
            if (ambiguous) {
                ctxAmbTotal++
                if (got == primary) ctxAmbHitPrimary++
                if (got == primary || got == alt) ctxAmbHitEither++
            } else {
                ctxClearTotal++
                if (got == primary) ctxClearHit++
            }

            // ── 거름망 ──────────────────────────────────────────────────────
            val s = PromptSieve.sieve(
                messages = msgs,
                safeSummary = d.optString("reference_summary", ""),
                userStatus = status,
                chatDate = chatDate,
                ahp = AhpEngine.solve(c.context.basePairwiseMatrix()),
                classification = c,
            )
            val frame = s.frame

            // 날짜: 정답이 절대 날짜로 있다 → 슬롯이 그 날짜를 담았는가
            val goldDate = gold.optString("date_absolute", "")
            if (goldDate.isNotBlank()) {
                whenTotal++
                if (s.slot(FrameSlot.WHEN).contains(goldDate)) whenHit++
            }
            // 지역: 정답 후보 중 하나라도 슬롯에 들어갔는가 ("(취소)" 표기는 제외)
            val goldPlaces = gold.strList("places").filterNot { it.contains("취소") }
            if (goldPlaces.isNotEmpty()) {
                whereTotal++
                val w = s.slot(FrameSlot.WHERE)
                if (goldPlaces.any { p -> w.contains(p) || p.contains(w) && w != SievedPrompt.UNSPECIFIED }) whereHit++
            }
            // 선호·제약: 프로필에서 온 값이 블록에 실렸는가 (전달 손실 측정)
            if (likes.isNotEmpty()) {
                prefTotal++
                if (likes.any { frame.contains(it) }) prefHit++
            }
            if (dislikes.isNotEmpty()) {
                consTotal++
                if (dislikes.any { frame.contains(it) }) consHit++
            }

            // ── 시간대 ──────────────────────────────────────────────────────
            //
            // **채점 기준(측정 전에 선언)**:
            //  · 시각 — `date_expressions`의 **마지막** 표현에 있는 "N시"가 슬롯에 있는가.
            //    마지막을 보는 이유는 대화가 그 값으로 합의했기 때문이다(날짜와 같은 규칙).
            //  · 시간대 표현 — 표현들 어딘가에 아침/오전/점심/낮/저녁/밤/새벽이 있었다면
            //    슬롯이 그것을 지켰는가. **이쪽은 합격/불합격이 아니라 관찰**이다 —
            //    "7시"만 남아도 틀린 값은 아니지만, 아침 7시인지 저녁 7시인지 모르게 된다.
            val exprs = gold.strList("date_expressions")
            val slotTime = s.slot(FrameSlot.TIME_OF_DAY)
            val lastHour = exprs.lastOrNull()?.let { Regex("""(\d{1,2})\s*시""").find(it)?.groupValues?.get(1) }
            if (lastHour != null) {
                hourTotal++
                if (slotTime.contains(lastHour + "시")) hourHit++
                else hourMiss += "$id 기대 " + lastHour + "시 → 슬롯 [" + slotTime + "] (표현: " + exprs + ")"
            }
            val markers = listOf("아침", "오전", "점심", "낮", "저녁", "밤", "새벽")
            val goldMarker = exprs.firstNotNullOfOrNull { e -> markers.firstOrNull { e.contains(it) } }
            if (goldMarker != null) {
                markerTotal++
                if (slotTime.contains(goldMarker)) markerKept++
                else markerMiss += "$id 대화엔 '" + goldMarker + "' → 슬롯 [" + slotTime + "]"
            }

            // ── 프라이버시: 블록에 이름·원문이 남았는가 ──────────────────────
            val leakedNames = names.filter { frame.contains(it) } +
                names.mapNotNull { if (it.length == 3) it.substring(1) else null }.filter { frame.contains(it) }
            if (leakedNames.isNotEmpty()) nameLeak++

            // 원문 유출은 **슬롯 영역에서만** 센다.
            //
            // 처음엔 블록 전문에서 셌다가 50%가 나왔는데, 열어 보니 전부 오탐이었다:
            //  - 6건은 요약문이 짧은 메시지와 문구가 겹친 것(요약문은 설계상 전송 대상이다)
            //  - 1건은 프로필의 `가능 일정` 값("토요일 저녁 가능")을 참가자가 그대로 타이핑한 것
            // 둘 다 원문이 새는 경로가 아니다. 물어야 할 것은 **슬롯 값이 원문 문장을 담는가**다.
            val slotRegion = frame.substringBefore("[판단 기준 가중치")
                .lines().filterNot { it.startsWith("가능 일정:") }.joinToString(System.lineSeparator())
            val leakedRaw = msgs.count { m -> m.content.length >= 8 && slotRegion.contains(m.content) }
            if (leakedRaw > 0) rawLeak++

            perCase.put(
                JSONObject()
                    .put("id", id).put("case", d.optString("case", ""))
                    .put("gold_context", primary).put("got_context", got)
                    .put("ambiguous", ambiguous)
                    .put("when_ok", goldDate.isBlank() || s.slot(FrameSlot.WHEN).contains(goldDate))
                    // 집계와 **같은 식**을 써야 한다. 예전엔 여기만 contains 단방향이라
                    // "망원"(슬롯) vs "망원동"(정답)이 집계는 통과하고 per_case는 실패로 보였다.
                    .put("where_ok", goldPlaces.isEmpty() || goldPlaces.any { p ->
                        val w = s.slot(FrameSlot.WHERE)
                        w.contains(p) || (p.contains(w) && w != SievedPrompt.UNSPECIFIED)
                    })
                    .put("missing_slots", JSONArray(s.missing.map { it.label }))
                    .put("when_slot", s.slot(FrameSlot.WHEN))
                    .put("when_gold", goldDate)
                    .put("where_slot", s.slot(FrameSlot.WHERE))
                    .put("name_leak", leakedNames)
            )
        }
        assumeTrue("레코드 없음", n > 0)

        // ── 보고 ────────────────────────────────────────────────────────────
        fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%% (%d/%d)".format(100.0 * a / b, a, b)

        println("═══ 상황 분류 + 거름망 정량 측정 (합성 n=$n) ═══")
        println("※ 회귀 게이트가 아니라 측정이다. 표본이 작고 상황 라벨은 저자가 gold.purpose에서 유도했다.")
        println()
        println("[상황 분류]")
        println("  명확분 정확도        ${pct(ctxClearHit, ctxClearTotal)}   ← 이 수치를 주로 쓸 것")
        println("  모호분 primary 일치  ${pct(ctxAmbHitPrimary, ctxAmbTotal)}")
        println("  모호분 primary|alt   ${pct(ctxAmbHitEither, ctxAmbTotal)}")
        println("  전체(primary 기준)   ${pct(ctxClearHit + ctxAmbHitPrimary, n)}")
        println("  혼동(정답>예측): " + confusion.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" })
        println()
        println("[거름망 슬롯 추출 — 정답이 객관적인 항목]")
        println("  일시(절대 날짜 일치) ${pct(whenHit, whenTotal)}")
        println("  지역                 ${pct(whereHit, whereTotal)}")
        println("  선호 전달            ${pct(prefHit, prefTotal)}")
        println("  제약(싫어요) 전달    ${pct(consHit, consTotal)}")
        println("  시각(마지막 합의)    ${pct(hourHit, hourTotal)}")
        hourMiss.forEach { println("      미탐: $it") }
        println()
        println("[시간대 표현 보존 — 합격/불합격이 아니라 관찰]")
        println("  대화에 시간대가 있던 ${markerTotal}건 중 슬롯이 지킨 것 ${pct(markerKept, markerTotal)}")
        markerMiss.take(6).forEach { println("      소실: $it") }
        println()
        println("[프라이버시 — 전송 블록]")
        println("  참가자 이름 유출     ${pct(nameLeak, n)}")
        println("  슬롯 값의 원문 유출  ${pct(rawLeak, n)}   ← 요약문·프로필은 설계상 전송 대상이라 제외")

        val report = JSONObject()
            .put("n", n)
            .put("note", "측정 전용. 상황 라벨은 scripts/eval/context_labels.json — 저자가 gold.purpose에서 유도")
            .put("context", JSONObject()
                .put("clear_total", ctxClearTotal).put("clear_hit", ctxClearHit)
                .put("ambiguous_total", ctxAmbTotal)
                .put("ambiguous_hit_primary", ctxAmbHitPrimary)
                .put("ambiguous_hit_either", ctxAmbHitEither)
                .put("confusion", JSONObject(confusion.toMap())))
            .put("slots", JSONObject()
                .put("when", JSONObject().put("total", whenTotal).put("hit", whenHit))
                .put("where", JSONObject().put("total", whereTotal).put("hit", whereHit))
                .put("preferences", JSONObject().put("total", prefTotal).put("hit", prefHit))
                .put("constraints", JSONObject().put("total", consTotal).put("hit", consHit))
                .put("hour", JSONObject().put("total", hourTotal).put("hit", hourHit))
                .put("time_marker", JSONObject().put("total", markerTotal).put("kept", markerKept)))
            .put("privacy", JSONObject().put("name_leak", nameLeak).put("raw_text_leak", rawLeak))
            .put("per_case", perCase)

        val outDir = File("build/eval").apply { mkdirs() }
        File(outDir, "sieve_bulk_report.json").writeText(report.toString(2))
        println("\n리포트: ${File(outDir, "sieve_bulk_report.json").absolutePath}")
        assertTrue(File(outDir, "sieve_bulk_report.json").exists())
    }

    private fun JSONObject.strList(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }
}
