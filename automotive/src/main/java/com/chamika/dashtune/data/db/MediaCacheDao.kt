package com.chamika.dashtune.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaCacheDao {

    @Query("SELECT * FROM cached_media_items WHERE parentId = :parentId ORDER BY sortOrder ASC")
    suspend fun getChildrenByParent(parentId: String): List<CachedMediaItemEntity>

    @Query("SELECT * FROM cached_media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getItem(mediaId: String): CachedMediaItemEntity?

    @Query("SELECT parentId FROM cached_media_items WHERE mediaId = :mediaId")
    suspend fun getParentIds(mediaId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedMediaItemEntity>)

    @Query("DELETE FROM cached_media_items WHERE parentId = :parentId")
    suspend fun deleteByParent(parentId: String)

    @Query("DELETE FROM cached_media_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) > 0 FROM cached_media_items")
    suspend fun hasData(): Boolean
}
