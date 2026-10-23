package com.navoodi.morimi.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.navoodi.morimi.data.local.AhpJudgmentEntity
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.HarnessRunEntity
import com.navoodi.morimi.service.AhpJudgmentLearner
import com.navoodi.morimi.service.HarnessMetrics
import com.navoodi.morimi.service.HarnessRunPoint
import com.navoodi.morimi.service.LearnedWeights
import com.navoodi.morimi.service.MeetingContext
import com.navoodi.morimi.service.RunEvidence
import com.navoodi.morimi.service.TrendSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 하네스 지표 기록 + AHP 판단 학습 저장소 — **피드백 순환 고리의 영속 계층**.
 *
 *   추천 실행 → [recordRun] (정확도·할루시네이션·적합도 기록)
 *        → 모임 후 후기 평점 → [attachSatisfaction] (만족도 부착 + 재학습 반영)
 *        → 다음 추천의 [weightsFor] (학습된 기준 가중치)
 *
 * 저장·학습 모두 기기 안에서 끝난다. 지표는 사용자 행동 기록이라 클라우드로 보내지 않는다.
 */
class MetricsRepository internal constructor(private val database: AppDatabase) {

    constructor(context: Context) : this(AppDatabase.getInstance(context))

    private val runDao = database.harnessRunDao()
    private val judgmentDao = database.ahpJudgmentDao()

    /** 후기 입력 시작 시 고정하는 대상. 저장 중 새 추천이 생겨도 바뀌지 않는다. */
    data class FeedbackTarget(val runId: Long, val roomId: String)

    suspend fun feedbackTargetFor(roomId: String): FeedbackTarget? = withContext(Dispatchers.IO) {
        runDao.latestByRoom(roomId)?.takeIf { it.satisfaction == null }
            ?.let { FeedbackTarget(it.id, it.roomId) }
    }

    companion object {
        private const val TAG = "MetricsRepository"
    }

    // ── 지표 기록 ────────────────────────────────────────────────────────────

    /** 하네스 1회 실행 결과를 남긴다. 실패해도 추천 자체는 이미 끝났으므로 조용히 삼킨다. */
    suspend fun recordRun(
        roomId: String,
        context: MeetingContext,
        metrics: HarnessMetrics,
        consistencyRatio: Double,
    ): Long = withContext(Dispatchers.IO) {
        runCatching {
            runDao.insert(
                HarnessRunEntity(
                    roomId = roomId,
                    context = context.name,
                    attempts = metrics.attempts,
                    // 레거시 비율 컬럼 — NOT NULL이라 채우기만 한다. 읽기는 개수에서 계산한다
                    accuracy = metrics.verifiedRate ?: 0.0,
                    hallucinationRate = metrics.hallucinationRate ?: 0.0,
                    constraintFitness = metrics.constraintCompliance ?: 0.0,
                    retryEfficiency = metrics.retryEfficiency,
                    placeCount = metrics.placeCount,
                    verifiedCount = metrics.verifiedCount,
                    notFoundCount = metrics.notFoundCount,
                    unverifiedCount = metrics.unverifiedCount,
                    violationCount = metrics.violationItemCount,
                    itemCount = metrics.itemCount,
                    violationItemCount = metrics.violationItemCount,
                    rawVerifiedCount = metrics.rawVerifiedCount,
                    rawNotFoundCount = metrics.rawNotFoundCount,
                    rawUnverifiedCount = metrics.rawUnverifiedCount,
                    consistencyRatio = consistencyRatio,
                )
            )
        }.onFailure { Log.e(TAG, "하네스 지표 기록 실패 roomId=$roomId", it) }
            .getOrDefault(-1L)
    }

    /**
     * 입력 시작 시 선택한 실행에 만족도를 붙이고, **같은 실행의 증거로 재학습한다**.
     * 여기가 "후기 → 다음 추천"의 고리가 실제로 닫히는 지점이다.
     *
     * 만족도 부착과 판단 저장은 하나의 트랜잭션이다. 저장 실패 시 둘 다 롤백하며,
     * 이미 평가된 실행은 다시 학습하지 않는다. 예외는 호출측에 전달한다.
     * @return 학습이 반영된 가중치. 대상이 없거나 다른 방/이미 평가된 실행이면 null
     */
    suspend fun attachSatisfaction(target: FeedbackTarget, rating: Int): LearnedWeights? =
        withContext(Dispatchers.IO) {
            require(rating in 1..5) { "후기 평점은 1~5여야 합니다" }
            val satisfaction = HarnessMetrics.satisfactionFromRating(rating)
            database.withTransaction {
                val run = runDao.getById(target.runId) ?: return@withTransaction null
                if (run.roomId != target.roomId || run.satisfaction != null) return@withTransaction null
                if (runDao.attachSatisfaction(target.runId, target.roomId, satisfaction) != 1) {
                    return@withTransaction null
                }
                // 기존 정책: 없는 가게 증거는 최종 결과가 아닌 전 시도 누적(raw)을 쓴다.
                learnInTransaction(
                    parseContext(run.context),
                    RunEvidence(run.violationItemCount, run.rawNotFoundCount, satisfaction),
                )
            }
        }

