package com.chamika.dashtune.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CachedMediaItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DashTuneDatabase : RoomDatabase() {
    abstract fun mediaCacheDao(): MediaCacheDao
}
