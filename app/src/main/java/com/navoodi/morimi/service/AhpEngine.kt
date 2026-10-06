package com.navoodi.morimi.service

import kotlin.math.abs
import kotlin.math.pow

/**
 * 추천 후보를 평가하는 의사결정 기준(criteria) 계층.
 *
 * 모임은 "서로 다른 성향을 가진 사람들"의 집합이라 개인 선호를 산술 평균하면 아무도
 * 만족하지 않는 중간값이 나온다. 그래서 개인 선호는 **기준 중 하나**([PREFERENCE_FIT])로
 * 내려두고, 최상위 기준은 그날 **모임의 목적**([PURPOSE_FIT])이 가진다.
 * (회의 피드백 2026-09-14: "개인으로 하면 안 돼. 오늘 모임의 목적이 뭐냐, 그 목적에 맞는 장소냐")
 */
enum class AhpCriterion(val label: String, val description: String) {
    // 채점(PlaceRanker.purposeFit)은 **업종 토큰만** 본다. 분위기 형용사는 취향·제약 기준이
    // 보는 몫이라 여기서 제외했으므로(DEVLOG 결정 3), 설명 문구도 업종으로 한정한다 —
    // 모델에게 주는 지침과 실제 채점 기준이 어긋나면 안 된다.
    PURPOSE_FIT("목적 적합", "그날 모임의 목적·상황(식사/술자리/위로 등)에 업종이 맞는가"),
    PREFERENCE_FIT("취향 적합", "참가자 성향 프로필의 '좋아요' 항목을 얼마나 만족시키는가"),
    CONSTRAINT_SAFETY("제약 준수", "'싫어요' 항목·금지 조건을 위반하지 않는가"),
    VERIFIED_TRUST("검증 신뢰", "실존·영업이 팩트 체크로 확인됐는가 (할루시네이션 방어)"),
    PAST_SATISFACTION("과거 만족", "유사한 과거 모임 후기에서 만족도가 높았던 유형인가"),
}

/**
 * 쌍대비교(pairwise comparison) 행렬. `value[i][j]` = 기준 i가 기준 j보다 몇 배 중요한가.
 * Saaty 1~9 척도(1 동등, 3 약간 우세, 5 우세, 7 매우 우세, 9 절대 우세)와 그 역수를 쓴다.
 *
 * 대각은 1, `m[j][i] = 1 / m[i][j]`(역수성)가 구조적으로 보장되도록 [of]로만 만든다.
 */
class PairwiseMatrix private constructor(val order: List<AhpCriterion>, val value: Array<DoubleArray>) {

    companion object {
        /**
         * 상삼각 판단만 주면 대각(1)과 하삼각(역수)을 채워 행렬을 만든다.
         * @param judgments (i, j) → i가 j보다 몇 배 중요한가. 빠진 쌍은 1(동등)로 둔다.
         */
        fun of(
            order: List<AhpCriterion> = AhpCriterion.entries.toList(),
            judgments: Map<Pair<AhpCriterion, AhpCriterion>, Double>,
        ): PairwiseMatrix {
            val n = order.size
            val m = Array(n) { DoubleArray(n) { 1.0 } }
            judgments.forEach { (pair, raw) ->
                val i = order.indexOf(pair.first)
                val j = order.indexOf(pair.second)
                // 알 수 없는 기준·자기 자신 비교·비양수 판단은 조용히 무시(동등 유지) — 방어적
                if (i < 0 || j < 0 || i == j || raw <= 0.0 || !raw.isFinite()) return@forEach
                m[i][j] = raw
                m[j][i] = 1.0 / raw
            }
            return PairwiseMatrix(order, m)
        }
    }

    /** 판단 하나를 바꾼 사본 — 재학습([AhpJudgmentLearner])이 원본을 훼손하지 않도록 불변 갱신. */
    fun withJudgment(i: AhpCriterion, j: AhpCriterion, ratio: Double): PairwiseMatrix {
        val a = order.indexOf(i)
        val b = order.indexOf(j)
        if (a < 0 || b < 0 || a == b || ratio <= 0.0 || !ratio.isFinite()) return this
        val copy = Array(value.size) { value[it].copyOf() }
        copy[a][b] = ratio
        copy[b][a] = 1.0 / ratio
        return PairwiseMatrix(order, copy)
    }

    fun judgment(i: AhpCriterion, j: AhpCriterion): Double {
        val a = order.indexOf(i)
        val b = order.indexOf(j)
        if (a < 0 || b < 0) return 1.0
        return value[a][b]
    }
}