    // ── AHP 판단 학습 ────────────────────────────────────────────────────────

    /**
     * 증거를 반영해 이 상황의 판단 보정을 갱신·영속한다.
     * CR 게이트에서 기각된 보정은 저장하지 않는다 — 저장했다가 다음에 되살아나면
     * 게이트를 통과하지 못한 판단이 조용히 재사용된다.
     */
    suspend fun learn(context: MeetingContext, evidence: RunEvidence): LearnedWeights =
        withContext(Dispatchers.IO) {
            database.withTransaction { learnInTransaction(context, evidence) }
        }

    private suspend fun learnInTransaction(context: MeetingContext, evidence: RunEvidence): LearnedWeights {
        // 학습 중 읽기 실패를 빈 이력으로 대체하면 기존 보정을 덮어쓰므로 예외를 전달한다.
        val current = judgmentDao.get(context.name)?.deltas
            ?.let { AhpJudgmentLearner.decode(it) } ?: emptyList()
        val updated = AhpJudgmentLearner.learn(current, evidence)
        val resolved = AhpJudgmentLearner.resolve(context, updated)

        judgmentDao.upsert(
            AhpJudgmentEntity(
                context = context.name,
                deltas = AhpJudgmentLearner.encode(resolved.appliedDeltas),
            )
        )

        Log.d(
            TAG,
            "재학습 ${context.name}: 보정 ${resolved.appliedDeltas.size}건 " +
                "(기각 ${resolved.droppedDeltas.size}건) CR=${resolved.result.cr}",
        )
        return resolved
    }

    /** 이 상황에 적용할 기준 가중치 — 학습된 보정이 있으면 반영, 없으면 프리셋 그대로. */
    suspend fun weightsFor(context: MeetingContext): LearnedWeights = withContext(Dispatchers.IO) {
        AhpJudgmentLearner.resolve(context, loadDeltas(context))
    }

    private suspend fun loadDeltas(context: MeetingContext) =
        runCatching { judgmentDao.get(context.name)?.deltas }
            .getOrNull()
            ?.let { AhpJudgmentLearner.decode(it) }
            ?: emptyList()

    // ── 추이 조회 ────────────────────────────────────────────────────────────

    /** 전체 실행 추이 — 만족도·정확도 그래프의 데이터 소스. */
    suspend fun trend(): TrendSummary = withContext(Dispatchers.IO) {
        val rows = runCatching { runDao.getAll() }.getOrDefault(emptyList())
        TrendSummary.of(rows.map { it.toPoint() })
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching {
            runDao.clear()
            judgmentDao.clear()
        }.onFailure { Log.e(TAG, "지표 초기화 실패", it) }
        Unit
    }

    private fun HarnessRunEntity.toPoint() = HarnessRunPoint(
        timestamp = timestamp,
        roomId = roomId,
        context = parseContext(context),
        // 개수에서 복원한다 — 비율 컬럼은 레거시라 읽지 않는다(정의가 바뀌어도 재계산 가능)
        metrics = HarnessMetrics(
            placeCount = placeCount,
            verifiedCount = verifiedCount,
            notFoundCount = notFoundCount,
            unverifiedCount = unverifiedCount,
            itemCount = itemCount,
            violationItemCount = violationItemCount,
            rawVerifiedCount = rawVerifiedCount,
            rawNotFoundCount = rawNotFoundCount,
            rawUnverifiedCount = rawUnverifiedCount,
            attempts = attempts,
            retryEfficiency = retryEfficiency,
        ),
        satisfaction = satisfaction,
        consistencyRatio = consistencyRatio,
    )

    /** 모르는 상황 이름(앱 버전 간 enum 변화)은 GENERIC으로 — 방어적 파싱(컨벤션 #2). */
    private fun parseContext(name: String): MeetingContext =
        runCatching { MeetingContext.valueOf(name) }.getOrDefault(MeetingContext.GENERIC)
}
