package com.chamika.dashtune.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * An abstract class rather than an interface so the multi-statement writes below can be
 * [Transaction]s: a reader must never see a parent between its delete and its insert.
 */
@Dao
abstract class MediaCacheDao {

    @Query("SELECT * FROM cached_media_items WHERE parentId = :parentId ORDER BY sortOrder ASC")
    abstract suspend fun getChildrenByParent(parentId: String): List<CachedMediaItemEntity>

    @Query("SELECT * FROM cached_media_items WHERE mediaId = :mediaId LIMIT 1")
    abstract suspend fun getItem(mediaId: String): CachedMediaItemEntity?

    @Query("SELECT parentId FROM cached_media_items WHERE mediaId = :mediaId")
    abstract suspend fun getParentIds(mediaId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<CachedMediaItemEntity>)

    @Query("DELETE FROM cached_media_items WHERE parentId = :parentId")
    abstract suspend fun deleteByParent(parentId: String)

    @Query("DELETE FROM cached_media_items")
    abstract suspend fun deleteAll()

    /** Deletes every row except those under [keepParents]. Keep the list short (SQLite caps bound variables). */
    @Query("DELETE FROM cached_media_items WHERE parentId NOT IN (:keepParents)")
    abstract suspend fun deleteAllExcept(keepParents: List<String>)

    @Query("SELECT COUNT(*) > 0 FROM cached_media_items")
    abstract suspend fun hasData(): Boolean

    /**
     * Swaps the cached children of each parent in [children] for the new list and leaves every
     * other parent alone. Rows of a parent that drops out of its list must go too, which a
     * plain REPLACE insert would leave behind with a stale sortOrder.
     */
    @Transaction
    open suspend fun replaceChildren(children: Map<String, List<CachedMediaItemEntity>>) {
        // One delete per parent instead of an IN list: a full sync touches more parents
        // than the 999 bound variables SQLite allows on API 28.
        children.keys.forEach { deleteByParent(it) }
        insertAll(children.values.flatten())
    }

    /** Replaces the whole cache with [items], except the rows under [keepParents]. */
    @Transaction
    open suspend fun replaceAllExcept(items: List<CachedMediaItemEntity>, keepParents: List<String>) {
        deleteAllExcept(keepParents)
        insertAll(items)
    }
}
