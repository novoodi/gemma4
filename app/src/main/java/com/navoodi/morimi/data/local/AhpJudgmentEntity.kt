package com.navoodi.morimi.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 상황별로 학습된 AHP 쌍대비교 판단 보정.
 *
 * 학습 결과를 **가중치가 아니라 판단(입력)으로** 저장하는 이유는, 다시 불러올 때도
 * 일관성 검사(CR)를 거치게 하기 위해서다. 가중치를 저장하면 검증 없이 부활한다.
 * 직렬화 포맷은 [com.navoodi.morimi.service.AhpJudgmentLearner.encode] 참조.
 *
 * 이 데이터는 이 기기 사용자 한 명의 것이다 — 후기와 마찬가지로 기기 밖으로 나가지 않는다.
 */
@Entity(tableName = "ahp_judgment")
data class AhpJudgmentEntity(
    /** [com.navoodi.morimi.service.MeetingContext] 이름 */
    @PrimaryKey val context: String,
    /** "CONSTRAINT_SAFETY>PURPOSE_FIT:1|VERIFIED_TRUST>PURPOSE_FIT:2" 형태 */
    val deltas: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface AhpJudgmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AhpJudgmentEntity)

    @Query("SELECT * FROM ahp_judgment WHERE context = :context")
    suspend fun get(context: String): AhpJudgmentEntity?

    @Query("SELECT * FROM ahp_judgment")
    suspend fun getAll(): List<AhpJudgmentEntity>

    @Query("DELETE FROM ahp_judgment")
    suspend fun clear()
}
