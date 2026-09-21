package com.navoodi.morimi.service

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [평가] PiiScrubber 단독 대량 검증 — 회귀 게이트가 아니라 **측정**이다(임계값 단언 없음).
 *
 * 입력: `scripts/eval/build_evalset.py`가 만든 `.eval-local/evalset/scrubber_bulk.jsonl`
 * (AI Hub 대화에 합성 이름·전화·이메일을 주입한 것 — 주입값이 정답). 파일이 없으면 스킵.
 * 출력: 콘솔 요약 + `build/eval/scrubber_bulk_report.json`.
 *
 * 정답·잔존 계산은 문자열 등장 횟수로 통일한다(입력 등장 − 출력 잔존 = 탐지).
 * 오탐 = 카테고리별 마스킹 수 − 탐지 수, 그리고 마스킹되면 안 되는 호칭어(decoy) 소실 수.
 */
class PiiScrubberBulkEvalTest {

    private data class Cat(var gold: Int = 0, var detected: Int = 0, var masks: Int = 0) {
        val recall get() = if (gold == 0) Double.NaN else detected.toDouble() / gold
        val precision get() = if (masks == 0) Double.NaN else detected.toDouble() / masks
        fun toJson() = JSONObject().put("gold", gold).put("detected", detected).put("masks", masks)
            .put("recall", if (recall.isNaN()) JSONObject.NULL else recall)
            .put("precision", if (precision.isNaN()) JSONObject.NULL else precision)
    }

    @Test
    fun scrubber_bulk_precision_recall() {
        val dir = System.getenv("MORIMI_EVAL_DIR")?.let(::File) ?: File("../.eval-local/evalset")
        val input = File(dir, "scrubber_bulk.jsonl")
        assumeTrue("평가 입력 없음: ${input.absolutePath}", input.exists())

        val names = Cat(); val knownNames = Cat(); val thirdNames = Cat()
        val phones = Cat(); val emails = Cat()
        var records = 0
        var decoyTotal = 0; var decoyLost = 0
        var textsWithPii = 0; var textsFullyClean = 0
        val residualNextChar = HashMap<String, Int>()      // 미탐 이름(명단내) 뒤에 온 글자 — 조사 목록 진단
        val residualNextCharThird = HashMap<String, Int>() // 미탐 이름(명단외) 뒤에 온 글자
        val decoyLostWords = HashMap<String, Int>()

        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = JSONObject(line)
                records++
                val text = o.getString("text")
                val known = o.getJSONArray("known_names").let { a -> List(a.length()) { a.getString(it) } }
                val knownGiven = known.filter { it.length == 3 }.map { it.substring(1) }.toSet()
                val inText = o.getJSONObject("in_text_names").keys().asSequence().toList()
                val phoneList = o.getJSONArray("phones").let { a -> List(a.length()) { a.getString(it) } }
                val emailList = o.getJSONArray("emails").let { a -> List(a.length()) { a.getString(it) } }
                val decoys = o.getJSONArray("decoys").let { a -> List(a.length()) { a.getString(it) } }

                val r = PiiScrubber.scrub(text, known)
                val out = r.text

                var goldHere = 0; var residualHere = 0
                for (n in inText) {
                    val g = count(text, n); val res = count(out, n)
                    val cat = if (n in knownGiven) knownNames else thirdNames
                    cat.gold += g; cat.detected += g - res
                    names.gold += g; names.detected += g - res
                    goldHere += g; residualHere += res
                    if (res > 0) {
                        val target = if (n in knownGiven) residualNextChar else residualNextCharThird
                        nextChars(out, n).forEach { c -> target.merge(c, 1, Int::plus) }
                    }
                }
                names.masks += r.byCategory[PiiScrubber.CATEGORY_NAME] ?: 0
                for (p in phoneList) {
                    val g = count(text, p); val res = count(out, p)
                    phones.gold += g; phones.detected += g - res; goldHere += g; residualHere += res
                }
                phones.masks += r.byCategory[PiiScrubber.CATEGORY_PHONE] ?: 0
                for (e in emailList) {
                    val g = count(text, e); val res = count(out, e)
                    emails.gold += g; emails.detected += g - res; goldHere += g; residualHere += res
                }
                emails.masks += r.byCategory[PiiScrubber.CATEGORY_EMAIL] ?: 0

                for (d in decoys) {
                    val g = count(text, d); val res = count(out, d)
                    decoyTotal += g
                    if (g > res) { decoyLost += g - res; decoyLostWords.merge(d, g - res, Int::plus) }
                }
                if (goldHere > 0) { textsWithPii++; if (residualHere == 0) textsFullyClean++ }
            }
        }
        assumeTrue("레코드 없음", records > 0)

        val report = JSONObject()
            .put("records", records)
            .put("texts_with_pii", textsWithPii)
            .put("texts_fully_clean", textsFullyClean)
            .put("names_all", names.toJson())
            .put("names_known_participants", knownNames.toJson())
            .put("names_third_party", thirdNames.toJson())
            .put("phones", phones.toJson())
            .put("emails", emails.toJson())
            .put("decoys", JSONObject().put("total", decoyTotal).put("lost", decoyLost)
                .put("lost_words", JSONObject(decoyLostWords.toSortedMap(compareByDescending { decoyLostWords[it] }).toMap())))
            .put("residual_known_name_next_char_top", JSONObject(
                residualNextChar.entries.sortedByDescending { it.value }.take(20).associate { it.key to it.value }))
            .put("residual_third_party_next_char_top", JSONObject(
                residualNextCharThird.entries.sortedByDescending { it.value }.take(20).associate { it.key to it.value }))

        println("═══ PiiScrubber 대량 평가 (n=$records) ═══")
        println("PII 포함 텍스트 $textsWithPii 건 중 완전 제거 $textsFullyClean 건 (${pct(textsFullyClean, textsWithPii)})")
        fun row(label: String, c: Cat) =
            println("%-14s gold=%5d detected=%5d masks=%5d recall=%s precision=%s".format(
                label, c.gold, c.detected, c.masks, fmt(c.recall), fmt(c.precision)))
        row("이름(전체)", names); row("이름(명단내)", knownNames); row("이름(명단외)", thirdNames)
        row("전화", phones); row("이메일", emails)
        println("호칭어 대조군: $decoyTotal 건 중 소실 $decoyLost 건 (${pct(decoyLost, decoyTotal)}) — $decoyLostWords")
        println("미탐 이름(명단내) 뒤 글자 상위: ${residualNextChar.entries.sortedByDescending { it.value }.take(12)}")
        println("미탐 이름(명단외) 뒤 글자 상위: ${residualNextCharThird.entries.sortedByDescending { it.value }.take(12)}")

        val outDir = File("build/eval").apply { mkdirs() }
        File(outDir, "scrubber_bulk_report.json").writeText(report.toString(2))
        assertTrue(File(outDir, "scrubber_bulk_report.json").exists())
    }

    private fun count(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var i = 0; var n = 0
        while (true) { i = hay.indexOf(needle, i); if (i < 0) return n; n++; i += needle.length }
    }

    private fun nextChars(hay: String, needle: String): List<String> {
        val res = ArrayList<String>(); var i = 0
        while (true) {
            i = hay.indexOf(needle, i); if (i < 0) return res
            val j = i + needle.length
            res += if (j < hay.length) hay[j].toString().let { if (it == "\n") "⏎" else it } else "<끝>"
            i = j
        }
    }

    private fun fmt(d: Double) = if (d.isNaN()) "n/a" else "%.3f".format(d)
    private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%%".format(100.0 * a / b)
}
