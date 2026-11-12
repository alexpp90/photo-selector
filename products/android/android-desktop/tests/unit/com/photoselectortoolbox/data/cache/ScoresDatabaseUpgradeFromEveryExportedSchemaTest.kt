package com.photoselectortoolbox.data.cache

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every exported schema (`schemas/…/N.json`, the databases real installs have) is created
 * exactly as Room created it, seeded, and opened by the current [ScoresDatabase] with its
 * migrations. There is no `5.json` or `6.json` because no build ever had those versions
 * (checked 2026-11-12): `git log -G "version = [0-9]+," -- '*ScoresDatabase.kt'` shows the version
 * going 4 → 7 in one commit, `16d66d43` (2026-10-17), which added `MIGRATION_4_5`, `MIGRATION_5_6`
 * and `MIGRATION_6_7` together, and every tag (`android-desktop-v0.6.0`, `-v0.7.0`, the nightlies)
 * has version 7 or 8. A version-4 install takes the three steps in one open, which the v4 case
 * here covers. The user's ratings survive every path; cached scores are invalidated at v8 so the
 * next scan recomputes them at the capped analysis size (FD-P-11, FD-Q-13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScoresDatabaseUpgradeFromEveryExportedSchemaTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val schemaDir = File("schemas/com.photoselectortoolbox.data.cache.ScoresDatabase")

    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles()!!.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()

    /** A database at [version] built from its exported schema, with Room's own setup queries. */
    private fun createAt(version: Int, name: String, seed: (SupportSQLiteDatabase) -> Unit) {
        val db = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(sql: SupportSQLiteDatabase) {
                        val entities = db.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = db.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
                    }

                    override fun onUpgrade(sql: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        helper.writableDatabase.also(seed).close()
    }

    @Test
    fun `the exported schemas include the current version`() {
        assertTrue(exportedVersions().toString(), 8 in exportedVersions())
    }

    @Test
    fun `every exported schema opens at the current version with ratings kept and scores invalidated`() = runTest {
        for (version in exportedVersions()) {
            val name = "upgrade_from_$version.db"
            createAt(version, name) { db ->
                if (version >= 3) {
                    db.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, rating, lastAccessTime) VALUES ('legacy', 100, 200, 70.0, 3, 900)")
                } else {
                    db.execSQL("INSERT INTO scores_cache (filePath, fileSize, lastModified, sharpnessScore, lastAccessTime) VALUES ('legacy', 100, 200, 70.0, 900)")
                }
                if (version >= 4) {
                    db.execSQL("INSERT INTO user_ratings (uri, rating, updatedAt) VALUES ('rated', -1, 800)")
                }
            }

            val room = Room.databaseBuilder(context, ScoresDatabase::class.java, name)
                .addMigrations(*ScoresDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            val dao = room.scoreDao()

            val legacy = dao.getScore("legacy")
            assertNotNull("v$version: the cache row stays", legacy)
            if (version < 8) {
                assertEquals("v$version: a score from another analysis size is not a cache hit", -1L, legacy!!.fileSize)
            }
            assertEquals("v$version", 70.0, legacy!!.sharpnessScore!!, 1e-9)
            if (version in 3..7) assertEquals("v$version: the rating moved to user_ratings", 3, dao.getUserRating("legacy")?.rating)
            if (version >= 4) assertEquals("v$version: a reject survives", -1, dao.getUserRating("rated")?.rating)
            room.close()
        }
    }
}
