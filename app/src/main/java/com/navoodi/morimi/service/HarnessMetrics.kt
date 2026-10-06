package com.navoodi.morimi.service

import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus

/**
 * 검증 하네스 1회 실행의 품질 지표.
 *
 * **개수로 보관하고 비율은 읽을 때 계산한다.** 비율만 저장하면 정의가 바뀔 때 과거 행을
 * 다시 계산할 수 없다(2026-09-23 D1 결정에서 실제로 정의가 바뀌었다).
 *
 * 지표 4종 (2026-09-23 재정의, D1·D8):
 *
 * | 지표 | 정의 | 분모 0일 때 |
 * |---|---|---|
 * | [verifiedRate] 실존 확인율 | VERIFIED ÷ (VERIFIED + NOT_FOUND) | `null` = 판정 불가 |
 * | [hallucinationRate] 할루시네이션율 | NOT_FOUND ÷ (VERIFIED + NOT_FOUND) | `null` |
 * | [coverage] 확인률 | (VERIFIED + NOT_FOUND) ÷ 전체 장소 | `null` |
 * | [constraintCompliance] 제약 준수율 | (항목 − 위반 항목) ÷ 항목 | `null` |
 *
 * **UNVERIFIED는 실존 확인율·할루시네이션율의 분자에도 분모에도 들어가지 않는다.**
 * 확인률에서만 드러난다. 카카오 키가 없으면 전부 UNVERIFIED가 되어 실존 확인율은 "판정 불가",
 * 확인률 0%가 된다 — 이것이 의도된 표시다(예전 정의에서는 정확도 0%로 보여 모델 탓처럼 읽혔다).
 *
 * [rawHallucinationRate]는 **전 시도 누적**이다. 사용자에게 도달하는 최종 결과에는
 * NOT_FOUND가 구조적으로 있을 수 없으므로([AssistantOrchestrator]는 `guardrail.passed`인 시도만
 * 반환한다), 최종 기준 할루시네이션율은 항상 0이다. 모델이 실제로 없는 가게를 몇 번 지어냈는지는
 * 재시도로 폐기된 시도까지 세어야 보인다.
 *
 * 순수 Kotlin — JVM 단위 테스트 가능(CLAUDE.md 컨벤션 #6).
 */
data class HarnessMetrics(
    // ── 최종 시도(사용자에게 간 결과)의 개수 ──────────────────────────────
    val placeCount: Int,
    val verifiedCount: Int,
    val notFoundCount: Int,
    val unverifiedCount: Int,
    // ── 제약 (분자·분모 모두 '항목' 단위 — 2026-09-23 C2) ─────────────────
    /** 제약 검사를 받은 항목 수 = 장소 + 활동 */
    val itemCount: Int,
    /** 불호를 하나라도 건드린 **항목**의 수(중복 제거). 구절 수가 아니다 */
    val violationItemCount: Int,
    // ── 전 시도 누적 (모델 원시) ──────────────────────────────────────────
    val rawVerifiedCount: Int = 0,
    val rawNotFoundCount: Int = 0,
    val rawUnverifiedCount: Int = 0,
    // ── 기타 ──────────────────────────────────────────────────────────────
    val attempts: Int,
    val retryEfficiency: Double,
) {
    /** 판정 가능분 = 실존 확인 + 실존 부정. UNVERIFIED 제외 */
    val judgedCount: Int get() = verifiedCount + notFoundCount
    val rawJudgedCount: Int get() = rawVerifiedCount + rawNotFoundCount

    /** 실존 확인율 — 판정 가능분 중 실존이 확인된 비율. 판정 가능분이 0이면 null */
    val verifiedRate: Double?
        get() = if (judgedCount == 0) null else verifiedCount.toDouble() / judgedCount

    /** 할루시네이션율 (최종 결과 기준 — 구조상 거의 항상 0) */
    val hallucinationRate: Double?
        get() = if (judgedCount == 0) null else notFoundCount.toDouble() / judgedCount

    /** 할루시네이션율 (모델 원시 — 재시도로 폐기된 시도까지 포함) */
    val rawHallucinationRate: Double?
        get() = if (rawJudgedCount == 0) null else rawNotFoundCount.toDouble() / rawJudgedCount

    /** 확인률(커버리지) — 전체 장소 중 실존 판정이 가능했던 비율 */
    val coverage: Double?
        get() = if (placeCount == 0) null else judgedCount.toDouble() / placeCount

    /** 제약 준수율 — 불호를 건드리지 않은 항목의 비율 */
    val constraintCompliance: Double?
        get() = if (itemCount == 0) null else
            ((itemCount - violationItemCount).toDouble() / itemCount).coerceIn(0.0, 1.0)

    companion object {
        /**
         * 만족 판정 임계값.
         *
         * 근거: 후기 평점이 5점 척도이고 **4점("만족") 이상을 만족으로 정의**하므로 4/5 = 0.8이다.
         * 즉 "80%"는 감으로 고른 숫자가 아니라 척도에서 유도된 운영 정의다. 표본이 쌓이면
         * 실제 만족도 분포로 재산정한다.
         */
        const val SATISFACTION_THRESHOLD = 0.8

        /** 후기 별점(1~5) → 0~1 만족도. 척도 밖 값은 잘라낸다. */
        fun satisfactionFromRating(rating: Int): Double = (rating.coerceIn(1, 5)) / 5.0

        /**
         * 실행 결과에서 지표를 계산한다.
         *
         * @param places             최종 시도의 추천 장소(Guardrail 검증 상태가 병합된 상태)
         * @param violationItemCount 불호를 건드린 **항목 수** ([ReflectionService.violatingItemCount])
         * @param itemCount          제약 검사를 받은 항목 총수(장소 + 활동)
         * @param rawCounts          전 시도 누적 검증 상태 개수. 없으면 최종 시도 값으로 대체되지 않고 0
         */
        fun of(
            places: List<RecommendedPlace>,
            violationItemCount: Int,
            itemCount: Int,
            attempts: Int,
            maxAttempts: Int,
            rawCounts: VerificationTally = VerificationTally(),
        ): HarnessMetrics {
            val tally = VerificationTally.of(places)
            // 1회에 통과 = 1.0, 상한까지 갔으면 0.0 — 재시도는 결과를 구하지만 비용이기도 하다
            val efficiency = if (maxAttempts <= 1) 1.0
            else (1.0 - (attempts - 1).toDouble() / (maxAttempts - 1)).coerceIn(0.0, 1.0)

            return HarnessMetrics(
                placeCount = places.size,
                verifiedCount = tally.verified,
                notFoundCount = tally.notFound,
                unverifiedCount = tally.unverified,
                itemCount = itemCount,
                violationItemCount = violationItemCount.coerceAtLeast(0),
                rawVerifiedCount = rawCounts.verified,
                rawNotFoundCount = rawCounts.notFound,
                rawUnverifiedCount = rawCounts.unverified,
                attempts = attempts,
                retryEfficiency = efficiency,
            )
        }
    }

    /** 사람이 읽는 한 줄 요약 — 디버그 로그·하네스 패널용. */
    fun summary(): String = buildString {
        append("실존 확인율 ${pct(verifiedRate)} · 할루시네이션율 ${pct(hallucinationRate)}")
        append(" · 확인률 ${pct(coverage)} · 제약 준수율 ${pct(constraintCompliance)}")
        if (rawJudgedCount > 0) append(" · 원시 할루시네이션율 ${pct(rawHallucinationRate)}")
        append("\n(장소 $placeCount = 검증 $verifiedCount / 미발견 $notFoundCount / 확인불가 $unverifiedCount")
        append(", 위반 항목 $violationItemCount/$itemCount, 시도 ${attempts}회")
        if (rawJudgedCount > 0) append(", 전 시도 누적 검증 $rawVerifiedCount / 미발견 $rawNotFoundCount")
        append(")")
    }

    private fun pct(v: Double?): String =
        if (v == null) "판정 불가" else "${Math.round(v * 1000) / 10.0}%"
}