/**
 * AHP 해(解). [weights]는 합이 1인 우선순위 벡터, [cr]은 판단의 일관성 비율.
 *
 * [consistent]가 false면 "기준 간 판단이 서로 모순됐다"는 뜻이라 가중치를 신뢰할 수 없다.
 * 이 경우 호출측은 결과를 채택하지 않고 직전(검증된) 가중치로 되돌린다 — CR이 단순 참고값이
 * 아니라 **채택/기각 게이트**로 동작한다.
 */
data class AhpResult(
    val weights: Map<AhpCriterion, Double>,
    val lambdaMax: Double,
    val ci: Double,
    val cr: Double,
    val consistent: Boolean,
) {
    fun weightOf(c: AhpCriterion): Double = weights[c] ?: 0.0

    /** 가중치 내림차순 기준 순서 — 디버그 로그·UI 설명용. */
    fun ranked(): List<Pair<AhpCriterion, Double>> = weights.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
}

/**
 * AHP(Analytic Hierarchy Process) 계산 엔진 — 순수 Kotlin(안드로이드 의존성 없음).
 *
 * 다기준 의사결정에서 "무엇을 얼마나 중요하게 볼 것인가"를 쌍대비교로 받아 정량 가중치로
 * 환산하는 표준 방법론(Saaty, 1980). 이 앱에서는 서로 다른 성향의 참가자들이 섞인 모임에서
 * 장소 후보를 고를 때, 상황별 기준 가중치를 산출하는 데 쓴다.
 *
 * 우선순위 벡터는 **행 기하평균법**(row geometric mean)으로 구한다. Saaty 원본은 주고유벡터법
 * 이지만, 기하평균법은 반복 수렴이 필요 없어 결정론적이고(같은 입력 → 항상 같은 출력)
 * 온디바이스 비용이 사실상 0이다.
 *
 * **"실질적으로 같다"는 주장은 재봤다**(`AhpWeightMethodEvalTest`, 2026-09-23):
 *
 * | 구간 | 표본 | 기준 순위 불일치 | 최대 가중치 차 |
 * |---|---|---|---|
 * | 9개 프리셋 (CR 0.0022~0.0177) | 9 | **0 / 9** | 0.23 pp |
 * | CR < 0.10 — **채택 구간** | 142 | **0.0%** | 3.18 pp |
 * | CR ≥ 0.10 — 기각 구간 | 101 | 45.5% | 11.91 pp |
 *
 * 즉 **두 방법을 일치시키는 것이 곧 CR 게이트다.** 게이트 안에서는 순위가 한 번도 갈리지
 * 않았고, 밖에서는 절반 가까이 갈렸다. CR 임계 0.10은 AHP 형식 절차가 아니라
 * *가중치 산출법 선택이 결과에 영향을 주지 않는 영역*을 지키는 장치이기도 하다.
 *
 * 일관성 검사:
 *   λmax = (1/n) Σ (Aw)_i / w_i,  CI = (λmax − n) / (n − 1),  CR = CI / RI(n)
 *   CR < 0.10 이면 판단이 일관적이라고 본다(Saaty 기준).
 */
object AhpEngine {

    /** Saaty가 제시한 일관성 허용 상한. 이 값 이상이면 판단을 다시 해야 한다. */
    const val CONSISTENCY_THRESHOLD = 0.10

    /** Random Index — 무작위 쌍대비교 행렬의 평균 CI (Saaty). index = 행렬 차수 n */
    private val RANDOM_INDEX = doubleArrayOf(
        0.0, 0.0, 0.0, 0.58, 0.90, 1.12, 1.24, 1.32, 1.41, 1.45, 1.49,
    )

    /** n > 10 구간의 RI 근사식 — 표를 벗어나도 계산이 끊기지 않게. */
    private fun randomIndex(n: Int): Double =
        if (n < RANDOM_INDEX.size) RANDOM_INDEX[n] else 1.98 * (n - 2) / n

