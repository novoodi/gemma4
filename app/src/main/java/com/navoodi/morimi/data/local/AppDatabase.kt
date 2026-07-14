package com.navoodi.morimi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserStatusEntity::class, FeedbackEntity::class, RecommendedRoomEntity::class, MeetingSummaryEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userStatusDao(): UserStatusDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun recommendedRoomDao(): RecommendedRoomDao
    abstract fun meetingSummaryDao(): MeetingSummaryDao

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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moim_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    // v1→v2: feedback 테이블 추가. 압축 상태(user_status)는 재생성되고
                    // 기존 후기는 로컬 데모 데이터라 파괴적 마이그레이션 허용 (DEVLOG 기록)
                    // — v3 미만에서 올라오는 경우에만 적용되는 레거시 폴백
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}