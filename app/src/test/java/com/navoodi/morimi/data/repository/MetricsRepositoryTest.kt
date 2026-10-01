package com.navoodi.morimi.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.navoodi.morimi.data.local.AppDatabase
import com.navoodi.morimi.data.local.HarnessRunEntity
import com.navoodi.morimi.data.repository.MetricsRepository.FeedbackTarget
import com.navoodi.morimi.service.AhpJudgmentLearner
import com.navoodi.morimi.service.MeetingContext
import com.navoodi.morimi.service.RunEvidence
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 실제 Room으로 대상 고정·중복 방지·트랜잭션 롤백·동시 학습을 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetricsRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MetricsRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).build()
        repository = MetricsRepository(database)
    }

    @After
    fun tearDown() { database.close() }

    private suspend fun insert(
        roomId: String = "room",
        timestamp: Long = 1000L,
        context: MeetingContext = MeetingContext.MEAL,
        violations: Int = 1,
        hallucinations: Int = 0,
        satisfaction: Double? = null,
    ): Long = database.harnessRunDao().insert(
        HarnessRunEntity(
            roomId = roomId, timestamp = timestamp, context = context.name,
            attempts = 1, retryEfficiency = 1.0, placeCount = 1,
            verifiedCount = 1, notFoundCount = 0, unverifiedCount = 0,
            violationCount = violations, itemCount = 1, violationItemCount = violations,
            rawNotFoundCount = hallucinations, consistencyRatio = 0.0, satisfaction = satisfaction,
        )
    )

    @Test
    fun `입력창 이후 새 추천이 생겨도 선택했던 실행의 증거만 학습한다`() = runBlocking<Unit> {
        val oldId = insert(violations = 1)
        val target = checkNotNull(repository.feedbackTargetFor("room"))
        val newId = insert(timestamp = 2000, context = MeetingContext.CAFE, violations = 0, hallucinations = 2)
        val result = checkNotNull(repository.attachSatisfaction(target, 2))
        val expected = AhpJudgmentLearner.resolve(
            MeetingContext.MEAL, AhpJudgmentLearner.learn(emptyList(), RunEvidence(1, 0, 0.4)),
        )
        assertEquals(expected.appliedDeltas, result.appliedDeltas)
        assertEquals(0.4, database.harnessRunDao().getById(oldId)!!.satisfaction!!, 0.0)
        assertNull(database.harnessRunDao().getById(newId)!!.satisfaction)
        assertNull(database.ahpJudgmentDao().get(MeetingContext.CAFE.name))
    }

    @Test
    fun `최신 실행이 평가됐으면 과거 미평가 실행을 대신 평가하지 않는다`() = runBlocking<Unit> {
        val oldId = insert()
        insert(timestamp = 2000, satisfaction = 1.0)
        assertNull(repository.feedbackTargetFor("room"))
        assertNull(database.harnessRunDao().getById(oldId)!!.satisfaction)
        assertTrue(database.ahpJudgmentDao().getAll().isEmpty())
    }

    @Test
    fun `같은 시각에 기록된 실행은 ID가 큰 실행을 선택한다`() = runBlocking<Unit> {
        insert()
        val latestId = insert()
        assertEquals(latestId, repository.feedbackTargetFor("room")!!.runId)
    }

    @Test
    fun `평점 재전송은 기존 만족도와 판단을 바꾸지 않는다`() = runBlocking<Unit> {
        val id = insert()
        val target = FeedbackTarget(id, "room")
        repository.attachSatisfaction(target, 2)
        val before = database.ahpJudgmentDao().getAll()
        assertNull(repository.attachSatisfaction(target, 5))
        assertEquals(before, database.ahpJudgmentDao().getAll())
        assertEquals(0.4, database.harnessRunDao().getById(id)!!.satisfaction!!, 0.0)
    }

    @Test
    fun `없는 실행과 다른 방의 실행은 변경하지 않는다`() = runBlocking<Unit> {
        val id = insert()
        assertNull(repository.attachSatisfaction(FeedbackTarget(id, "other"), 2))
        assertNull(repository.attachSatisfaction(FeedbackTarget(id + 100, "room"), 2))
        assertNull(database.harnessRunDao().getById(id)!!.satisfaction)
        assertTrue(database.ahpJudgmentDao().getAll().isEmpty())
    }

    @Test
    fun `척도 밖 평점은 학습 전에 거부한다`() = runBlocking<Unit> {
        val id = insert()
        for (rating in listOf(-1, 0, 6)) {
            val error = runCatching { repository.attachSatisfaction(FeedbackTarget(id, "room"), rating) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
        assertNull(database.harnessRunDao().getById(id)!!.satisfaction)
        assertTrue(database.ahpJudgmentDao().getAll().isEmpty())
    }

    @Test
    fun `판단 저장 실패는 만족도도 롤백하며 재시도가 가능하다`() = runBlocking<Unit> {
        val id = insert()
        val target = FeedbackTarget(id, "room")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_judgment BEFORE INSERT ON ahp_judgment " +
                "BEGIN SELECT RAISE(ABORT, 'test storage failure'); END"
        )
        assertTrue(runCatching { repository.attachSatisfaction(target, 2) }.isFailure)
        assertNull(database.harnessRunDao().getById(id)!!.satisfaction)
        assertTrue(database.ahpJudgmentDao().getAll().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_judgment")
        assertNotNull(repository.attachSatisfaction(target, 2))
    }

    @Test
    fun `동일 실행을 동시에 평가해도 학습은 한 번만 반영한다`() = runBlocking<Unit> {
        val target = FeedbackTarget(insert(), "room")
        val results = List(2) { async { repository.attachSatisfaction(target, 2) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        val expected = AhpJudgmentLearner.resolve(
            MeetingContext.MEAL, AhpJudgmentLearner.learn(emptyList(), RunEvidence(1, 0, 0.4)),
        )
        assertEquals(expected.appliedDeltas, repository.weightsFor(MeetingContext.MEAL).appliedDeltas)
    }

    @Test
    fun `같은 상황의 서로 다른 실행을 동시에 학습해도 보정을 잃지 않는다`() = runBlocking<Unit> {
        val targets = listOf(FeedbackTarget(insert("a"), "a"), FeedbackTarget(insert("b"), "b"))
        targets.map { async { repository.attachSatisfaction(it, 2) } }.awaitAll()
        val evidence = RunEvidence(1, 0, 0.4)
        val first = AhpJudgmentLearner.resolve(MeetingContext.MEAL, AhpJudgmentLearner.learn(emptyList(), evidence))
        val second = AhpJudgmentLearner.resolve(MeetingContext.MEAL, AhpJudgmentLearner.learn(first.appliedDeltas, evidence))
        assertEquals(second.appliedDeltas, repository.weightsFor(MeetingContext.MEAL).appliedDeltas)
        assertEquals(2, database.harnessRunDao().getAll().count { it.satisfaction == 0.4 })
    }
}
