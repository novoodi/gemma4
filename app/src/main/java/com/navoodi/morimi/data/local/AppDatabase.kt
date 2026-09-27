package com.navoodi.morimi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserStatusEntity::class,
        FeedbackEntity::class,
        RecommendedRoomEntity::class,
        MeetingSummaryEntity::class,
        HarnessRunEntity::class,
        AhpJudgmentEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userStatusDao(): UserStatusDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun recommendedRoomDao(): RecommendedRoomDao
    abstract fun meetingSummaryDao(): MeetingSummaryDao
    abstract fun harnessRunDao(): HarnessRunDao
    abstract fun ahpJudgmentDao(): AhpJudgmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v3→v4: meeting_summary 테이블 추가. 이 시점부터 후기+임베딩은 지우면 안 되는
        // 실사용 데이터라 파괴적 폴백에 맡기지 않고 명시적 마이그레이션으로 보존한다.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meeting_summary` (" +
                        "`roomId` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`roomId`))"
                )
            }
        }

        // v4→v5: 하네스 지표(harness_run)·학습된 AHP 판단(ahp_judgment) 테이블 추가,
        // feedback에 5점 척도 rating 컬럼 추가(0 = 미평가).
        // 후기·임베딩·지난 추천은 지우면 안 되는 실사용 데이터라 여기서도 명시적 마이그레이션.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `harness_run` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`roomId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `context` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `accuracy` REAL NOT NULL, " +
                        "`hallucinationRate` REAL NOT NULL, `constraintFitness` REAL NOT NULL, " +
                        "`retryEfficiency` REAL NOT NULL, `placeCount` INTEGER NOT NULL, " +
                        "`verifiedCount` INTEGER NOT NULL, `notFoundCount` INTEGER NOT NULL, " +
                        "`unverifiedCount` INTEGER NOT NULL, `violationCount` INTEGER NOT NULL, " +
                        "`consistencyRatio` REAL NOT NULL, `satisfaction` REAL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ahp_judgment` (" +
                        "`context` TEXT NOT NULL, `deltas` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`context`))"
                )
                db.execSQL("ALTER TABLE `feedback` ADD COLUMN `rating` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5→v6 (2026-09-23): 지표를 비율이 아니라 **개수**로 보관하도록 컬럼 추가.
        // 비율만 저장하면 정의가 바뀔 때 과거 행을 재계산할 수 없다(D1에서 실제로 분모가 바뀜).
        // 또 제약 준수율의 분자·분모 단위를 '항목'으로 맞추기 위해 itemCount/violationItemCount를,
        // 모델 원시 할루시네이션 집계를 위해 raw* 를 추가한다.
        // feedback.createdAt은 후기 팝업 조건(C7)이 "최신 추천 이후 후기인가"를 판정하는 데 쓴다.
        // MIGRATION_4_5는 건드리지 않는다 — v5 개발 빌드를 설치한 기기가 있으면 무결성 검사에서 깨진다.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "itemCount", "violationItemCount",
                    "rawVerifiedCount", "rawNotFoundCount", "rawUnverifiedCount",
                ).forEach { col ->
                    db.execSQL("ALTER TABLE `harness_run` ADD COLUMN `$col` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE `feedback` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                // 기존 후기는 date('YYYY-MM-DD')를 자정 기준 epoch ms로 환산해 채운다.
                // 형식이 다른 행은 0으로 남고, 그런 방은 팝업 조건에서 폴백 경로를 탄다.
                db.execSQL(
                    "UPDATE `feedback` SET `createdAt` = CAST(strftime('%s', `date`) AS INTEGER) * 1000 " +
                        "WHERE `createdAt` = 0 AND `date` LIKE '____-__-__'"
                )
            }
        }

        /**
         * 앱이 실제로 등록하는 마이그레이션 목록.
         *
         * [getInstance]와 **같은 배열을 쓴다.** 테스트가 자기만의 목록을 만들면
         * 등록 누락을 영영 못 잡는데, `fallbackToDestructiveMigration()`이 켜져 있어
         * 누락은 크래시가 아니라 **조용한 데이터 삭제**로 나타난다.
         * (`MigrationRuntimeTest` 2026-09-23 S3)
         */
        fun productionMigrations(): Array<Migration> =
            arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moim_database"
                )
                    .addMigrations(*productionMigrations())
                    // v1→v2: feedback 테이블 추가. 압축 상태(user_status)는 재생성되고
                    // 기존 후기는 로컬 데모 데이터라 파괴적 마이그레이션 허용 (DEVLOG 기록)
                    // — v3 미만에서 올라오는 경우에만 적용되는 레거시 폴백
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}