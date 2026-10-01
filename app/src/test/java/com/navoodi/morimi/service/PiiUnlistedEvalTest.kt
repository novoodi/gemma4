package com.navoodi.morimi.service

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [평가] 명단 밖 이름 탐지 도입 전후 비교 — 합성 대화(`scripts/eval/synthetic_dialogues.py` 산출물).
 *
 * 같은 입력을 `detectUnlisted=false`(이전 동작)와 `true`(현재)로 스크러빙해 비교한다.
 * - 명단 내 이름 재현율: 떨어지면 안 된다
 * - 명단 밖(제3자) 이름 재현율: 이번 수정의 목표
 * - 과잉 마스킹(이름 마스크 − 실제 지운 이름): 늘면 안 된다 — 지역·가게·일반명사 오탐
 * 입력 파일이 없으면 스킵한다(기존 평가 테스트와 같은 규칙).
 */
class PiiUnlistedEvalTest {

    private data class Tally(var knownGold: Int = 0, var knownHit: Int = 0, var thirdGold: Int = 0, var thirdHit: Int = 0,
                             var masks: Int = 0, var removed: Int = 0) {
        val overMask get() = masks - removed
    }

    @Test
    fun 명단밖_이름_탐지_전후_비교() {
        val f = listOf(File(".eval-local/evalset/synthetic.json"), File("../.eval-local/evalset/synthetic.json"))
            .firstOrNull { it.exists() }
        assumeTrue("평가 입력 없음 — scripts/eval/synthetic_dialogues.py 먼저 실행", f != null)

        val before = Tally(); val after = Tally(); val flow = Tally()
        val dialogues = JSONObject(f!!.readText()).getJSONArray("dialogues")
        for (i in 0 until dialogues.length()) {
            val d = dialogues.getJSONObject(i)
            val roster = d.getJSONArray("participants").let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } }
            val given = roster.filter { it.length == 3 }.map { it.substring(1) }
            val pii = d.getJSONObject("gold").getJSONObject("pii_injected")
            val third = pii.getJSONArray("third_party_names").let { a -> List(a.length()) { a.getString(it) } }
            val msgs = d.getJSONArray("messages")
            // 오케스트레이터 경로: 대화 전체에서 찾은 명단 밖 이름을 명단에 더한다(buildKnownNames)
            val harvested = (0 until msgs.length()).flatMap { PiiScrubber.detectNames(msgs.getJSONObject(it).getString("text")) }
            for (j in 0 until msgs.length()) {
                val text = msgs.getJSONObject(j).getString("text")
                for ((t, mode) in listOf(before to 0, after to 1, flow to 2)) {
                    val r = when (mode) {
                        0 -> PiiScrubber.scrub(text, roster, detectUnlisted = false)
                        1 -> PiiScrubber.scrub(text, roster)
                        else -> PiiScrubber.scrub(text, roster + harvested)
                    }
                    t.masks += r.byCategory[PiiScrubber.CATEGORY_NAME] ?: 0
                    for (g in given) {
                        val n = count(text, g); val left = count(r.text, g)
                        t.knownGold += n; t.knownHit += n - left; t.removed += n - left
                    }
                    for (g in third) {
                        val n = count(text, g); val left = count(r.text, g)
                        t.thirdGold += n; t.thirdHit += n - left; t.removed += n - left
                    }
                }
            }
        }

        println("═══ 명단 밖 이름 탐지 전후 (합성 n=${dialogues.length()}) ═══")
        for ((label, t) in listOf("이전" to before, "현재(문장 단위)" to after, "현재(대화 명단 포함)" to flow)) {
            println("  %s  명단내 %d/%d  명단밖 %d/%d  이름마스크 %d  과잉마스킹 %d".format(
                label, t.knownHit, t.knownGold, t.thirdHit, t.thirdGold, t.masks, t.overMask))
        }
        val outDir = File("build/eval").apply { mkdirs() }
        File(outDir, "scrubber_unlisted_report.json").writeText(JSONObject()
            .put("before", JSONObject().put("known", "${before.knownHit}/${before.knownGold}").put("third", "${before.thirdHit}/${before.thirdGold}").put("over_mask", before.overMask))
            .put("flow", JSONObject().put("known", "${flow.knownHit}/${flow.knownGold}").put("third", "${flow.thirdHit}/${flow.thirdGold}").put("over_mask", flow.overMask))
            .put("after", JSONObject().put("known", "${after.knownHit}/${after.knownGold}").put("third", "${after.thirdHit}/${after.thirdGold}").put("over_mask", after.overMask))
            .toString(2))

        assertTrue("명단 내 재현율 하락", after.knownHit >= before.knownHit)
        assertTrue("과잉 마스킹 증가 ${before.overMask} → ${after.overMask}", after.overMask <= before.overMask)
        assertTrue("과잉 마스킹 증가(대화 명단) ${before.overMask} → ${flow.overMask}", flow.overMask <= before.overMask)
    }

    private fun count(hay: String, needle: String): Int {
        var c = 0; var i = hay.indexOf(needle)
        while (i >= 0) { c++; i = hay.indexOf(needle, i + needle.length) }
        return c
    }
}
