package com.navoodi.morimi.service

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [평가] 행 기하평균법 vs 고유벡터법 — **측정 전용**(임계값 단언 없음).
 *
 * ### 왜 재나
 *
 * `AhpEngine` KDoc이 *"행 기하평균법은 고유벡터법과 실질적으로 같은 결과를 준다"* 고
 * 주장한다. 교과서적으로 맞는 말이지만 **이 레포에서 재본 적이 없다.**
 * "측정하지 않은 것은 주장하지 않는다"(CLAUDE.md)에 걸린다.
 *
 * 더구나 이건 방어에서 나올 수 있는 질문이다 — Saaty 원본은 **주고유벡터**이고,
 * 우리 `solve()`는 행 기하평균으로 가중치를 뽑은 뒤 그 벡터로 λmax를 계산한다.
 * "그럼 CR은 어느 벡터 기준인가"가 따라붙는다.
 *
 * ### 무엇을 재나
 *
 * 9개 상황 프리셋 각각에서 멱승법(power iteration)으로 주고유벡터를 직접 구해
 * 행 기하평균 가중치와 비교한다. 두 방법은 **완전 일관 행렬에서 정확히 일치**하고
 * 비일관성이 커질수록 벌어지므로, **CR이 큰 행렬도 같이 넣어** 그 경향을 본다.
 *
 * 임계 단언은 하지 않는다 — 수치를 남기는 것이 목적이다.
 */
class AhpWeightMethodEvalTest {

