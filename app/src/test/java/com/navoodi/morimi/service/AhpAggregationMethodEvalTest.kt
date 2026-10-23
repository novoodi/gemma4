package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * [평가] 종합 단계 비교 — **단순가중합(SAW) vs TOPSIS**. 측정 전용(임계값 단언 없음).
 *
 * ### 왜 재나
 *
 * 우리 AHP는 단계가 둘이고 **단계마다 다른 기법**을 쓴다:
 *
 * | 단계 | 우리 방식 |
 * |---|---|
 * | 기준 가중치 산출 | AHP (쌍대비교 → 행 기하평균 → CR) |
 * | 후보 종합·정렬 | **단순가중합 (SAW/WSM)** — `AhpEngine.synthesize` |
 *
 * 즉 **②는 교수님이 지정한 부분이 아니라 우리 선택**이다. 그러면 따라오는 질문이 있다:
 *
 * > *"종합 방법을 바꾸면 답이 달라지나?"*
 * > *"가중합이라서 1위가 그렇게 나온 건 아닌가?"*
 * >
 * 안 재고는 답할 수 없다. `AhpWeightMethodEvalTest`(고유벡터 vs 기하평균)와 **같은 방식**으로
 * 감도를 잰다 — **교체가 아니라 병행 계산**이라 코드 동작은 그대로다.
 *
 * ### 왜 TOPSIS인가
 *
 * SAW의 알려진 약점이 **기준별 척도 차이를 보정하지 않는 것**이다. 우리도 이 약점이 있다 —
 * 검증 신뢰는 사실상 3값(1.0 / 0.5 / 0.0)인데 취향 적합은 연속값이다.
 * TOPSIS는 **벡터 정규화를 먼저** 해서 이 차이를 없앤다. 우리 약점을 정확히 겨냥한 대안이다.
 *
 * 다만 TOPSIS에는 우리가 대안 쌍대비교를 거부한 것과 **같은 함정**이 있다:
 * 정규화 분모가 **후보 집합 전체**에서 나오므로 후보가 하나 바뀌면 나머지의 정규화 값이
 * 전부 달라진다(순위 역전). 우리 후보는 매 실행 Gemini가 새로 만들어 집합이 계속 바뀐다.
 * **그래서 채택 후보가 아니라 감도 측정 도구로만 쓴다.**
 *
 * ### n=2에서도 두 방법은 다르다 (수식으로)
 *
 * 후보 2개일 때 TOPSIS 근접도 비교는
 * `Σ_{1이 나은 기준}(wΔ)²  vs  Σ_{2가 나은 기준}(wΔ)²` 로 환원되고,
 * SAW는 `Σ_{1이 나은}wΔ vs Σ_{2가 나은}wΔ` 다.
 * **제곱이 큰 격차를 증폭**하므로 두 방법은 원리적으로 어긋날 수 있다 — 실제로 얼마나인지가 이 측정이다.
 */
class AhpAggregationMethodEvalTest {

    // ── TOPSIS ───────────────────────────────────────────────────────────────

