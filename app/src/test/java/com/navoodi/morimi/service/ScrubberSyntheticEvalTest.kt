package com.navoodi.morimi.service

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [평가] 합성 대화로 재는 `PiiScrubber` 이름 탐지 — **측정 전용**(임계값 단언 없음).
 *
 * ### 왜 합성셋인가
 *
 * 팀원의 대량 평가(`PiiScrubberBulkEvalTest`)는 AI Hub ZIP에서 만든
 * `scrubber_bulk.jsonl`이 필요한데, 그 원본이 외장 드라이브에 있어 이 환경에서는 못 돈다.
 * 합성셋(`synthetic_dialogues.py`)에도 **이름이 주입돼 있고 호격 형태가 들어 있어**
 * ("고마워 수빈아", "예약 부탁 우진아") 같은 질문을 작은 표본으로 답할 수 있다.
 *
 * ### 무엇을 재나
 *
 * 2026-09-13 실기기 평가가 지목한 미탐 원인을 조사(助詞)별로 쪼개 본다:
 *
 * > 명단 내 이름 미탐의 주원인은 **호격 조사**: 이름 뒤 `아` 77회, `야` 54회,
 * > `언(니)` 13, `형` 7, `쌤` 5. 현재 조사 목록에 아/야가 없다. (`docs/eval/RESULTS.md`)
 *
 * 표본이 작으므로(이름 등장 12회 수준) **비율보다 어떤 형태를 놓치는지**가 이 측정의 쓸모다.
 */
class ScrubberSyntheticEvalTest {

    private fun synthetic(): File =
        listOf(
            File(".eval-local/evalset/synthetic.json"),
            File("../.eval-local/evalset/synthetic.json"),
        ).firstOrNull { it.exists() } ?: File(".eval-local/evalset/synthetic.json")

    @Test
    fun 이름_탐지를_조사별로_쪼개_본다() {
        val f = synthetic()
        assumeTrue("평가 입력 없음: ${f.absolutePath} — scripts/eval/synthetic_dialogues.py 먼저 실행", f.exists())

        val dialogues = JSONObject(f.readText()).getJSONArray("dialogues")
        var gold = 0
        var detected = 0
        val missByNextChar = LinkedHashMap<String, Int>()
        val hitByNextChar = LinkedHashMap<String, Int>()
        val missExamples = ArrayList<String>()

        for (i in 0 until dialogues.length()) {
            val d = dialogues.getJSONObject(i)
            val names = d.getJSONArray("participants")
                .let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } }
            // 전형적 3자 이름의 '이름 부분'이 대화에 실제로 등장한다(김민수 → 민수)
            val given = names.filter { it.length == 3 }.map { it.substring(1) }

            val msgs = d.getJSONArray("messages")
            for (j in 0 until msgs.length()) {
                val text = msgs.getJSONObject(j).getString("text")
                val out = PiiScrubber.scrub(text, names).text

                for (g in given) {
                    var at = text.indexOf(g)
                    while (at >= 0) {
                        gold++
                        val next = text.getOrNull(at + g.length)?.toString() ?: "<끝>"
                        // 그 자리가 지워졌는지는 출력의 등장 횟수로 본다
                        if (count(out, g) < count(text, g)) {
                            detected++
                            hitByNextChar.merge(next, 1, Int::plus)
                        } else {
                            missByNextChar.merge(next, 1, Int::plus)
                            if (missExamples.size < 8) missExamples += "[$g$next] $text"
                        }
                        at = text.indexOf(g, at + g.length)
                    }
                }
            }
        }
        assumeTrue("이름 등장이 없다", gold > 0)

        println("═══ PiiScrubber 이름 탐지 (합성 n=${dialogues.length()}) ═══")
        println("※ 측정 전용. 표본이 작다 — 비율보다 '어떤 형태를 놓치는가'를 본다.")
        println("  이름 등장 %d회 중 탐지 %d회 (%.1f%%)".format(gold, detected, 100.0 * detected / gold))
        println("  탐지된 뒤 글자: " + hitByNextChar.entries.sortedByDescending { it.value })
        println("  미탐된 뒤 글자: " + missByNextChar.entries.sortedByDescending { it.value })
        if (missExamples.isNotEmpty()) {
            println("  미탐 예:")
            missExamples.forEach { println("    $it") }
        }

        val report = JSONObject()
            .put("gold", gold).put("detected", detected)
            .put("hit_by_next_char", JSONObject(hitByNextChar.toMap()))
            .put("miss_by_next_char", JSONObject(missByNextChar.toMap()))
        val outDir = File("build/eval").apply { mkdirs() }
        File(outDir, "scrubber_synthetic_report.json").writeText(report.toString(2))
        assertTrue(File(outDir, "scrubber_synthetic_report.json").exists())
    }

    private fun count(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var i = 0; var n = 0
        while (true) { i = hay.indexOf(needle, i); if (i < 0) return n; n++; i += needle.length }
    }
}
