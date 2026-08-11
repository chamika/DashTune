package com.chamika.dashtune.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A container (album, playlist or audiobook) the user has deliberately pinned for offline
 * playback. Kept in a table separate from [CachedMediaItemEntity] on purpose: the media cache
 * is wiped on every library sync / clear-cache / logout, whereas pinned state must survive those
 * so the downloaded audio (which lives, un-evicted, in the pinned ExoPlayer cache) stays visible
 * and removable under the Downloads browse node.
 *
 * The individual track download progress/completion is owned by the pinned [DownloadManager]'s
 * download index; this row only stores what's needed to render the container and to remove it.
 */
@Entity(tableName = "pinned_downloads")
data class PinnedDownloadEntity(
    @PrimaryKey
    val containerId: String,
    val title: String,
    val subtitle: String?,
    val artUri: String?,
    val mediaType: Int,
    /** JSON array of the track media IDs enqueued for this container. */
    val trackIds: String,
    val totalTracks: Int,
    val createdAt: Long
)
