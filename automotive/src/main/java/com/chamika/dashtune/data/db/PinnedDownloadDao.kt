package com.chamika.dashtune.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PinnedDownloadDao {

    @Query("SELECT * FROM pinned_downloads ORDER BY createdAt ASC")
    suspend fun getAll(): List<PinnedDownloadEntity>

    @Query("SELECT * FROM pinned_downloads WHERE containerId = :containerId LIMIT 1")
    suspend fun getById(containerId: String): PinnedDownloadEntity?

    @Query("SELECT containerId FROM pinned_downloads")
    suspend fun getAllContainerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PinnedDownloadEntity)

    @Query("DELETE FROM pinned_downloads WHERE containerId = :containerId")
    suspend fun deleteById(containerId: String)

    @Query("DELETE FROM pinned_downloads")
    suspend fun deleteAll()
}
