package com.chamika.dashtune.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * exportSchema is off, so this exercises the migration body directly against a hand-built v1
 * database rather than via Room's MigrationTestHelper: it proves MIGRATION_1_2 creates
 * pinned_downloads without disturbing the existing cached_media_items rows.
 */
@RunWith(RobolectricTestRunner::class)
class DashTuneMigrationTest {

    private lateinit var db: SupportSQLiteDatabase

    // The cached_media_items schema as it existed at database version 1.
    private val createV1CacheTable =
        "CREATE TABLE IF NOT EXISTS `cached_media_items` (" +
            "`mediaId` TEXT NOT NULL, `parentId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`subtitle` TEXT, `artUri` TEXT, `mediaType` INTEGER NOT NULL, " +
            "`isPlayable` INTEGER NOT NULL, `isBrowsable` INTEGER NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, `durationMs` INTEGER, `isFavorite` INTEGER NOT NULL, " +
            "`extras` TEXT, PRIMARY KEY(`mediaId`, `parentId`))"

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createV1CacheTable)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migration 1 to 2 preserves cached_media_items and adds pinned_downloads`() {
        db.execSQL(
            "INSERT INTO cached_media_items " +
                "(mediaId, parentId, title, subtitle, artUri, mediaType, isPlayable, " +
                "isBrowsable, sortOrder, durationMs, isFavorite, extras) " +
                "VALUES ('t1', 'album-1', 'Track', NULL, NULL, 1, 1, 0, 0, NULL, 0, NULL)"
        )

        DatabaseModule.MIGRATION_1_2.migrate(db)

        // Existing cache row survives the migration.
        db.query("SELECT title FROM cached_media_items WHERE mediaId = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Track", cursor.getString(0))
        }

        // The new pinned_downloads table exists and is writable.
        db.execSQL(
            "INSERT INTO pinned_downloads " +
                "(containerId, title, subtitle, artUri, mediaType, trackIds, totalTracks, createdAt) " +
                "VALUES ('album-1', 'Album', NULL, NULL, 3, '[\"t1\"]', 1, 0)"
        )
        db.query("SELECT COUNT(*) FROM pinned_downloads").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
