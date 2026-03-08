package com.chamika.dashtune.di

import android.content.Context
import androidx.room.Room
import com.chamika.dashtune.data.db.DashTuneDatabase
import com.chamika.dashtune.data.db.MediaCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashTuneDatabase {
        return Room.databaseBuilder(
            context,
            DashTuneDatabase::class.java,
            "dashtune_db"
        ).build()
    }

    @Provides
    fun provideMediaCacheDao(database: DashTuneDatabase): MediaCacheDao {
        return database.mediaCacheDao()
    }
}
