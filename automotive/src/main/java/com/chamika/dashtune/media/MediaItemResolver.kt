package com.chamika.dashtune.media

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY

class MediaItemResolver(
    private val repository: MediaRepository
) {

    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()

        mediaItems.forEach {
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
        val item = repository.getItem(mediaId)
        if (item.mediaMetadata.extras?.containsKey(PARENT_KEY) == true) return true
        // Fall back to DB parent relationship (handles stale cache)
        return repository.getContentParentId(mediaId) != null
    }

    suspend fun expandSingleItem(item: MediaItem): List<MediaItem> {
        val parentId = repository.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)
            ?: repository.getContentParentId(item.mediaId)
            ?: return listOf(item)
        val children = repository.getChildren(parentId)
        return resolveMediaItems(children)
    }
}