    /**
     * 쌍대비교 행렬을 풀어 가중치와 일관성 지표를 반환한다.
     *
     * n ≤ 2는 일관성 개념이 정의되지 않으므로(RI = 0) CR = 0, consistent = true로 둔다.
     */
    fun solve(matrix: PairwiseMatrix): AhpResult {
        val order = matrix.order
        val n = order.size
        if (n == 0) return AhpResult(emptyMap(), 0.0, 0.0, 0.0, consistent = true)

        val m = matrix.value

        // 1) 행 기하평균 → 정규화 = 우선순위 벡터 w
        val geo = DoubleArray(n) { i ->
            var logSum = 0.0
            for (j in 0 until n) {
                val v = m[i][j]
                // 비정상 값은 동등(1.0)으로 간주 — log(0)/NaN 오염 방지
                logSum += if (v > 0.0 && v.isFinite()) kotlin.math.ln(v) else 0.0
            }
            kotlin.math.exp(logSum / n)
        }
        val geoSum = geo.sum()
        val w = if (geoSum > 0.0) DoubleArray(n) { geo[it] / geoSum } else DoubleArray(n) { 1.0 / n }

        // 2) λmax = 평균( (Aw)_i / w_i )
        var lambdaMax = 0.0
        var counted = 0
        for (i in 0 until n) {
            var aw = 0.0
            for (j in 0 until n) aw += m[i][j] * w[j]
            if (w[i] > 1e-12) {
                lambdaMax += aw / w[i]
                counted++
            }
        }
        lambdaMax = if (counted > 0) lambdaMax / counted else n.toDouble()

        // 3) CI, CR
        val ci = if (n > 1) (lambdaMax - n) / (n - 1) else 0.0
        val ri = randomIndex(n)
        val cr = if (ri > 0.0) ci / ri else 0.0
        // 부동소수 오차로 -1e-16 같은 값이 나올 수 있어 절댓값으로 판정
        val consistent = abs(cr) < CONSISTENCY_THRESHOLD

        return AhpResult(
            weights = order.mapIndexed { i, c -> c to w[i] }.toMap(),
            lambdaMax = lambdaMax,
            ci = ci,
            cr = cr,
            consistent = consistent,
        )
    }

    /**
     * 기준별 후보 점수(0~1)를 가중 합산해 최종 점수를 낸다.
     * AHP의 종합(synthesis) 단계 — Σ w_i × s_i.
     *
     * ### 여기 들어오는 [scores]는 쌍대비교의 결과가 아니다
     *
     * 교과서 AHP는 대안도 기준별로 쌍대비교해 우선순위 벡터를 뽑는다(**상대 측정**).
     * 우리는 [PlaceRanker]가 매긴 **기준별 0~1 결정론 루브릭 점수**를 그대로 받는다 —
     * Saaty가 상대 측정과 함께 제시한 **절대 평가(ratings) 계열**이다. 이유는 셋:
     *
     * 1. 대안 쌍대비교는 판단자가 필요한데 실행 시점의 판단자는 LLM뿐이고,
     *    채점자가 곧 채점 대상이면 검증이 성립하지 않는다(CLAUDE.md 컨벤션 #8).
     * 2. 상대 측정은 대안이 드나들 때 **순위 역전**이 일어날 수 있다. 우리 후보는 매 실행
     *    Gemini가 새로 만들고 Guardrail이 걸러내 집합이 계속 바뀐다 — 같은 장소가 다른
     *    후보 때문에 순위가 달라지면 재현이 안 된다.
     * 3. 비용이 후보 n개 × 기준 5개에 n(n−1)/2 비교다.
     *
     * **대가**: 루브릭 척도가 기준마다 다르다(검증 신뢰는 사실상 3값, 취향 적합은 연속값).
     * 가중합은 그 차이를 보정하지 않으므로 이 값을 "최적해"로 부르지 않는다 —
     * *일관성이 검증된 가중치에 따른 정렬*까지가 이 함수가 보장하는 것이다.
     * (README "AHP: 왜 후보끼리는 쌍대비교하지 않는가", DEVLOG 2026-09-23 (12))
     */
    fun synthesize(scores: Map<AhpCriterion, Double>, result: AhpResult): Double =
        result.weights.entries.sumOf { (criterion, weight) ->
            weight * (scores[criterion] ?: 0.0).coerceIn(0.0, 1.0)
        }

    /** 소수 가중치를 퍼센트 문자열로 — 디버그 로그·UI 표기용. */
    fun formatWeights(result: AhpResult): String =
        result.ranked().joinToString(", ") { (c, w) -> "${c.label} ${(w * 100).roundTo(1)}%" }

    private fun Double.roundTo(digits: Int): Double {
        val factor = 10.0.pow(digits)
        return kotlin.math.round(this * factor) / factor
    }
}
