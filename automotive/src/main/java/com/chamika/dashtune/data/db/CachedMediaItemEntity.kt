package com.chamika.dashtune.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "cached_media_items",
    primaryKeys = ["mediaId", "parentId"]
)
data class CachedMediaItemEntity(
    val mediaId: String,
    @ColumnInfo(index = true)
    val parentId: String,
    val title: String,
    val subtitle: String?,
    val artUri: String?,
    val mediaType: Int,
    val isPlayable: Boolean,
    val isBrowsable: Boolean,
    val sortOrder: Int,
    val durationMs: Long?,
    val isFavorite: Boolean,
    val extras: String?
)
