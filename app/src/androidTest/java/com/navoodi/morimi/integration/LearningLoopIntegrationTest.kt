package com.navoodi.morimi.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.model.RecommendedPlace
import com.navoodi.morimi.data.model.VerificationStatus
import com.navoodi.morimi.data.repository.MetricsRepository
import com.navoodi.morimi.service.AhpCriterion
import com.navoodi.morimi.service.HarnessMetrics
import com.navoodi.morimi.service.MeetingContext
import com.navoodi.morimi.service.VerificationTally
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **재학습 루프 통합 테스트 (2026-09-23 B5, 에뮬레이터 API 36 x86_64).**
 *
 * 그동안 R7(후기 평점 → 재학습)은 두 조각으로만 검증돼 있었다 —
 * `AhpJudgmentLearnerTest`(순수 로직)와 `HarnessMetricsTest`(지표 계산).
 * **그 둘을 잇는 배선은 실제 DB 위에서 한 번도 돌려 본 적이 없다.**
 * 여기서는 운영 `MetricsRepository`를 실제 Android SQLite 위에서 그대로 써서,
 * 기록 → 평점 → 원인 판정 → 판단 보정 → 영속화까지를 한 줄로 검증한다.
 *
 * 특히 3차에서 R6으로 밝힌 경로를 실제 DB로 확인한다:
 * **"없는 가게" 원인은 최종 결과가 아니라 전 시도 누적(`rawNotFoundCount`)을 읽어야 한다.**
 * 최종 결과에는 NOT_FOUND가 구조적으로 남지 않아서, 최종 기준으로 보면 이 학습 경로는
 * 영원히 발동하지 않는다. 그 수정이 DB를 거쳐도 살아 있는지가 이 테스트의 핵심이다.
 */
