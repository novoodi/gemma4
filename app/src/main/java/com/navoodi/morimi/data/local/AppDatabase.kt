package com.navoodi.morimi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [UserStatusEntity::class, FeedbackEntity::class, RecommendedRoomEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userStatusDao(): UserStatusDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun recommendedRoomDao(): RecommendedRoomDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moim_database"
                )
                    // v1→v2: feedback 테이블 추가. 압축 상태(user_status)는 재생성되고
                    // 기존 후기는 로컬 데모 데이터라 파괴적 마이그레이션 허용 (DEVLOG 기록)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}