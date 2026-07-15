package com.chamika.dashtune.media

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FOLDER_PREFIX

class MediaItemResolver(
    private val repository: MediaRepository
) {

    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()

        mediaItems.forEach {
            if (it.mediaId.startsWith(SHUFFLE_FOLDER_PREFIX)) {
                playlist.addAll(
                    repository.getShuffledTracks(it.mediaId.removePrefix(SHUFFLE_FOLDER_PREFIX))
                )
                return@forEach
            }

            val item = repository.getItem(it.mediaId)
            val isAudiobook = item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
            val isExpandable = (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                    item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST) &&
                    (!isAudiobook || item.mediaMetadata.isBrowsable == true)

            if (isExpandable) {
                val children = resolveMediaItems(repository.getChildren(item.mediaId))
                if (children.isNotEmpty()) {
                    children.forEach(playlist::add)
                } else if (item.localConfiguration?.uri != null) {
                    // Single-file audiobook with no children — play directly
                    Log.i(LOG_TAG, "Playing single-file audiobook directly: ${item.mediaMetadata.title}")
                    playlist.add(item)
                } else {
                    Log.w(LOG_TAG, "Empty children and no URI for ${item.mediaMetadata.title} (type=${item.mediaMetadata.mediaType}, playable=${item.mediaMetadata.isPlayable})")
                }
            } else if (item.mediaMetadata.isPlayable == true) {
                playlist.add(item)
            } else {
                Log.e(LOG_TAG, "Cannot add media ${item.mediaMetadata.title}")
            }
        }

        return playlist
    }

    suspend fun isSingleItemWithParent(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val mediaId = mediaItems[0].mediaId
        // A shuffle pseudo-item may be cached as a normal folder child in Room, which
        // would make the DB-parent fallback below misidentify it as "one track from a
        // folder" and play only that folder's immediate children non-recursively.
        if (mediaId.startsWith(SHUFFLE_FOLDER_PREFIX)) return false
        val item = repository.getItem(mediaId)
        // An album/playlist is itself an expandable container: tapping it should play its
        // own tracks, not expand the whole parent folder. In folder browse these have a
        // (non-static) folder as parent, so without this they'd be treated as "one item
        // within a parent" — resolveMediaItems would expand every sibling album and the
        // selected album's id would vanish from the queue, starting playback on the wrong
        // track. Audiobooks keep the parent-expand path so chapter resume still works.
        val isAudiobook = item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
        val mediaType = item.mediaMetadata.mediaType
        if (!isAudiobook && (mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST)
        ) {
            return false
        }
        if (item.mediaMetadata.extras?.containsKey(PARENT_KEY) == true) return true
        // Fall back to DB parent relationship (handles stale cache)
        return repository.getContentParentId(mediaId) != null
    }

    suspend fun expandSingleItem(item: MediaItem): List<MediaItem> {
        val parentId = repository.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)
            ?: repository.getContentParentId(item.mediaId)
            ?: return listOf(item)
        val children = repository.getChildren(parentId)
            // Folder-browse children carry an injected "Shuffle all" pseudo-item at the
            // front. It's a browse-only affordance, not a real sibling — resolving it would
            // splice a whole random shuffle of the folder's descendants into the queue and
            // start playback on the wrong track. Drop it so tapping a song plays that song
            // and its actual siblings in order.
            .filterNot { it.mediaId.startsWith(SHUFFLE_FOLDER_PREFIX) }
        return resolveMediaItems(children)
    }
}