@RunWith(AndroidJUnit4::class)
class LearningLoopIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repo: MetricsRepository

    @Before
    fun setUp() = runBlocking {
        repo = MetricsRepository(context)
        repo.clearAll()
    }

    @After
    fun tearDown() = runBlocking { repo.clearAll() }

    /** 원시 누적(raw)과 최종 결과를 따로 지정해 실행 1건을 기록한다. */
    private fun record(
        roomId: String,
        ctx: MeetingContext = MeetingContext.MEAL,
        rawNotFound: Int = 0,
        violationItems: Int = 0,
    ): Long = runBlocking {
        // 최종 결과는 항상 '검증된 장소 3곳' — 재시도를 통과한 결과라 NOT_FOUND가 없다
        val places = List(3) {
            RecommendedPlace("가게$it", reason = "설명", verification = VerificationStatus.VERIFIED)
        }
        val metrics = HarnessMetrics.of(
            places = places,
            violationItemCount = violationItems,
            itemCount = 3,
            attempts = if (rawNotFound > 0) 2 else 1,
            maxAttempts = 3,
            // 폐기된 시도까지 누적한 원시 집계 — 여기에만 NOT_FOUND가 남는다
            rawCounts = VerificationTally(
                verified = 3,
                notFound = rawNotFound,
                unverified = 0,
            ),
        )
        repo.recordRun(roomId, ctx, metrics, consistencyRatio = 0.0177)
    }

    private fun deltasOf(ctx: MeetingContext) = runBlocking {
        repo.weightsFor(ctx).appliedDeltas.map { "${it.row}>${it.col}:${it.steps}" }.toSet()
    }

    // ── 원인별 보정 ──────────────────────────────────────────────────────────

    @Test
    fun 없는_가게가_있었고_2점이면_검증신뢰_축이_올라간다() = runBlocking {
        record("room-nf", rawNotFound = 1)
        val learned = repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-nf")), rating = 2)

        assertTrue("학습 결과가 없다", learned != null)
        assertEquals(
            setOf("VERIFIED_TRUST>PREFERENCE_FIT:1"),
            deltasOf(MeetingContext.MEAL),
        )
        // R6의 핵심: 최종 결과의 notFoundCount는 0인데도 학습이 걸려야 한다
        val row = AppDatabase.getInstance(context).harnessRunDao().getByRoom("room-nf").last()
        assertEquals("최종 결과에는 NOT_FOUND가 없다", 0, row.notFoundCount)
        assertEquals("원시 누적에는 남는다", 1, row.rawNotFoundCount)
    }

    @Test
    fun 제약_위반이_있었고_2점이면_제약준수_축이_올라간다() = runBlocking {
        record("room-vi", violationItems = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-vi")), rating = 2)

        assertEquals(
            setOf("CONSTRAINT_SAFETY>PREFERENCE_FIT:1"),
            deltasOf(MeetingContext.MEAL),
        )
    }

    @Test
    fun 원인이_둘_다면_두_축이_모두_올라간다() = runBlocking {
        record("room-both", rawNotFound = 1, violationItems = 2)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-both")), rating = 2)

        assertEquals(
            setOf(
                "CONSTRAINT_SAFETY>PREFERENCE_FIT:1",
                "VERIFIED_TRUST>PREFERENCE_FIT:1",
            ),
            deltasOf(MeetingContext.MEAL),
        )
    }

    @Test
    fun 원인이_없는_불만은_취향과_과거만족을_올린다() = runBlocking {
        record("room-none")
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-none")), rating = 2)

        assertEquals(
            setOf(
                "PREFERENCE_FIT>PURPOSE_FIT:1",
                "PAST_SATISFACTION>PURPOSE_FIT:1",
            ),
            deltasOf(MeetingContext.MEAL),
        )
    }

    // ── 무동작과 감쇠 ────────────────────────────────────────────────────────

    @Test
    fun 삼점은_아무것도_바꾸지_않는다() = runBlocking {
        record("room-3a", rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-3a")), rating = 2)
        val before = deltasOf(MeetingContext.MEAL)

        record("room-3b", rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-3b")), rating = 3)
        val after = deltasOf(MeetingContext.MEAL)

        assertEquals("3점이 판단을 바꿨다 (D3 위반)", before, after)
    }

    @Test
    fun 만족하면_가장_오래된_보정_1건만_풀린다() = runBlocking {
        // 1) 없는 가게 → 검증 신뢰 보정
        record("room-f1", rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-f1")), rating = 2)
        // 2) 제약 위반 → 제약 준수 보정
        record("room-f2", violationItems = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-f2")), rating = 2)
        assertEquals("보정 2건이 쌓여야 한다", 2, deltasOf(MeetingContext.MEAL).size)

        // 3) 5점 → FIFO로 가장 오래된 것(검증 신뢰)만 해제
        record("room-f3")
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-f3")), rating = 5)

        assertEquals(
            "가장 오래된 보정만 풀려야 한다 (D6)",
            setOf("CONSTRAINT_SAFETY>PREFERENCE_FIT:1"),
            deltasOf(MeetingContext.MEAL),
        )
    }

    // ── 상황 분리와 영속성 ───────────────────────────────────────────────────

    @Test
    fun 상황이_다르면_학습도_분리된다() = runBlocking {
        record("room-meal", ctx = MeetingContext.MEAL, rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-meal")), rating = 2)

        assertTrue("MEAL은 학습돼야 한다", deltasOf(MeetingContext.MEAL).isNotEmpty())
        assertTrue("DRINK까지 번지면 안 된다", deltasOf(MeetingContext.DRINK).isEmpty())
    }

    @Test
    fun 학습_결과가_DB를_거쳐_다음_회차_가중치에_반영된다() = runBlocking {
        val base = repo.weightsFor(MeetingContext.MEAL).result.weightOf(AhpCriterion.VERIFIED_TRUST)

        record("room-persist", rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-persist")), rating = 2)

        // 저장소를 새로 만들어 읽는다 — 메모리 캐시가 아니라 DB에서 살아 돌아오는지 본다
        val reopened = MetricsRepository(context)
        val after = reopened.weightsFor(MeetingContext.MEAL)

        assertTrue("학습이 DB에 남지 않았다", after.isLearned)
        assertTrue(
            "검증 신뢰 가중치가 올라야 한다: $base -> ${after.result.weightOf(AhpCriterion.VERIFIED_TRUST)}",
            after.result.weightOf(AhpCriterion.VERIFIED_TRUST) > base,
        )
        assertTrue("CR 게이트를 통과한 상태여야 한다", after.result.consistent)
    }

    @Test
    fun 추이_요약이_실제_기록에서_계산된다() = runBlocking {
        record("room-t1", rawNotFound = 1)
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-t1")), rating = 2)
        record("room-t2")
        repo.attachSatisfaction(checkNotNull(repo.feedbackTargetFor("room-t2")), rating = 5)

        val trend = repo.trend()
        assertEquals("표본 수", 2, trend.points.size)
        assertEquals("평점이 달린 건수", 2, trend.ratedCount)
        // 최종 결과는 3곳 모두 VERIFIED라 실존 확인율 100%, 할루시네이션율 0%
        assertEquals(1.0, trend.averageVerifiedRate!!, 1e-6)
        assertEquals(0.0, trend.averageHallucination!!, 1e-6)
    }
}
