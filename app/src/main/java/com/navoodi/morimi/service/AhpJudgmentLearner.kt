package com.navoodi.morimi.service

import kotlin.math.abs

/**
 * 쌍대비교 판단 한 칸의 보정. `row`가 `col`보다 얼마나 더 중요한가를 Saaty 사다리에서
 * [steps] 칸 올리거나(양수) 내린다(음수).
 *
 * 가중치 벡터를 직접 건드리지 않고 **판단(입력)을 고치는** 이유는, 그래야 고친 결과가
 * 일관성 검사(CR)의 대상이 되기 때문이다. 가중치를 곧바로 수정하면 검증 없이 통과한다.
 */
data class JudgmentDelta(val row: AhpCriterion, val col: AhpCriterion, val steps: Int)

/** 하네스 1회 실행에서 학습에 쓰이는 증거. */
data class RunEvidence(
    val constraintViolations: Int = 0,
    val hallucinatedPlaces: Int = 0,
    /** 사용자 후기 평점에서 온 0~1 만족도. 아직 후기가 없으면 null(만족 관련 학습은 보류). */
    val satisfaction: Double? = null,
)

/**
 * 학습 반영 결과.
 *
 * @property accepted    CR 게이트를 통과해 채택됐는가
 * @property appliedDeltas 실제로 적용된 보정(일관성 때문에 일부가 잘려나갔을 수 있다)
 * @property droppedDeltas 일관성 회복을 위해 되돌린 보정
 */
data class LearnedWeights(
    val context: MeetingContext,
    val result: AhpResult,
    val appliedDeltas: List<JudgmentDelta>,
    val droppedDeltas: List<JudgmentDelta>,
    val accepted: Boolean,
) {
    val isLearned: Boolean get() = appliedDeltas.isNotEmpty()
}

