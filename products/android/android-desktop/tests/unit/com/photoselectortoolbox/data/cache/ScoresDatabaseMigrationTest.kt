package com.photoselectortoolbox.data.cache

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScoresDatabaseMigrationTest {

    @Test
    fun `migration 2 to 3 adds rating and compositionAnalysis columns with defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_2_3.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Insert row into v2 database
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, aestheticScore, lastAccessTime)
            VALUES ('content://photo/1', 1024, 2000, 42.0, 1.5, 7.8, 1000)
            """.trimIndent()
        )

        // Execute MIGRATION_2_3
        ScoresDatabase.MIGRATION_2_3.migrate(db)

        // Query the row after migration
        val cursor = db.query("SELECT filePath, sharpnessScore, aestheticScore, rating, compositionAnalysis FROM scores_cache WHERE filePath = 'content://photo/1'")
        assertTrue(cursor.moveToFirst())

        assertEquals("content://photo/1", cursor.getString(0))
        assertEquals(42.0, cursor.getDouble(1), 1e-6)
        assertEquals(7.8, cursor.getDouble(2), 1e-6)
        // rating must default to 0
        assertEquals(0, cursor.getInt(3))
        // compositionAnalysis must be null
        assertTrue(cursor.isNull(4))
        cursor.close()

        // Test updating new columns
        db.execSQL("UPDATE scores_cache SET rating = 4, compositionAnalysis = '{\"lines\":[]}' WHERE filePath = 'content://photo/1'")
        val updatedCursor = db.query("SELECT rating, compositionAnalysis FROM scores_cache WHERE filePath = 'content://photo/1'")
        assertTrue(updatedCursor.moveToFirst())
        assertEquals(4, updatedCursor.getInt(0))
        assertEquals("{\"lines\":[]}", updatedCursor.getString(1))
        updatedCursor.close()

        db.close()
    }

    @Test
    fun `migration 2 to 3 handles empty database with zero rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_empty.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Execute migration on empty database
        ScoresDatabase.MIGRATION_2_3.migrate(db)

        val cursor = db.query("SELECT count(*) FROM scores_cache")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()

        // Insert row after migration
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, rating, lastAccessTime)
            VALUES ('content://photo/empty_test', 500, 1000, 3, 2000)
            """.trimIndent()
        )
        val checkCursor = db.query("SELECT filePath, rating, compositionAnalysis FROM scores_cache")
        assertTrue(checkCursor.moveToFirst())
        assertEquals("content://photo/empty_test", checkCursor.getString(0))
        assertEquals(3, checkCursor.getInt(1))
        assertTrue(checkCursor.isNull(2))
        checkCursor.close()

        db.close()
    }

    @Test
    fun `migration 2 to 3 preserves null scores, existing aestheticScore values, and mixed rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_nulls_and_existing.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Row 1: All scores null (e.g. freshly indexed or uncomputed)
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, highlightClipping, shadowClipping, aestheticScore, lastAccessTime)
            VALUES ('photo_all_null', 100, 200, NULL, NULL, NULL, NULL, NULL, 500)
            """.trimIndent()
        )

        // Row 2: Only aestheticScore set, other scores null
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, highlightClipping, shadowClipping, aestheticScore, lastAccessTime)
            VALUES ('photo_aesthetic_only', 100, 200, NULL, NULL, NULL, NULL, 9.4, 600)
            """.trimIndent()
        )

        // Row 3: All technical scores set, aestheticScore null (legacy v1 row migrated to v2)
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, highlightClipping, shadowClipping, aestheticScore, lastAccessTime)
            VALUES ('photo_tech_only', 100, 200, 55.2, 0.8, 0.05, 0.02, NULL, 700)
            """.trimIndent()
        )

        // Row 4: Full data (both technical and aesthetic scores present)
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, highlightClipping, shadowClipping, aestheticScore, lastAccessTime)
            VALUES ('photo_full', 100, 200, 88.0, 0.1, 0.01, 0.0, 8.9, 800)
            """.trimIndent()
        )

        // Execute MIGRATION_2_3
        ScoresDatabase.MIGRATION_2_3.migrate(db)

        // Verify Row 1: All scores still null, rating = 0, compositionAnalysis is null
        val c1 = db.query("SELECT sharpnessScore, noiseLevel, highlightClipping, shadowClipping, aestheticScore, rating, compositionAnalysis FROM scores_cache WHERE filePath = 'photo_all_null'")
        assertTrue(c1.moveToFirst())
        assertTrue(c1.isNull(0))
        assertTrue(c1.isNull(1))
        assertTrue(c1.isNull(2))
        assertTrue(c1.isNull(3))
        assertTrue(c1.isNull(4))
        assertEquals(0, c1.getInt(5))
        assertTrue(c1.isNull(6))
        c1.close()

        // Verify Row 2: aestheticScore preserved at 9.4, others null, rating = 0
        val c2 = db.query("SELECT sharpnessScore, aestheticScore, rating, compositionAnalysis FROM scores_cache WHERE filePath = 'photo_aesthetic_only'")
        assertTrue(c2.moveToFirst())
        assertTrue(c2.isNull(0))
        assertEquals(9.4, c2.getDouble(1), 1e-6)
        assertEquals(0, c2.getInt(2))
        assertTrue(c2.isNull(3))
        c2.close()

        // Verify Row 3: technical scores preserved, aestheticScore still null, rating = 0
        val c3 = db.query("SELECT sharpnessScore, noiseLevel, aestheticScore, rating FROM scores_cache WHERE filePath = 'photo_tech_only'")
        assertTrue(c3.moveToFirst())
        assertEquals(55.2, c3.getDouble(0), 1e-6)
        assertEquals(0.8, c3.getDouble(1), 1e-6)
        assertTrue(c3.isNull(2))
        assertEquals(0, c3.getInt(3))
        c3.close()

        // Verify Row 4: Full scores preserved intact
        val c4 = db.query("SELECT sharpnessScore, aestheticScore, rating FROM scores_cache WHERE filePath = 'photo_full'")
        assertTrue(c4.moveToFirst())
        assertEquals(88.0, c4.getDouble(0), 1e-6)
        assertEquals(8.9, c4.getDouble(1), 1e-6)
        assertEquals(0, c4.getInt(2))
        c4.close()

        db.close()
    }

    @Test
    fun `migration 2 to 3 opens cleanly with Room and verifies DAO rating queries`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_room_dao.db"
        context.deleteDatabase(dbName)

        // 1. Create a schema v2 database using SupportSQLiteOpenHelper with Room master table
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f43c7924b06441a304ed9920fa73c02a')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val rawDb = helper.writableDatabase

        // Seed 4 initial rows into v2 table
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, aestheticScore, lastAccessTime) VALUES ('photo_r1', 100, 200, 7.5, 1000)")
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, aestheticScore, lastAccessTime) VALUES ('photo_r2', 100, 200, 8.0, 1001)")
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, aestheticScore, lastAccessTime) VALUES ('photo_r3', 100, 200, NULL, 1002)")
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, aestheticScore, lastAccessTime) VALUES ('photo_r4', 100, 200, 9.1, 1003)")
        rawDb.close()

        // 2. Open via Room with MIGRATIONS — this validates the full Room schema integrity!
        val roomDb = Room.databaseBuilder(context, ScoresDatabase::class.java, dbName)
            .addMigrations(*ScoresDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val dao = roomDb.scoreDao()

        // Verify all 4 rows exist and have rating = 0 (unrated default)
        assertEquals(4, dao.getCount())
        val r1 = dao.getScore("photo_r1")
        assertNotNull(r1)
        assertEquals(7.5, r1!!.aestheticScore!!, 1e-6)
        assertEquals(0, r1.rating)
        assertNull(r1.compositionAnalysis)

        val r3 = dao.getScore("photo_r3")
        assertNotNull(r3)
        assertNull(r3!!.aestheticScore)
        assertEquals(0, r3.rating)

        // 3. Test updateRating: set distinct ratings (-1 = reject, 0 = unrated, 3 = 3-star, 5 = 5-star)
        dao.updateRating("photo_r1", -1) // reject
        dao.updateRating("photo_r2", 3)  // 3 stars
        dao.updateRating("photo_r3", 5)  // 5 stars
        // photo_r4 stays at 0 (unrated)

        assertEquals(-1, dao.getScore("photo_r1")!!.rating)
        assertEquals(3, dao.getScore("photo_r2")!!.rating)
        assertEquals(5, dao.getScore("photo_r3")!!.rating)
        assertEquals(0, dao.getScore("photo_r4")!!.rating)

        // 4. Test updateRating on non-existent row does not crash
        dao.updateRating("non_existent_photo", 4)
        assertNull(dao.getScore("non_existent_photo"))

        // 5. Test updateCompositionAnalysis
        val compJson = """{"leadingLines":[],"subjectPlacement":"rule_of_thirds"}"""
        dao.updateCompositionAnalysis("photo_r3", compJson)
        assertEquals(compJson, dao.getScore("photo_r3")!!.compositionAnalysis)

        roomDb.close()
    }

    @Test
    fun `migration 1 to 3 executes both migrations sequentially and validates Room integrity`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_1_to_3.db"
        context.deleteDatabase(dbName)

        // Create schema v1 database
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'cd6091cfecc83b34aff9776fc1c647b8')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val rawDb = helper.writableDatabase

        // Seed legacy v1 row (has no aestheticScore, no rating, no compositionAnalysis)
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, lastAccessTime) VALUES ('v1_legacy_photo', 1024, 2048, 65.0, 1.2, 5000)")
        rawDb.close()

        // Open directly with v3 Room DB using arrayOf(MIGRATION_1_2, MIGRATION_2_3)
        val roomDb = Room.databaseBuilder(context, ScoresDatabase::class.java, dbName)
            .addMigrations(*ScoresDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val dao = roomDb.scoreDao()

        // Legacy row should have aestheticScore = null, rating = 0, compositionAnalysis = null
        val legacy = dao.getScore("v1_legacy_photo")
        assertNotNull(legacy)
        assertEquals(65.0, legacy!!.sharpnessScore!!, 1e-6)
        assertNull(legacy.aestheticScore)
        assertEquals(0, legacy.rating)
        assertNull(legacy.compositionAnalysis)

        // Rating update on legacy row
        dao.updateRating("v1_legacy_photo", 4)
        val updatedLegacy = dao.getScore("v1_legacy_photo")
        assertEquals(4, updatedLegacy!!.rating)

        // Insert new v3 entity
        dao.insertOrUpdate(
            ScoreEntity(
                filePath = "v3_new_photo",
                fileSize = 2048,
                lastModified = 4096,
                sharpnessScore = 70.0,
                aestheticScore = 9.2,
                rating = 5,
                compositionAnalysis = "{\"score\":0.9}"
            )
        )

        assertEquals(2, dao.getCount())

        // Pruning works seamlessly on migrated DB
        dao.pruneToMru(1)
        assertEquals(1, dao.getCount())

        roomDb.close()
    }

    @Test
    fun `migration 1 to 2 adds nullable aestheticScore column and preserves existing technical scores`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_1_2.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, noiseLevel, highlightClipping, shadowClipping, lastAccessTime)
            VALUES ('content://photo/v1_only', 4096, 8192, 91.5, 0.4, 0.01, 0.02, 12345)
            """.trimIndent()
        )

        ScoresDatabase.MIGRATION_1_2.migrate(db)

        val cursor = db.query("SELECT filePath, sharpnessScore, noiseLevel, aestheticScore FROM scores_cache WHERE filePath = 'content://photo/v1_only'")
        assertTrue(cursor.moveToFirst())
        assertEquals("content://photo/v1_only", cursor.getString(0))
        assertEquals(91.5, cursor.getDouble(1), 1e-6)
        assertEquals(0.4, cursor.getDouble(2), 1e-6)
        assertTrue(cursor.isNull(3))
        cursor.close()
        db.close()
    }

    @Test
    fun `migration 3 to 4 creates user_ratings table and copies only non-zero ratings from scores_cache`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_3_4.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `rating` INTEGER NOT NULL,
                            `compositionAnalysis` TEXT,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // Seed v3 rows: 5-star, reject (-1), and unrated (0)
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime)
            VALUES ('content://photo/five_star', 1000, 2000, 80.0, 5, 9001)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime)
            VALUES ('content://photo/rejected', 1000, 2000, 30.0, -1, 9002)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime)
            VALUES ('content://photo/unrated', 1000, 2000, 60.0, 0, 9003)
            """.trimIndent()
        )

        ScoresDatabase.MIGRATION_3_4.migrate(db)

        val cursor = db.query("SELECT uri, rating, updatedAt FROM user_ratings ORDER BY uri ASC")
        assertEquals(2, cursor.count)

        assertTrue(cursor.moveToFirst())
        assertEquals("content://photo/five_star", cursor.getString(0))
        assertEquals(5, cursor.getInt(1))
        assertEquals(9001L, cursor.getLong(2))

        assertTrue(cursor.moveToNext())
        assertEquals("content://photo/rejected", cursor.getString(0))
        assertEquals(-1, cursor.getInt(1))
        assertEquals(9002L, cursor.getLong(2))
        cursor.close()

        db.close()
    }

    @Test
    fun `migration 3 to 4 opens cleanly with Room v4 and preserves ratings across deleteAll and pruneToMru`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_3_4_room.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `scores_cache` (
                            `filePath` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `lastModified` INTEGER NOT NULL,
                            `sharpnessScore` REAL,
                            `noiseLevel` REAL,
                            `highlightClipping` REAL,
                            `shadowClipping` REAL,
                            `aestheticScore` REAL,
                            `rating` INTEGER NOT NULL,
                            `compositionAnalysis` TEXT,
                            `lastAccessTime` INTEGER NOT NULL,
                            PRIMARY KEY(`filePath`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '95846fa727e71f40900f75e079e55cdc')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val rawDb = helper.writableDatabase
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime) VALUES ('photo_a', 100, 200, 75.0, 4, 1000)")
        rawDb.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime) VALUES ('photo_b', 100, 200, 85.0, 0, 2000)")
        rawDb.close()

        val roomDb = Room.databaseBuilder(context, ScoresDatabase::class.java, dbName)
            .addMigrations(*ScoresDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val dao = roomDb.scoreDao()

        // Migrated rating is in user_ratings
        val migratedRating = dao.getUserRating("photo_a")
        assertNotNull(migratedRating)
        assertEquals(4, migratedRating!!.rating)
        assertEquals(1000L, migratedRating.updatedAt)
        assertNull(dao.getUserRating("photo_b"))

        // Unscanned photo rating can be upserted directly into user_ratings
        dao.upsertUserRating(UserRatingEntity(uri = "unscanned_photo", rating = 5, updatedAt = 3000L))
        assertEquals(5, dao.getUserRating("unscanned_photo")?.rating)

        // MRU pruning evicts photo_a from scores_cache (oldest access time), but user_ratings keeps it
        dao.pruneToMru(1)
        assertNull(dao.getScore("photo_a"))
        assertEquals(4, dao.getUserRating("photo_a")?.rating)

        // Clearing all scan cache wipes scores_cache, but user_ratings survives untouched
        dao.deleteAll()
        assertEquals(0, dao.getCount())
        val survivingRatings = dao.getUserRatings(listOf("photo_a", "unscanned_photo")).associateBy { it.uri }
        assertEquals(4, survivingRatings["photo_a"]?.rating)
        assertEquals(5, survivingRatings["unscanned_photo"]?.rating)

        // Verify v5 technique_facts, v6 lesson_progress, and v7 practice_goals tables & DAO operations
        dao.upsertTechniqueFacts(
            listOf(
                TechniqueFactEntity(
                    uri = "photo_a",
                    kind = "light.hour",
                    value = "golden_hour",
                    confidence = 0.9,
                    geometryJson = null,
                    fileSize = 100L,
                    lastModified = 200L,
                    detectorVersion = 1,
                )
            )
        )
        val facts = dao.getTechniqueFactsForUri("photo_a")
        assertEquals(1, facts.size)
        assertEquals("golden_hour", facts[0].value)

        dao.upsertLessonProgress(
            LessonProgressEntity(
                lessonId = "light.golden_hour",
                completedAt = 5000L,
                spotItCorrect = 2,
                spotItTotal = 2,
            )
        )
        assertEquals(2, dao.getLessonProgress("light.golden_hour")?.spotItCorrect)

        dao.upsertPracticeGoal(
            PracticeGoalEntity(
                lessonId = "composition.level_horizon",
                startedAtEpochMs = 6000L,
                baselineRate = 0.25,
                baselineSampleSize = 20,
            )
        )
        assertEquals(0.25, dao.getPracticeGoal("composition.level_horizon")!!.baselineRate, 1e-6)

        roomDb.close()
    }

    // REQ-AND-CACHE.03, REQ-AND-CACHE.06
    @Test
    fun chunkedQueries_handle2500UrisWithoutExceedingSqliteBindLimit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.scoreDao()
        try {
            val uris = (1..2500).map { "content://test/photo_$it.jpg" }
            uris.forEachIndexed { idx, uri ->
                dao.insertOrUpdate(
                    ScoreEntity(
                        filePath = uri,
                        fileSize = 100L + idx,
                        lastModified = 1000L,
                        sharpnessScore = 50.0,
                        lastAccessTime = 100L,
                    )
                )
                dao.upsertUserRating(UserRatingEntity(uri = uri, rating = (idx % 5) + 1, updatedAt = 500L))
            }

            val scores = dao.getScoresChunked(uris)
            assertEquals(2500, scores.size)

            dao.updateAccessTimesChunked(uris, time = 9999L)
            assertEquals(9999L, dao.getScore(uris.first())?.lastAccessTime)
            assertEquals(9999L, dao.getScore(uris.last())?.lastAccessTime)

            val ratings = dao.getUserRatingsChunked(uris)
            assertEquals(2500, ratings.size)
        } finally {
            db.close()
        }
    }
}