    /**
     * TOPSIS 근접도 계수 C_i = D⁻ / (D⁺ + D⁻). 클수록 좋다.
     *
     * 우리 기준 5개는 **전부 편익형**(클수록 좋음)이라 비용형 반전이 없다.
     * 열이 전부 0이면 정규화 분모가 0이 되므로 그 열은 0으로 둔다.
     */
    private fun topsisCloseness(
        breakdowns: List<Map<AhpCriterion, Double>>,
        ahp: AhpResult,
    ): List<Double> {
        val criteria = AhpCriterion.entries.toList()
        val n = breakdowns.size
        if (n == 0) return emptyList()

        // 1) 벡터 정규화 후 가중치 적용
        val v = Array(n) { DoubleArray(criteria.size) }
        criteria.forEachIndexed { j, c ->
            val col = breakdowns.map { it[c] ?: 0.0 }
            val norm = sqrt(col.sumOf { it * it })
            val w = ahp.weightOf(c)
            for (i in 0 until n) v[i][j] = if (norm > 1e-12) w * col[i] / norm else 0.0
        }

        // 2) 이상해 / 반이상해
        val best = DoubleArray(criteria.size) { j -> (0 until n).maxOf { v[it][j] } }
        val worst = DoubleArray(criteria.size) { j -> (0 until n).minOf { v[it][j] } }

        // 3) 거리 → 근접도
        return (0 until n).map { i ->
            var dp = 0.0
            var dm = 0.0
            for (j in criteria.indices) {
                dp += (v[i][j] - best[j]) * (v[i][j] - best[j])
                dm += (v[i][j] - worst[j]) * (v[i][j] - worst[j])
            }
            dp = sqrt(dp); dm = sqrt(dm)
            // 후보가 전부 동일하면 dp=dm=0 — 구분 불가이므로 동점(0.5)으로 둔다
            if (dp + dm < 1e-12) 0.5 else dm / (dp + dm)
        }
    }

    private fun saw(breakdowns: List<Map<AhpCriterion, Double>>, ahp: AhpResult): List<Double> =
        breakdowns.map { AhpEngine.synthesize(it, ahp) }

    /** 점수 내림차순 인덱스. 동점은 인덱스 순으로 안정 정렬(두 방법 모두 같은 규칙). */
    private fun order(scores: List<Double>): List<Int> =
        scores.indices.sortedWith(compareByDescending<Int> { round6(scores[it]) }.thenBy { it })

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    private fun ahpOf(ctx: MeetingContext) = AhpEngine.solve(ctx.basePairwiseMatrix())

    // ── 측정 1: 실제 시나리오 픽스처 ────────────────────────────────────────

    /**
     * 발표에 쓰는 **실제 시나리오 후보**에서 1위가 바뀌나.
     *
     * 이게 가장 중요한 물음이다 — 일반론보다 *우리가 보여줄 그 표*가 흔들리는지가 문제다.
     */
    @Test
    fun 실제_시나리오_후보에서_1위가_바뀌는지_본다() {
        val prefs = listOf("좋아요: 조용한 곳", "싫어요: 시끄러운 술집")
        val fixtures = listOf(
            "시나리오 A·B 후보 3곳" to listOf(
                RecommendedPlace("루프탑 다이닝", reason = "분위기 좋은 루프탑 코스요리 레스토랑", verification = VerificationStatus.VERIFIED),
                RecommendedPlace("골목 포차", reason = "골목 안쪽 아늑한 포차", verification = VerificationStatus.VERIFIED),
                RecommendedPlace("시끌벅적 호프", reason = "시끄러운 술집 분위기의 호프", verification = VerificationStatus.VERIFIED),
            ),
            "시나리오 C 후보 3곳(검증 상태 혼합)" to listOf(
                RecommendedPlace("조용한 미식당", reason = "조용한 곳에서 식사 가능한 한식당", verification = VerificationStatus.NOT_FOUND),
                RecommendedPlace("연남 한식당", reason = "차분한 분위기의 한식당", verification = VerificationStatus.VERIFIED),
                RecommendedPlace("확인불가 식당", reason = "조용한 곳으로 알려진 식당", verification = VerificationStatus.UNVERIFIED),
            ),
        )

        println("═══ 실제 시나리오 픽스처: SAW vs TOPSIS ═══")
        println("※ 측정 전용. 실행불가(NOT_FOUND) 후보는 두 방법 모두 정렬 최하위 — 종합 단계 비교는 실행가능분으로 한다.")

        var cases = 0
        var top1Diff = 0
        var rankDiff = 0

        for ((label, places) in fixtures) {
            println()
            println("── $label")
            for (ctx in MeetingContext.entries) {
                val ahp = ahpOf(ctx)
                val ranked = PlaceRanker.rank(places, ctx, ahp, prefs)
                val feasible = ranked.filter { it.feasible }
                if (feasible.size < 2) continue

                val bd = feasible.map { it.breakdown }
                val sawS = saw(bd, ahp)
                val topS = topsisCloseness(bd, ahp)
                val sawO = order(sawS)
                val topO = order(topS)

                cases++
                val sameTop1 = sawO.first() == topO.first()
                val sameRank = sawO == topO
                if (!sameTop1) top1Diff++
                if (!sameRank) rankDiff++

                val mark = if (!sameTop1) "★1위 바뀜" else if (!sameRank) "△하위 순서만" else "일치"
                println(
                    "   %-12s SAW: %-14s TOPSIS: %-14s  %s".format(
                        ctx.name,
                        feasible[sawO.first()].place.name,
                        feasible[topO.first()].place.name,
                        mark,
                    ),
                )
                if (!sameRank) {
                    println("        SAW    " + sawO.joinToString(" > ") { "%s(%.3f)".format(feasible[it].place.name, sawS[it]) })
                    println("        TOPSIS " + topO.joinToString(" > ") { "%s(%.3f)".format(feasible[it].place.name, topS[it]) })
                }
            }
        }

        println()
        println("  사례 %d건 중 1위 불일치 %d건 / 전체 순위 불일치 %d건".format(cases, top1Diff, rankDiff))
        assertTrue("사례가 만들어지지 않았다", cases > 0)
    }

