package com.chamika.dashtune.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chamika.dashtune.data.db.DashTuneDatabase
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.data.db.PinnedDownloadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

    companion object {
        /**
         * Adds the pinned_downloads table without touching cached_media_items, so upgrading users
         * keep their offline library cache. A destructive fallback would drop that cache; it's
         * rebuildable via sync, but there's no reason to churn it just to add a new table.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pinned_downloads` (" +
                        "`containerId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`subtitle` TEXT, " +
                        "`artUri` TEXT, " +
                        "`mediaType` INTEGER NOT NULL, " +
                        "`trackIds` TEXT NOT NULL, " +
                        "`totalTracks` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`containerId`))"
                )
            }
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashTuneDatabase {
        return Room.databaseBuilder(
            context,
            DashTuneDatabase::class.java,
            "dashtune_db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideMediaCacheDao(database: DashTuneDatabase): MediaCacheDao {
        return database.mediaCacheDao()
    }

    @Provides
    fun providePinnedDownloadDao(database: DashTuneDatabase): PinnedDownloadDao {
        return database.pinnedDownloadDao()
    }
}