    /** 멱승법으로 주고유벡터(정규화, 합=1)와 그 고유값을 구한다. */
    private fun principalEigenvector(m: Array<DoubleArray>): Pair<DoubleArray, Double> {
        val n = m.size
        var v = DoubleArray(n) { 1.0 / n }
        var lambda = n.toDouble()
        repeat(500) {
            val next = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0
                for (j in 0 until n) s += m[i][j] * v[j]
                next[i] = s
            }
            val sum = next.sum()
            if (sum <= 0.0) return@repeat
            for (i in 0 until n) next[i] /= sum
            var diff = 0.0
            for (i in 0 until n) diff = maxOf(diff, abs(next[i] - v[i]))
            v = next
            // Rayleigh 몫: λ = 평균((Av)_i / v_i)
            var l = 0.0
            for (i in 0 until n) {
                var s = 0.0
                for (j in 0 until n) s += m[i][j] * v[j]
                l += s / v[i]
            }
            lambda = l / n
            if (diff < 1e-14) return v to lambda
        }
        return v to lambda
    }

    @Test
    fun 두_산출법의_차이를_상황별로_잰다() {
        println("═══ 우선순위 벡터: 행 기하평균 vs 주고유벡터 ═══")
        println("※ 측정 전용. 두 방법은 완전 일관 행렬에서 정확히 일치한다.")
        println("%-14s %8s %10s %10s  %s".format("상황", "CR", "최대차(pp)", "λ차", "순위 일치"))

        var worst = 0.0
        var worstCtx = ""
        var rankMismatch = 0

        for (ctx in MeetingContext.entries) {
            val matrix = ctx.basePairwiseMatrix()
            val geo = AhpEngine.solve(matrix)
            val (eig, lambdaEig) = principalEigenvector(matrix.value)

            val order = matrix.order
            var maxDiff = 0.0
            order.forEachIndexed { i, c ->
                maxDiff = maxOf(maxDiff, abs(geo.weightOf(c) - eig[i]))
            }
            val geoRank = order.sortedByDescending { geo.weightOf(it) }
            val eigRank = order.indices.sortedByDescending { eig[it] }.map { order[it] }
            val sameRank = geoRank == eigRank
            if (!sameRank) rankMismatch++
            if (maxDiff > worst) { worst = maxDiff; worstCtx = ctx.name }

            println(
                "%-14s %8.4f %10.4f %10.6f  %s".format(
                    ctx.name, geo.cr, maxDiff * 100, abs(geo.lambdaMax - lambdaEig),
                    if (sameRank) "일치" else "★불일치 geo=$geoRank eig=$eigRank",
                ),
            )
        }

        println()
        println("  최대 가중치 차이: %.4f pp (%s)".format(worst * 100, worstCtx))
        println("  기준 순위가 갈린 상황: %d / %d".format(rankMismatch, MeetingContext.entries.size))

        // 측정 전용이지만 "측정이 실제로 돌았다"는 것만 지킨다
        assertTrue("프리셋이 없다", MeetingContext.entries.isNotEmpty())
    }

    /**
     * **CR 게이트가 채택하는 구간에서도 두 방법이 일치하는가** — 이게 진짜 물음이다.
     *
     * 프리셋 9개만 보면 CR이 0.0022~0.0177로 너무 착해서 "항상 같다"로 오독할 수 있다.
     * 재학습([AhpJudgmentLearner])은 판단을 칸 단위로 움직이므로 실제로는 CR이 임계
     * 0.10 **바로 아래**까지 올라간 행렬도 채택된다. 그 구간이 위험 구간이다.
     *
     * 그래서 판단 셀을 Saaty 척도 전역으로 흔들어 많은 행렬을 만들고,
     * **CR < 0.10(채택) / CR ≥ 0.10(기각)** 으로 나눠 순위 일치율을 따로 센다.
     */
    @Test
    fun CR_게이트가_채택하는_구간에서_순위가_갈리는지_본다() {
        val scale = listOf(1 / 9.0, 1 / 7.0, 1 / 5.0, 1 / 3.0, 1.0, 3.0, 5.0, 7.0, 9.0)
        val cells = listOf(
            AhpCriterion.PURPOSE_FIT to AhpCriterion.PREFERENCE_FIT,
            AhpCriterion.PREFERENCE_FIT to AhpCriterion.VERIFIED_TRUST,
            AhpCriterion.CONSTRAINT_SAFETY to AhpCriterion.PAST_SATISFACTION,
        )

        var acceptN = 0; var acceptMismatch = 0; var acceptWorst = 0.0
        var rejectN = 0; var rejectMismatch = 0; var rejectWorst = 0.0
        val acceptExamples = ArrayList<String>()

        for (ctx in MeetingContext.entries) {
            val base = ctx.basePairwiseMatrix()
            for ((a, b) in cells) {
                for (r1 in scale) {
                    val m = base.withJudgment(a, b, r1)
                    val geo = AhpEngine.solve(m)
                    val (eig, _) = principalEigenvector(m.value)
                    var maxDiff = 0.0
                    m.order.forEachIndexed { i, c ->
                        maxDiff = maxOf(maxDiff, abs(geo.weightOf(c) - eig[i]))
                    }
                    val geoRank = m.order.sortedByDescending { geo.weightOf(it) }
                    val eigRank = m.order.indices.sortedByDescending { eig[it] }.map { m.order[it] }
                    val same = geoRank == eigRank

                    if (geo.cr < AhpEngine.CONSISTENCY_THRESHOLD) {
                        acceptN++
                        acceptWorst = maxOf(acceptWorst, maxDiff)
                        if (!same) {
                            acceptMismatch++
                            if (acceptExamples.size < 5) {
                                acceptExamples += "%s %s/%s=%.3f CR=%.4f 차=%.3fpp".format(
                                    ctx.name, a.label, b.label, r1, geo.cr, maxDiff * 100,
                                )
                            }
                        }
                    } else {
                        rejectN++
                        rejectWorst = maxOf(rejectWorst, maxDiff)
                        if (!same) rejectMismatch++
                    }
                }
            }
        }

        println("═══ 행 기하평균 vs 주고유벡터 — CR 게이트 기준으로 나눠 보기 ═══")
        println("※ 측정 전용. 판단 셀 3곳 × Saaty 척도 9값 × 상황 9종 = %d개 행렬".format(acceptN + rejectN))
        println()
        println("  [채택 구간] CR < 0.10 : %d개".format(acceptN))
        println("      기준 순위 불일치 : %d개 (%.1f%%)".format(
            acceptMismatch, if (acceptN > 0) 100.0 * acceptMismatch / acceptN else 0.0))
        println("      최대 가중치 차이 : %.4f pp".format(acceptWorst * 100))
        if (acceptExamples.isNotEmpty()) {
            println("      불일치 예:")
            acceptExamples.forEach { println("        $it") }
        }
        println()
        println("  [기각 구간] CR >= 0.10 : %d개".format(rejectN))
        println("      기준 순위 불일치 : %d개 (%.1f%%)".format(
            rejectMismatch, if (rejectN > 0) 100.0 * rejectMismatch / rejectN else 0.0))
        println("      최대 가중치 차이 : %.4f pp".format(rejectWorst * 100))

        assertTrue("행렬이 하나도 안 만들어졌다", acceptN + rejectN > 0)
    }
}