    // ── 측정 2: 루브릭 값 전역 스윕 ─────────────────────────────────────────

    /**
     * 픽스처 몇 개로는 "우연히 같았다"를 배제할 수 없다. **루브릭이 실제로 내는 값**으로
     * 후보 프로필을 전수 생성해 쌍마다 비교한다.
     *
     * 값 집합은 `PlaceRanker`의 실제 코드에서 왔다:
     *  - 목적 적합 `{0.2, 0.5, 1.0}` · 취향 적합 `{0.2, 0.5, 1.0}`
     *  - 제약 준수 `{0.0, 1.0}` (하드) · 검증 신뢰 `{0.5, 1.0}` (0.0=NOT_FOUND는 게이트가 제외)
     *  - 과거 만족 `{0.0, 0.5, 1.0}`
     */
    @Test
    fun 루브릭_값_전역에서_1위가_바뀌는_빈도를_잰다() {
        val values = mapOf(
            AhpCriterion.PURPOSE_FIT to listOf(0.2, 0.5, 1.0),
            AhpCriterion.PREFERENCE_FIT to listOf(0.2, 0.5, 1.0),
            AhpCriterion.CONSTRAINT_SAFETY to listOf(0.0, 1.0),
            AhpCriterion.VERIFIED_TRUST to listOf(0.5, 1.0),
            AhpCriterion.PAST_SATISFACTION to listOf(0.0, 0.5, 1.0),
        )

        // 가능한 후보 프로필 전수 생성 (3×3×2×2×3 = 108)
        val profiles = ArrayList<Map<AhpCriterion, Double>>()
        for (p in values.getValue(AhpCriterion.PURPOSE_FIT))
            for (f in values.getValue(AhpCriterion.PREFERENCE_FIT))
                for (c in values.getValue(AhpCriterion.CONSTRAINT_SAFETY))
                    for (v in values.getValue(AhpCriterion.VERIFIED_TRUST))
                        for (s in values.getValue(AhpCriterion.PAST_SATISFACTION))
                            profiles += mapOf(
                                AhpCriterion.PURPOSE_FIT to p,
                                AhpCriterion.PREFERENCE_FIT to f,
                                AhpCriterion.CONSTRAINT_SAFETY to c,
                                AhpCriterion.VERIFIED_TRUST to v,
                                AhpCriterion.PAST_SATISFACTION to s,
                            )

        println("═══ 루브릭 값 전역 스윕: SAW vs TOPSIS ═══")
        println("※ 측정 전용. 후보 프로필 %d개, 상황 9종.".format(profiles.size))

        // ── 쌍 전수 ──
        var pairN = 0
        var pairTop1 = 0
        val flipExamples = ArrayList<String>()
        val byContext = LinkedHashMap<String, Pair<Int, Int>>()

        for (ctx in MeetingContext.entries) {
            val ahp = ahpOf(ctx)
            var n = 0
            var diff = 0
            for (i in profiles.indices) {
                for (j in i + 1 until profiles.size) {
                    val bd = listOf(profiles[i], profiles[j])
                    val sawO = order(saw(bd, ahp))
                    val topO = order(topsisCloseness(bd, ahp))
                    n++
                    if (sawO.first() != topO.first()) {
                        diff++
                        if (flipExamples.size < 6) {
                            flipExamples += "%s  A=%s B=%s  SAW→%s / TOPSIS→%s".format(
                                ctx.name, fmt(profiles[i]), fmt(profiles[j]),
                                if (sawO.first() == 0) "A" else "B",
                                if (topO.first() == 0) "A" else "B",
                            )
                        }
                    }
                }
            }
            byContext[ctx.name] = n to diff
            pairN += n
            pairTop1 += diff
        }

        println()
        println("  [후보 2개] 쌍 %d건".format(pairN))
        println("      1위 불일치 %d건 (%.2f%%)".format(pairTop1, 100.0 * pairTop1 / pairN))
        println("      상황별:")
        byContext.forEach { (k, v) ->
            println("        %-12s %6d쌍 중 %5d건 (%.2f%%)".format(k, v.first, v.second, 100.0 * v.second / v.first))
        }
        if (flipExamples.isNotEmpty()) {
            println("      1위가 뒤집힌 예 (목적/취향/제약/검증/과거):")
            flipExamples.forEach { println("        $it") }
        }

        // ── 3개 조합 표본 (보폭 추출로 결정론 유지) ──
        var tripN = 0
        var tripTop1 = 0
        var tripRank = 0
        for (ctx in MeetingContext.entries) {
            val ahp = ahpOf(ctx)
            var i = 0
            while (i < profiles.size) {
                var j = i + 1
                while (j < profiles.size) {
                    var k = j + 1
                    while (k < profiles.size) {
                        val bd = listOf(profiles[i], profiles[j], profiles[k])
                        val sawO = order(saw(bd, ahp))
                        val topO = order(topsisCloseness(bd, ahp))
                        tripN++
                        if (sawO.first() != topO.first()) tripTop1++
                        if (sawO != topO) tripRank++
                        k += 7
                    }
                    j += 5
                }
                i += 3
            }
        }

        println()
        println("  [후보 3개] 조합 %d건 (보폭 추출)".format(tripN))
        println("      1위 불일치 %d건 (%.2f%%)".format(tripTop1, 100.0 * tripTop1 / tripN))
        println("      전체 순위 불일치 %d건 (%.2f%%)".format(tripRank, 100.0 * tripRank / tripN))

        assertTrue("스윕이 돌지 않았다", pairN > 0 && tripN > 0)
    }

