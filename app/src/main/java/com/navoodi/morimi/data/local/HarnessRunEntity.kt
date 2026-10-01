package com.navoodi.morimi.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 검증 하네스 1회 실행의 기록. 만족도 추이·품질 추이 그래프의 원자료다.
 *
 * "80%면 만족"이라는 기준을 감으로 말하지 않으려면 실행마다 측정치가 남아 있어야 한다.
 * (회의 피드백 2026-09-14: "그거는 근거가 뭐냐고" / "그래프로 보여주면 여기까지 좋아졌다")
 *
 * **개수로 저장한다 (2026-09-23 v6).** 비율만 저장하면 지표 정의가 바뀔 때 과거 행을 다시
 * 계산할 수 없다 — 실제로 D1 결정에서 분모 정의가 바뀌었다. 비율은 읽을 때 계산한다.
 *
 * [satisfaction]은 실행 시점엔 알 수 없다 — 모임을 다녀와 후기를 남길 때 뒤늦게 채워진다.
 * 그래서 nullable이고, 후기 입력 시작 시 확보한 실행 ID로 한 번만 갱신한다.
 */
@Entity(tableName = "harness_run")
data class HarnessRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** [com.navoodi.morimi.service.MeetingContext] 이름. enum이 바뀌어도 읽기가 깨지지 않게 문자열로 보관 */
    val context: String,
    val attempts: Int,

    // ── 레거시 비율 컬럼 (v5) ────────────────────────────────────────────────
    // 정의가 바뀌어 더 이상 신뢰하지 않는다. 읽기는 전부 아래 개수 컬럼에서 한다.
    // NOT NULL이라 남겨 두고, 판정 불가는 0.0으로 채운다. **새 코드에서 읽지 말 것.**
    @Deprecated("v6부터 개수에서 계산한다. 읽지 말 것") val accuracy: Double = 0.0,
    @Deprecated("v6부터 개수에서 계산한다. 읽지 말 것") val hallucinationRate: Double = 0.0,
    @Deprecated("v6부터 개수에서 계산한다. 읽지 말 것") val constraintFitness: Double = 0.0,
    val retryEfficiency: Double,

    // ── 최종 시도 개수 ───────────────────────────────────────────────────────
    val placeCount: Int,
    val verifiedCount: Int,
    val notFoundCount: Int,
    val unverifiedCount: Int,
    /** 불호 '구절' 단위 위반 수 (레거시 — 지표는 [violationItemCount]를 쓴다) */
    val violationCount: Int,

    // ── v6 신규: 제약 준수율의 분자·분모를 같은 '항목' 단위로 ────────────────
    // @ColumnInfo(defaultValue)를 붙여야 Room의 기대 스키마에도 DEFAULT가 생겨
    // ALTER TABLE ... DEFAULT 0 으로 만든 컬럼과 정확히 일치한다(마이그레이션 검증 통과).
    /** 제약 검사를 받은 항목 총수 = 장소 + 활동. v5 이전 행은 0(판정 불가) */
    @ColumnInfo(defaultValue = "0") val itemCount: Int = 0,
    /** 불호를 하나라도 건드린 **항목** 수(중복 제거). v5 이전 행은 0 */
    @ColumnInfo(defaultValue = "0") val violationItemCount: Int = 0,

    // ── v6 신규: 전 시도 누적(모델 원시) ─────────────────────────────────────
    // 최종 결과에는 NOT_FOUND가 구조적으로 남지 않으므로, 모델이 실제로 없는 가게를
    // 몇 번 지어냈는지는 재시도로 폐기된 시도까지 세어야 보인다.
    @ColumnInfo(defaultValue = "0") val rawVerifiedCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val rawNotFoundCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val rawUnverifiedCount: Int = 0,

    /** 이 실행에 쓰인 AHP 판단의 일관성 비율 — 가중치를 신뢰할 수 있었는지의 근거 */
    val consistencyRatio: Double,
    /** 사후 후기 평점에서 온 0~1 만족도. 아직 후기가 없으면 null */
    val satisfaction: Double? = null,
)

@Dao
interface HarnessRunDao {

    @Insert
    suspend fun insert(entity: HarnessRunEntity): Long

    /** 입력 시작 시 선택한 실행만 갱신한다. 이미 평가했거나 다른 방이면 0행이다. */
    @Query(
        "UPDATE harness_run SET satisfaction = :satisfaction " +
            "WHERE id = :runId AND roomId = :roomId AND satisfaction IS NULL"
    )
    suspend fun attachSatisfaction(runId: Long, roomId: String, satisfaction: Double): Int

    @Query("SELECT * FROM harness_run WHERE id = :runId")
    suspend fun getById(runId: Long): HarnessRunEntity?

    @Query("SELECT * FROM harness_run WHERE roomId = :roomId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestByRoom(roomId: String): HarnessRunEntity?

    @Query("SELECT * FROM harness_run ORDER BY timestamp ASC")
    suspend fun getAll(): List<HarnessRunEntity>

    @Query("SELECT * FROM harness_run WHERE roomId = :roomId ORDER BY timestamp ASC")
    suspend fun getByRoom(roomId: String): List<HarnessRunEntity>

    /** 이 방의 가장 최근 실행 시각. 추천을 한 번도 안 받았으면 null (C7 후기 팝업 조건) */
    @Query("SELECT MAX(timestamp) FROM harness_run WHERE roomId = :roomId")
    suspend fun latestRunTimestamp(roomId: String): Long?

    /** 특정 상황의 최근 실행 — 재학습 증거로 쓴다(상황이 다르면 학습도 달라야 하므로). */
    @Query("SELECT * FROM harness_run WHERE context = :context ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByContext(context: String, limit: Int): List<HarnessRunEntity>

    @Query("DELETE FROM harness_run")
    suspend fun clear()
}