/**
 * **AHP 판단 재학습기 — 피드백 순환 고리가 다시 들어오는 지점.**
 *
 * 추천 → 검증 → 후기(만족도)로 돌아온 증거를 다음 회차의 **기준 가중치**에 반영한다.
 * 다만 가중치를 직접 손대지 않고 **쌍대비교 판단 한 칸씩** 움직인다:
 *
 *   제약 위반 발생      → "제약 준수"를 **"취향 적합"보다** 한 칸 더 중요하게
 *   실존하지 않는 장소  → "검증 신뢰"를 **"취향 적합"보다** 한 칸 더 중요하게
 *   원인 불명 불만      → "취향 적합"·"과거 만족"을 "목적 적합"보다 한 칸씩 더 중요하게
 *   만족(4~5점)        → 가장 오래된 보정 **1건만** 1칸 되돌린다 (FIFO 감쇠)
 *   3점·미평가          → 무동작 (PlaceRanker의 과거 만족 기준과 해석을 통일 — D3)
 *
 * **보정 축이 '취향 적합' 기준인 이유(2026-09-23 D4)**: 이전에는 모든 보정이
 * `X vs 목적 적합` 한 축이었다. 목적 적합은 상황이 정하는 최상위 기준이라 그것만 깎으면
 * 상황 설계가 흔들리고, 상한(3칸)까지 밀어도 후보 점수 차를 뒤집지 못했다(실측).
 * 원인이 된 기준을 **취향 적합 대비로** 올리면 같은 칸수로도 실제 순위에 닿는다.
 *
 * 그리고 **CR(일관성 비율)이 채택 게이트**가 된다. 판단을 국소적으로 고치면 기준들 사이의
 * 추이성이 깨질 수 있는데, CR ≥ 0.10이면 "이번 학습은 판단을 모순되게 만들었다"는 뜻이므로
 * 가장 최근 보정부터 되돌려 일관성을 회복한다. 회복이 안 되면 상황 기본 행렬로 롤백한다.
 * (CR을 보고용 숫자가 아니라 **채택/기각 판정**으로 쓴다)
 *
 * 순수 Kotlin — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
object AhpJudgmentLearner {

    /**
     * 한 칸(row,col)에 누적 가능한 보정 상한 — 학습이 폭주해 특정 기준만 남는 것을 막는다.
     *
     * **3 → 5 (2026-09-23 D9).** 3에서는 보정이 후보 점수 차를 뒤집지 못했다(실측: 시나리오 C의
     * 두 후보 격차가 0.1057 → 0.0138로 좁아지기만 함). 역전은 4칸에서 일어나고 그때 CR도
     * 0.0744로 임계 이내라, 막고 있던 것은 일관성이 아니라 이 상한이었다.
     *
     * 5를 넘기지 않는 이유: 6칸에서 CR이 0.1166으로 임계를 넘어 [resolve]의 게이트에 걸린다.
     * 즉 5는 "일관성이 허용하는 최대치"이지 임의로 고른 숫자가 아니다.
     * 상한을 올려도 게이트는 그대로다 — 상황에 따라 5칸이 CR을 깨면 그 보정은 여전히 기각된다.
     */
    const val MAX_STEPS = 5

    /** Saaty 척도 사다리(역수 포함). 인덱스 8이 1.0(동등). */
    private val LADDER = doubleArrayOf(
        1.0 / 9, 1.0 / 8, 1.0 / 7, 1.0 / 6, 1.0 / 5, 1.0 / 4, 1.0 / 3, 1.0 / 2,
        1.0,
        2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0,
    )

    /**
     * 증거를 반영해 누적 보정을 갱신한다(순수 함수 — 입력을 바꾸지 않는다).
     *
     * 축은 원인에 따라 다르다(D4): 원인이 특정된 불만은 `X vs 취향 적합`,
     * 원인 불명 불만은 기존 `X vs 목적 적합`. 직렬화 포맷이 (row, col) 쌍을 저장하므로
     * 축이 바뀌어도 기존 저장분은 그대로 복원된다(호환 처리 불필요).
     */
    fun learn(current: List<JudgmentDelta>, evidence: RunEvidence): List<JudgmentDelta> {
        // 누적 순서를 보존한다 — FIFO 감쇠(D6)가 "가장 오래된 보정"을 알아야 하기 때문.
        // LinkedHashMap의 삽입 순서 = 그 보정이 처음 생긴 순서.
        val acc = LinkedHashMap<Pair<AhpCriterion, AhpCriterion>, Int>()
        current.forEach { acc[it.row to it.col] = it.steps }

        fun bump(row: AhpCriterion, col: AhpCriterion, by: Int) {
            if (row == col) return
            val key = row to col
            val next = ((acc[key] ?: 0) + by).coerceIn(0, MAX_STEPS)
            if (next == 0) acc.remove(key) else acc[key] = next
        }

        // 0~1 만족도를 5점 척도로 되돌린다(저장은 0~1, 판정 기준은 척도 단위라서)
        val rating = evidence.satisfaction?.let { Math.round(it * 5).toInt() }

        when {
            // 미평가(null)·3점(보통) → 무동작. 3점은 PlaceRanker에서도 중립이다(D3)
            rating == null || rating == 3 -> Unit

            // 4~5점(만족) → 가장 오래된 보정 1건만 1칸 되돌린다(D6).
            // 전부 되돌리면 효과를 낸 보정까지 한 번에 사라져 학습이 남지 않는다.
            rating >= 4 -> acc.keys.firstOrNull()?.let { bump(it.first, it.second, -1) }

            // 1~2점(불만) → 원인별 보정, 축은 '취향 적합' 기준(D4)
            else -> {
                var caused = false
                if (evidence.constraintViolations > 0) {
                    bump(AhpCriterion.CONSTRAINT_SAFETY, AhpCriterion.PREFERENCE_FIT, 1); caused = true
                }
                if (evidence.hallucinatedPlaces > 0) {
                    bump(AhpCriterion.VERIFIED_TRUST, AhpCriterion.PREFERENCE_FIT, 1); caused = true
                }
                // 원인이 특정되지 않은 불만 — 취향을 못 맞힌 것으로 보고 기존 축 유지
                if (!caused) {
                    bump(AhpCriterion.PREFERENCE_FIT, AhpCriterion.PURPOSE_FIT, 1)
                    bump(AhpCriterion.PAST_SATISFACTION, AhpCriterion.PURPOSE_FIT, 1)
                }
            }
        }

        // 삽입 순서 그대로 내보낸다 — FIFO 감쇠가 이 순서를 읽는다
        return acc.entries.map { (pair, steps) -> JudgmentDelta(pair.first, pair.second, steps) }
    }

    /**
     * 보정을 적용해 최종 가중치를 구한다. CR ≥ 0.10이면 **가장 최근(마지막) 보정부터 되돌려**
     * 일관성을 회복하고, 끝내 안 되면 상황 기본 행렬로 롤백한다.
     */
    fun resolve(context: MeetingContext, deltas: List<JudgmentDelta>): LearnedWeights {
        val base = context.basePairwiseMatrix()
        if (deltas.isEmpty()) {
            return LearnedWeights(context, AhpEngine.solve(base), emptyList(), emptyList(), accepted = true)
        }

        val kept = deltas.toMutableList()
        val dropped = mutableListOf<JudgmentDelta>()

        while (kept.isNotEmpty()) {
            val result = AhpEngine.solve(apply(base, kept))
            if (result.consistent) {
                return LearnedWeights(context, result, kept.toList(), dropped.toList(), accepted = true)
            }
            // 보정이 판단을 모순되게 만들었다 — 마지막 것부터 버린다
            dropped.add(kept.removeAt(kept.lastIndex))
        }

        // 전부 버려야 하는 상황 — 통째로 버리기 전에 **칸수를 줄여** CR 이내 최대치를 찾는다.
        // (2026-09-23 D9) MAX_STEPS를 5로 올리면서 필요해졌다: 상황에 따라 4~5칸이 CR을 깨는데,
        // 그때 보정을 통째로 버리면 3칸에서 안정적으로 남던 학습이 0으로 증발한다 — 상향 전보다 나쁘다.
        // 칸수를 낮추면 그 상황은 자기 CR이 허용하는 최대치(예: 3칸)에 자동으로 안착한다.
        trimToConsistent(context, base, deltas)?.let { return it }

        // 1칸으로도 일관성이 회복되지 않음 — 학습 없이 간다(하드 실패는 만들지 않는다)
        return LearnedWeights(context, AhpEngine.solve(base), emptyList(), dropped.toList(), accepted = false)
    }

    /**
     * 가장 큰 보정부터 한 칸씩 낮추며 CR 이내로 들어오는 최대치를 찾는다.
     * 전부 버리는 것보다 낫기 때문이지, 임계를 느슨하게 하는 것이 아니다 — 판정 기준은 그대로 0.10이다.
     *
     * @return 일관성을 회복한 결과, 끝내 회복 못 하면 null
     */
    private fun trimToConsistent(
        context: MeetingContext,
        base: PairwiseMatrix,
        original: List<JudgmentDelta>,
    ): LearnedWeights? {
        var current = original.toList()
        while (true) {
            val peak = current.maxOfOrNull { abs(it.steps) } ?: return null
            if (peak <= 1) return null
            // 가장 큰 칸수를 가진 보정들을 한 칸씩 낮춘다(부호 유지)
            current = current.map {
                if (abs(it.steps) >= peak) it.copy(steps = it.steps - it.steps.sign()) else it
            }
            val active = current.filter { it.steps != 0 }
            if (active.isEmpty()) return null

            val result = AhpEngine.solve(apply(base, active))
            if (result.consistent) {
                // 원본과 달라진(=깎인) 보정을 dropped로 보고한다 — 학습이 얼마나 잘렸는지 보이게
                val trimmed = original.filterNot { o -> active.any { it == o } }
                return LearnedWeights(context, result, active, trimmed, accepted = true)
            }
        }
    }

    private fun Int.sign(): Int = if (this > 0) 1 else if (this < 0) -1 else 0

    /** 보정을 행렬에 적용 — 각 (row, col) 칸을 Saaty 사다리에서 steps 칸 이동. */
    internal fun apply(base: PairwiseMatrix, deltas: List<JudgmentDelta>): PairwiseMatrix =
        deltas.fold(base) { matrix, d ->
            if (d.steps == 0 || d.row == d.col) matrix
            else matrix.withJudgment(d.row, d.col, shift(matrix.judgment(d.row, d.col), d.steps))
        }

    /** 사다리에서 [steps] 칸 이동한 값. 사다리 밖 값은 가장 가까운 칸으로 끌어와서 이동한다. */
    internal fun shift(value: Double, steps: Int): Double {
        var nearest = 0
        var bestGap = Double.MAX_VALUE
        for (i in LADDER.indices) {
            // 비율 척도라 절대 차가 아니라 로그 거리로 가장 가까운 칸을 찾는다
            val gap = abs(kotlin.math.ln(value) - kotlin.math.ln(LADDER[i]))
            if (gap < bestGap) { bestGap = gap; nearest = i }
        }
        return LADDER[(nearest + steps).coerceIn(0, LADDER.lastIndex)]
    }

    // ── 영속 직렬화 (Room TEXT 컬럼) ─────────────────────────────────────────
    //
    // 기존 Converters의 "||" 구분자 관례와 같은 계열의 단순 문자열 포맷.
    // 모르는 기준 이름(앱 버전 간 enum 변화)은 조용히 건너뛴다 — 방어적 파싱(컨벤션 #2).

    fun encode(deltas: List<JudgmentDelta>): String =
        deltas.joinToString("|") { "${it.row.name}>${it.col.name}:${it.steps}" }

    fun decode(raw: String): List<JudgmentDelta> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { token ->
            runCatching {
                val (pair, steps) = token.split(":", limit = 2)
                val (row, col) = pair.split(">", limit = 2)
                JudgmentDelta(
                    row = AhpCriterion.valueOf(row),
                    col = AhpCriterion.valueOf(col),
                    steps = steps.toInt().coerceIn(-MAX_STEPS, MAX_STEPS),
                )
            }.getOrNull()
        }.filter { it.steps != 0 && it.row != it.col }
    }
}