    /**
     * **불일치의 방향** — 6%가 어긋난다는 것보다 *어느 쪽으로* 어긋나는지가 쓸모다.
     *
     * ### 세웠던 가설과 그 기각
     *
     * 처음 가설: *"TOPSIS는 제곱거리라 큰 격차를 증폭하므로 전 구간(0↔1)이 벌어진
     * 제약 준수를 더 무겁게 볼 것이다 → TOPSIS가 제약 위반에 더 민감할 것이다."*
     *
     * **측정이 이 가설을 기각했다.** 제약 준수가 갈린 뒤집힌 쌍에서 '제약을 지킨 쪽'을
     * 고른 것은 **SAW가 950건, TOPSIS가 582건**이었다 — 오히려 SAW가 제약에 더 엄격하다.
     *
     * 원인은 제곱이 아니라 **벡터 정규화**다([정규화가_열마다_격차를_바꾸는지_본다] 참조).
     * TOPSIS는 각 열을 그 열의 노름으로 나누므로 **원래 격차의 크기 정보가 사라진다**:
     *
     * | 기준 | 원값 | 정규화 후 격차 |
     * |---|---|---|
     * | 제약 준수 | 0.0 ↔ 1.0 (전 구간) | **1.000** |
     * | 과거 만족 | 0.0 ↔ 0.5 (절반) | **1.000** ← 같아진다 |
     * | 검증 신뢰 | 0.5 ↔ 1.0 | 0.447 |
     *
     * 즉 TOPSIS에서는 *"과거 만족 0 vs 0.5"* 가 *"제약 준수 0 vs 1"* 과 **같은 크기의
     * 차이**로 취급된다. 우리 루브릭의 **제약 준수 0/1은 가중합의 이진 점수**이며
     * 정규화가 원래 척도의 격차를 바꾼다. 후보 제외를 보장하는 하드 게이트는 아니다.
     *
     * ### 그래서 결론이 뒤집혔다
     *
     * "TOPSIS가 척도 차이를 보정해 주니 우리 약점을 메운다"고 생각했는데, **우리 척도 차이는
     * 보정해야 할 잡음이 아니라 설계된 정보였다.** SAW가 정규화를 **안 하는 것**이 이 도메인에서
     * 맞는 선택이다. 채택하지 않을 이유가 하나 더 늘었다(원래 이유는 순위 역전).
     */
    @Test
    fun 불일치가_어느_쪽으로_일어나는지_본다() {
        val values = mapOf(
            AhpCriterion.PURPOSE_FIT to listOf(0.2, 0.5, 1.0),
            AhpCriterion.PREFERENCE_FIT to listOf(0.2, 0.5, 1.0),
            AhpCriterion.CONSTRAINT_SAFETY to listOf(0.0, 1.0),
            AhpCriterion.VERIFIED_TRUST to listOf(0.5, 1.0),
            AhpCriterion.PAST_SATISFACTION to listOf(0.0, 0.5, 1.0),
        )
        val profiles = ArrayList<Map<AhpCriterion, Double>>()
        for (p in values.getValue(AhpCriterion.PURPOSE_FIT))
            for (f in values.getValue(AhpCriterion.PREFERENCE_FIT))
                for (c in values.getValue(AhpCriterion.CONSTRAINT_SAFETY))
                    for (v in values.getValue(AhpCriterion.VERIFIED_TRUST))
                        for (s in values.getValue(AhpCriterion.PAST_SATISFACTION))
                            profiles += mapOf(
                                AhpCriterion.PURPOSE_FIT to p,
                                AhpCriterion.PREFERENCE_FIT to f,
                                AhpCriterion.CONSTRAINT_SAFETY to c,
                                AhpCriterion.VERIFIED_TRUST to v,
                                AhpCriterion.PAST_SATISFACTION to s,
                            )

        var flips = 0
        var topsisPicksSaferConstraint = 0
        var sawPicksSaferConstraint = 0
        var constraintTied = 0
        // 뒤집힌 쌍에서 기준별로 '전 구간(0↔1) 격차'가 몇 번 관여했나
        val widestGapCriterion = LinkedHashMap<AhpCriterion, Int>()

        for (ctx in MeetingContext.entries) {
            val ahp = ahpOf(ctx)
            for (i in profiles.indices) {
                for (j in i + 1 until profiles.size) {
                    val a2 = profiles[i]
                    val b2 = profiles[j]
                    val bd = listOf(a2, b2)
                    val sawWin = order(saw(bd, ahp)).first()
                    val topWin = order(topsisCloseness(bd, ahp)).first()
                    if (sawWin == topWin) continue
                    flips++

                    val cs = AhpCriterion.CONSTRAINT_SAFETY
                    val ca = a2[cs] ?: 0.0
                    val cb = b2[cs] ?: 0.0
                    when {
                        ca == cb -> constraintTied++
                        // 제약 준수가 더 높은 쪽을 누가 골랐나
                        (if (ca > cb) 0 else 1) == topWin -> topsisPicksSaferConstraint++
                        else -> sawPicksSaferConstraint++
                    }

                    // 이 쌍에서 가장 크게 벌어진 기준
                    val widest = AhpCriterion.entries.maxByOrNull {
                        Math.abs((a2[it] ?: 0.0) - (b2[it] ?: 0.0))
                    }
                    if (widest != null) widestGapCriterion.merge(widest, 1, Int::plus)
                }
            }
        }

        println("═══ 불일치의 방향 (뒤집힌 쌍 %d건) ═══".format(flips))
        println("※ 측정 전용.")
        println()
        println("  제약 준수가 갈린 쌍에서 '제약을 지킨 쪽'을 고른 방법:")
        println("      TOPSIS가 고름 : %d건".format(topsisPicksSaferConstraint))
        println("      SAW가 고름    : %d건".format(sawPicksSaferConstraint))
        println("      제약 동일     : %d건".format(constraintTied))
        println()
        println("  뒤집힌 쌍에서 가장 크게 벌어진 기준:")
        widestGapCriterion.entries.sortedByDescending { it.value }.forEach { (c, n) ->
            println("      %-10s %6d건 (%.1f%%)".format(c.label, n, 100.0 * n / flips))
        }

        assertTrue("뒤집힌 쌍이 없다", flips > 0)
    }