/** 검증 상태 집계 — 시도별 누적에 쓴다. */
data class VerificationTally(
    val verified: Int = 0,
    val notFound: Int = 0,
    val unverified: Int = 0,
) {
    operator fun plus(other: VerificationTally) = VerificationTally(
        verified + other.verified,
        notFound + other.notFound,
        unverified + other.unverified,
    )

    companion object {
        fun of(places: List<RecommendedPlace>) = VerificationTally(
            verified = places.count { it.verification == VerificationStatus.VERIFIED },
            notFound = places.count { it.verification == VerificationStatus.NOT_FOUND },
            unverified = places.count { it.verification == VerificationStatus.UNVERIFIED },
        )
    }
}

/**
 * 만족도 추이 1점 — 재학습 루프가 실제로 개선을 만들었는지 보여주는 시계열의 원소.
 *
 * @param satisfaction 사용자 후기 평점에서 온 0~1 값. 아직 후기가 없으면 null
 */
data class HarnessRunPoint(
    val timestamp: Long,
    val roomId: String,
    val context: MeetingContext,
    val metrics: HarnessMetrics,
    val satisfaction: Double?,
    val consistencyRatio: Double,
)

/** 추이 통계 — 임계값 도달 여부를 표본 수와 함께 정직하게 보고한다. */
data class TrendSummary(
    val points: List<HarnessRunPoint>,
    val averageSatisfaction: Double?,
    val averageVerifiedRate: Double?,
    val averageHallucination: Double?,
    val averageCoverage: Double?,
    val averageCompliance: Double?,
    val ratedCount: Int,
    val meetsThreshold: Boolean,
) {
    val sampleSize: Int get() = points.size

    companion object {
        fun of(points: List<HarnessRunPoint>): TrendSummary {
            if (points.isEmpty()) {
                return TrendSummary(emptyList(), null, null, null, null, null, 0, false)
            }
            val rated = points.mapNotNull { it.satisfaction }
            val avgSat = rated.takeIf { it.isNotEmpty() }?.average()
            // 판정 불가(null)인 실행은 평균에서 제외한다 — 0으로 치면 API 장애가 성능 저하로 보인다
            fun avg(sel: (HarnessMetrics) -> Double?): Double? =
                points.mapNotNull { sel(it.metrics) }.takeIf { it.isNotEmpty() }?.average()

            return TrendSummary(
                points = points,
                averageSatisfaction = avgSat,
                averageVerifiedRate = avg { it.verifiedRate },
                averageHallucination = avg { it.hallucinationRate },
                averageCoverage = avg { it.coverage },
                averageCompliance = avg { it.constraintCompliance },
                ratedCount = rated.size,
                meetsThreshold = avgSat != null && avgSat >= HarnessMetrics.SATISFACTION_THRESHOLD,
            )
        }
    }
}
