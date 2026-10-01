package com.navoodi.morimi.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.navoodi.morimi.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * **마이그레이션 Room 런타임 검증 (2026-09-23 S3).**
 *
 * `docs/00_START_HERE.md` 5절의 1순위 위험을 실기기 없이 줄이기 위한 테스트다.
 * 그동안 마이그레이션 SQL은 sqlite로 PRAGMA 기준까지 맞췄지만, **Room이 실행 시점에 하는
 * 스키마 무결성 검사를 실제로 통과시킨 적은 없었다.** 여기서 통과하지 못하면 실기기에서도
 * 첫 실행에 깨진다.
 *
 * 실기기 R2를 대체하지는 않는다 — 실제 사용자 DB의 데이터 분포와 WAL 상태는 재현하지 않는다.
 *
 * ### 왜 "크래시 없음"만으로는 부족한가
 *
 * `AppDatabase.getInstance`에는 `fallbackToDestructiveMigration()`이 걸려 있다.
 * 마이그레이션 경로가 없으면 Room은 **예외를 던지는 대신 DB를 지우고 새로 만든다.**
 * 그래서 이 테스트는 반드시 **시드한 후기 행이 살아남았는지**를 확인한다.
 * 그것이 R2의 완료 기준("기존 후기 유지")과 같은 조건이다.
 *
 * ### v4/v5 DDL의 출처
 *
 * 이 프로젝트에는 git 이력이 없어서(S2) 과거 Entity를 꺼내올 수 없다. 대신 KSP가 생성한
 * 현재 스키마(`AppDatabase_Impl.createAllTables`)에서 `MIGRATION_4_5`·`MIGRATION_5_6`이
 * 추가하는 것을 **역으로 제거해** 만들었다. 두 마이그레이션 본문이 곧 명세다:
 *  - 4→5: `harness_run`·`ahp_judgment` 테이블 신설 + `feedback.rating` 추가
 *  - 5→6: `harness_run`에 5개 컬럼 + `feedback.createdAt` 추가 + `createdAt` 백필
 *
 * `room_master_table`은 넣지 않는다. v4의 identity hash를 알 수 없기 때문이며,
 * 업그레이드 경로에서는 마이그레이션 후 Room이 새로 기록한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationRuntimeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_runtime_test.db"

    private fun dbFile(): File = context.getDatabasePath(dbName)

    @Before
    fun clean() = deleteDb()

    @After
    fun cleanup() = deleteDb()

    private fun deleteDb() {
        listOf("", "-wal", "-shm").forEach { File(dbFile().path + it).delete() }
    }

    // ── v4 / v5 스키마 재현 ──────────────────────────────────────────────────

    /** v4에 존재하던 테이블. `meeting_summary`는 v3→v4에서 이미 생겼다. */
    private val v4Tables = listOf(
        "CREATE TABLE IF NOT EXISTS `user_status` (`roomId` TEXT NOT NULL, `participants` TEXT NOT NULL, " +
            "`preferences` TEXT NOT NULL, `availability` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, " +
            "PRIMARY KEY(`roomId`))",
        // rating·createdAt이 없는 상태가 v4다
        "CREATE TABLE IF NOT EXISTS `feedback` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`roomId` TEXT NOT NULL, `date` TEXT NOT NULL, `feedback` TEXT NOT NULL, `embedding` BLOB)",
        "CREATE TABLE IF NOT EXISTS `recommended_room` (`roomId` TEXT NOT NULL, " +
            "`recommendedAt` INTEGER NOT NULL, PRIMARY KEY(`roomId`))",
        "CREATE TABLE IF NOT EXISTS `meeting_summary` (`roomId` TEXT NOT NULL, `json` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`roomId`))",
    )

    /** v5 = v4 + harness_run(v5 모양) + ahp_judgment + feedback.rating */
    private val v4ToV5 = listOf(
        "CREATE TABLE IF NOT EXISTS `harness_run` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
            "`roomId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `context` TEXT NOT NULL, " +
            "`attempts` INTEGER NOT NULL, `accuracy` REAL NOT NULL, `hallucinationRate` REAL NOT NULL, " +
            "`constraintFitness` REAL NOT NULL, `retryEfficiency` REAL NOT NULL, " +
            "`placeCount` INTEGER NOT NULL, `verifiedCount` INTEGER NOT NULL, " +
            "`notFoundCount` INTEGER NOT NULL, `unverifiedCount` INTEGER NOT NULL, " +
            "`violationCount` INTEGER NOT NULL, `consistencyRatio` REAL NOT NULL, `satisfaction` REAL)",
        "CREATE TABLE IF NOT EXISTS `ahp_judgment` (`context` TEXT NOT NULL, `deltas` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`context`))",
        "ALTER TABLE `feedback` ADD COLUMN `rating` INTEGER NOT NULL DEFAULT 0",
    )

    /** v6 = v5 + harness_run 개수 컬럼 5개 + feedback.createdAt (MIGRATION_5_6 본문과 같다) */
    private val v5ToV6 = listOf("itemCount", "violationItemCount", "rawVerifiedCount", "rawNotFoundCount", "rawUnverifiedCount")
        .map { "ALTER TABLE `harness_run` ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0" } +
        "ALTER TABLE `feedback` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0"

    /** v7 = v6 + calendar_event (MIGRATION_6_7 본문과 같다) */
    private val v6ToV7 = listOf(
        "CREATE TABLE IF NOT EXISTS `calendar_event` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `date` TEXT NOT NULL, " +
            "`time` TEXT NOT NULL, `location` TEXT NOT NULL, `note` TEXT NOT NULL, `roomId` TEXT, " +
            "`placeName` TEXT NOT NULL, `placeAddress` TEXT NOT NULL, `placeUrl` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )

    /** 시드한 후기 — 마이그레이션이 이걸 지우면 R2도 실패한다. */
    private val seedDate = "2026-09-01"
    private val seedFeedback = "조용하고 좋았어요"

    /**
     * 지정한 버전의 DB를 **Room 없이 맨 SQLite로** 만든다.
     * Room 빌더로 만들면 그 시점의 identity가 기록돼 "과거 버전 재현"이 되지 않는다.
     */
    private fun createLegacyDb(version: Int) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        v4Tables.forEach(db::execSQL)
                        if (version >= 5) v4ToV5.forEach(db::execSQL)
                        if (version >= 6) v5ToV6.forEach(db::execSQL)
                        if (version >= 7) v6ToV7.forEach(db::execSQL)

                        // 마이그레이션이 건드리는 테이블마다 최소 1행
                        db.execSQL(
                            "INSERT INTO `feedback` (`roomId`, `date`, `feedback`, `embedding`) " +
                                "VALUES ('room-1', '$seedDate', '$seedFeedback', NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO `user_status` (`roomId`, `participants`, `preferences`, " +
                                "`availability`, `lastUpdated`) VALUES ('room-1', '가', '좋아요: 조용한 곳', '저녁', 1)"
                        )
                        db.execSQL(
                            "INSERT INTO `recommended_room` (`roomId`, `recommendedAt`) VALUES ('room-1', 1)"
                        )
                        if (version >= 5) {
                            db.execSQL(
                                "INSERT INTO `harness_run` (`roomId`, `timestamp`, `context`, `attempts`, " +
                                    "`accuracy`, `hallucinationRate`, `constraintFitness`, `retryEfficiency`, " +
                                    "`placeCount`, `verifiedCount`, `notFoundCount`, `unverifiedCount`, " +
                                    "`violationCount`, `consistencyRatio`, `satisfaction`) " +
                                    "VALUES ('room-1', 100, 'MEAL', 1, 1.0, 0.0, 1.0, 1.0, 3, 3, 0, 0, 0, 0.0177, NULL)"
                            )
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { it.version = version }
        helper.close()
    }

    /**
     * **운영 코드와 같은 구성으로** 연다 — 마이그레이션 목록도, 파괴적 폴백도.
     *
     * `fallbackToDestructiveMigration()`을 빼면 테스트가 운영보다 **엄격해진다.**
     * 그 상태에서는 마이그레이션 누락이 `IllegalStateException`으로 잡히지만,
     * 실제 앱에서는 예외 없이 **DB가 지워진다.** 다른 실패 양상을 검사하는 테스트는
     * 실기기 위험을 대변하지 못하므로 폴백까지 같이 켠다.
     * (그래서 이 파일의 검증은 "예외가 안 났다"가 아니라 **"시드 행이 살아남았다"** 로 한다)
     */
    private fun openLikeProduction(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.productionMigrations())
            .fallbackToDestructiveMigration()
            .build()

    // ── 검증 ────────────────────────────────────────────────────────────────

    @Test
    fun `v4 DB를 열면 마이그레이션이 돌고 기존 후기가 살아남는다`() {
        createLegacyDb(4)

        val db = openLikeProduction()
        // 실제 쿼리를 한 번 해야 Room이 DB를 열고 무결성 검사를 수행한다
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT `roomId`, `feedback`, `rating`, `createdAt` FROM `feedback`"
        )
        cursor.use {
            assertTrue("시드한 후기 행이 사라졌다 — 파괴적 폴백이 탔을 수 있다", it.moveToFirst())
            assertEquals(1, it.count)
            assertEquals("room-1", it.getString(0))
            assertEquals(seedFeedback, it.getString(1))
            assertEquals("새 컬럼 rating의 기본값", 0, it.getInt(2))
            // createdAt은 date에서 백필된다 — 0이 아니어야 한다
            assertTrue("createdAt 백필이 안 됐다: ${it.getLong(3)}", it.getLong(3) > 0L)
        }
        db.close()
    }

    @Test
    fun `v4에서 올라온 DB의 버전이 최신이고 새 테이블을 쓸 수 있다`() {
        createLegacyDb(4)

        val db = openLikeProduction()
        val sdb = db.openHelper.writableDatabase
        assertEquals("최종 버전", 8, sdb.version)

        // 새 테이블에 쓰고 읽기 — v6 신규 컬럼 포함
        sdb.execSQL(
            "INSERT INTO `harness_run` (`roomId`, `timestamp`, `context`, `attempts`, `accuracy`, " +
                "`hallucinationRate`, `constraintFitness`, `retryEfficiency`, `placeCount`, " +
                "`verifiedCount`, `notFoundCount`, `unverifiedCount`, `violationCount`, " +
                "`itemCount`, `violationItemCount`, `rawVerifiedCount`, `rawNotFoundCount`, " +
                "`rawUnverifiedCount`, `consistencyRatio`, `satisfaction`) " +
                "VALUES ('room-1', 200, 'MEAL', 1, 0.0, 0.0, 0.0, 1.0, 3, 2, 1, 0, 0, 3, 1, 2, 1, 0, 0.0177, 0.4)"
        )
        sdb.execSQL(
            "INSERT INTO `ahp_judgment` (`context`, `deltas`, `updatedAt`) " +
                "VALUES ('MEAL', 'VERIFIED_TRUST>PREFERENCE_FIT:1', 300)"
        )
        sdb.query("SELECT `rawNotFoundCount`, `itemCount` FROM `harness_run` WHERE `timestamp` = 200").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(3, it.getInt(1))
        }
        sdb.query("SELECT `deltas` FROM `ahp_judgment` WHERE `context` = 'MEAL'").use {
            assertTrue(it.moveToFirst())
            assertEquals("VERIFIED_TRUST>PREFERENCE_FIT:1", it.getString(0))
        }
        db.close()
    }

    @Test
    fun `v5 DB에서 올라와도 기존 후기와 실행 기록이 유지된다`() {
        createLegacyDb(5)

        val db = openLikeProduction()
        val sdb = db.openHelper.readableDatabase
        assertEquals(8, sdb.version)

        sdb.query("SELECT `feedback`, `createdAt` FROM `feedback`").use {
            assertTrue("v5→v6에서 후기가 사라졌다", it.moveToFirst())
            assertEquals(seedFeedback, it.getString(0))
            assertTrue("createdAt 백필 실패", it.getLong(1) > 0L)
        }
        // v5 시절 실행 기록도 남고, v6 신규 컬럼은 기본값 0이어야 한다
        sdb.query("SELECT `context`, `rawNotFoundCount`, `itemCount` FROM `harness_run`").use {
            assertTrue("v5 실행 기록이 사라졌다", it.moveToFirst())
            assertEquals("MEAL", it.getString(0))
            assertEquals("v6 신규 컬럼 기본값", 0, it.getInt(1))
            assertEquals("v6 신규 컬럼 기본값", 0, it.getInt(2))
        }
        db.close()
    }

    @Test
    fun `v5에서 올라온 DB에 캘린더 일정을 저장할 수 있고 기존 후기는 유지된다`() {
        createLegacyDb(5)

        val db = openLikeProduction()
        val sdb = db.openHelper.writableDatabase
        assertEquals(8, sdb.version)

        // v7 신규 테이블 — 방 일정(roomId 있음)과 직접 추가 일정(roomId NULL) 모두
        sdb.execSQL(
            "INSERT INTO `calendar_event` (`id`, `title`, `date`, `time`, `location`, `note`, `roomId`, " +
                "`placeName`, `placeAddress`, `placeUrl`, `category`, `createdAt`) VALUES " +
                "('e1', '저녁 모임', '2026-10-03', '19:00', '강남', '', 'room-1', '고깃집', '서울', 'http://p', '모임', 1), " +
                "('e2', '과제 마감', '2026-10-05', '', '', '메모', NULL, '', '', '', '', 2)"
        )
        sdb.query("SELECT `id`, `roomId` FROM `calendar_event` ORDER BY `createdAt`").use {
            assertEquals(2, it.count)
            assertTrue(it.moveToFirst()); assertEquals("e1", it.getString(0))
            assertTrue(it.moveToNext()); assertTrue("roomId NULL 허용", it.isNull(1))
        }
        sdb.query("SELECT `feedback` FROM `feedback`").use {
            assertTrue("v6→v7에서 후기가 사라졌다", it.moveToFirst())
            assertEquals(seedFeedback, it.getString(0))
        }
        db.close()
    }

    @Test
    fun `신규 설치 경로에서 모든 테이블이 생성된다`() {
        val db = openLikeProduction()
        val sdb = db.openHelper.writableDatabase
        assertEquals(8, sdb.version)

        val expected = setOf(
            "user_status", "feedback", "recommended_room",
            "meeting_summary", "harness_run", "ahp_judgment",
            "calendar_event",
        )
        val found = mutableSetOf<String>()
        sdb.query("SELECT name FROM sqlite_master WHERE type='table'").use {
            while (it.moveToNext()) found += it.getString(0)
        }
        assertTrue("누락된 테이블: ${expected - found}", found.containsAll(expected))
        db.close()
    }

    @Test
    fun `날짜 형식이 다른 후기는 createdAt이 0으로 남는다`() {
        // 백필 SQL의 LIKE '____-__-__' 조건에 걸리지 않는 행 — 팝업 조건의 폴백 경로 재료다
        createLegacyDb(4)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) = Unit
                }).build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO `feedback` (`roomId`, `date`, `feedback`, `embedding`) " +
                "VALUES ('room-2', '언젠가', '형식이 다른 행', NULL)"
        )
        helper.close()

        val db = openLikeProduction()
        db.openHelper.readableDatabase
            .query("SELECT `createdAt` FROM `feedback` WHERE `roomId` = 'room-2'").use {
                assertTrue(it.moveToFirst())
                assertEquals("형식이 다르면 백필하지 않는다", 0L, it.getLong(0))
            }
        db.openHelper.readableDatabase
            .query("SELECT `createdAt` FROM `feedback` WHERE `roomId` = 'room-1'").use {
                assertTrue(it.moveToFirst())
                assertTrue("정상 형식은 백필된다", it.getLong(0) > 0L)
            }
        db.close()
    }

    @Test
    fun `운영 코드에 v4부터 현재까지의 마이그레이션이 모두 등록돼 있다`() {
        // fallbackToDestructiveMigration() 때문에 경로가 빠져도 크래시가 나지 않는다.
        // 그래서 "등록 여부"를 직접 확인한다 — 누락되면 조용히 데이터가 날아간다.
        val registered = AppDatabase.productionMigrations()
            .map { it.startVersion to it.endVersion }
            .toSet()
        assertNotNull(registered)
        (4 until 8).forEach { v ->
            assertTrue(
                "v$v → v${v + 1} 마이그레이션이 등록돼 있지 않다 — 파괴적 폴백이 탄다",
                registered.contains(v to v + 1)
            )
        }
    }

    // ── v7 → v8: user_status.sourceMemberIds (2026-10, 나간 멤버 반영 결함) ─────────────

    @Test
    fun `v7 DB를 열면 기존 프로필이 보존되고 출처는 빈 목록으로 읽힌다`() {
        createLegacyDb(7)

        val db = openLikeProduction()
        assertEquals(8, db.openHelper.readableDatabase.version)
        // Room DAO·타입 컨버터 경로로 읽는다 — 컬럼 기본값('')이 컨버터 형식과 맞는지까지 확인
        val status = kotlinx.coroutines.runBlocking { db.userStatusDao().getByRoomId("room-1") }
        assertNotNull("v7→v8에서 프로필이 사라졌다 — 파괴적 폴백이 탔을 수 있다", status)
        assertEquals(listOf("좋아요: 조용한 곳"), status!!.preferences)
        assertEquals(listOf("가"), status.participants)
        assertTrue("기존 행의 출처는 미기록(빈 목록)", status.sourceMemberIds.isEmpty())
        db.openHelper.readableDatabase.query("SELECT `feedback` FROM `feedback`").use {
            assertTrue("v7→v8에서 후기가 사라졌다", it.moveToFirst())
            assertEquals(seedFeedback, it.getString(0))
        }
        db.close()
    }

    @Test
    fun `v7에서 올라온 DB에 출처 멤버를 저장하고 다시 읽을 수 있다`() {
        createLegacyDb(7)

        val db = openLikeProduction()
        val dao = db.userStatusDao()
        kotlinx.coroutines.runBlocking {
            dao.upsert(com.navoodi.morimi.data.local.UserStatusEntity(
                roomId = "room-1", preferences = listOf("좋아요: 고기"), sourceMemberIds = listOf("uid-a", "uid-b"),
            ))
            assertEquals(listOf("uid-a", "uid-b"), dao.getByRoomId("room-1")!!.sourceMemberIds)
        }
        db.close()
    }

    @Test
    fun `v4에서 v8까지 한 번에 올라와도 프로필과 후기가 유지된다`() {
        createLegacyDb(4)

        val db = openLikeProduction()
        val status = kotlinx.coroutines.runBlocking { db.userStatusDao().getByRoomId("room-1") }
        assertNotNull(status)
        assertTrue(status!!.sourceMemberIds.isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `feedback`").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.close()
    }
}