    /**
     * TOPSIS **벡터 정규화가 열마다 격차를 어떻게 바꾸는지** 보여준다.
     *
     * [불일치가_어느_쪽으로_일어나는지_본다]의 결과를 설명하는 메커니즘이다.
     * 열을 그 열의 노름으로 나누므로 **값이 작은 열이 상대적으로 증폭**된다 —
     * 결과적으로 원래 격차 크기(우리가 의도한 설계)가 지워진다.
     */
    @Test
    fun 정규화가_열마다_격차를_바꾸는지_본다() {
        val cases = listOf(
            "제약 준수(전 구간 하드)" to listOf(0.0, 1.0),
            "과거 만족(절반, 값 작음)" to listOf(0.0, 0.5),
            "검증 신뢰(절반, 값 큼)" to listOf(0.5, 1.0),
            "취향 적합" to listOf(0.2, 1.0),
        )
        println("═══ TOPSIS 벡터 정규화가 격차를 어떻게 바꾸나 (후보 2개) ═══")
        println("※ 측정 전용. 원래 격차 크기가 보존되지 않는 것을 보인다.")
        println("%-26s %-14s %8s %-18s %8s".format("기준", "원값", "노름", "정규화", "격차"))
        for ((label, col) in cases) {
            val norm = sqrt(col.sumOf { it * it })
            val r = col.map { if (norm > 1e-12) it / norm else 0.0 }
            println(
                "%-26s %-14s %8.4f %-18s %8.3f".format(
                    label,
                    col.joinToString(" ↔ ") { "%.1f".format(it) },
                    norm,
                    r.joinToString(" ↔ ") { "%.3f".format(it) },
                    Math.abs(r[0] - r[1]),
                ),
            )
        }
        println()
        println("  → '과거 만족 0↔0.5'와 '제약 준수 0↔1'의 정규화 격차가 **둘 다 1.000**이다.")
        println("     제약 준수 0/1 점수와 다른 기준 사이의 원래 격차가 정규화에서 달라진다(강제 제외 아님).")
        assertTrue(cases.isNotEmpty())
    }

    private fun fmt(b: Map<AhpCriterion, Double>) =
        AhpCriterion.entries.joinToString("/") { "%.1f".format(b[it] ?: 0.0) }
}
