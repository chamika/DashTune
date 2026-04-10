package com.chamika.dashtune.data

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ALBUM
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ARTIST
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_PLAYLIST
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.FirebaseUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MediaRepository(
    private val dao: MediaCacheDao,
    private val tree: JellyfinMediaTree,
    private val itemFactory: MediaItemFactory
) {

    private val syncMutex = Mutex()

    private val staticIds = setOf(ROOT_ID, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS, BOOKS)

    suspend fun getItem(id: String): MediaItem {
        if (id in staticIds) {
            return tree.getItem(id)
        }
        val cached = dao.getItem(id)
        if (cached != null) {
            return cached.toMediaItem()
        }
        return tree.getItem(id)
    }

    suspend fun getContentParentId(mediaId: String): String? {
        return dao.getParentIds(mediaId).firstOrNull { it !in staticIds }
    }

    suspend fun getChildren(parentId: String): List<MediaItem> {
        if (parentId == ROOT_ID) {
            return tree.getChildren(ROOT_ID)
        }

        val cached = dao.getChildrenByParent(parentId)
        if (cached.isNotEmpty()) {
            return cached.map { it.toMediaItem() }
        }

        return try {
            val children = tree.getChildren(parentId)
            if (children.isNotEmpty()) {
                val entities = children.mapIndexed { index, item ->
                    item.toEntity(parentId, index)
                }
                dao.insertAll(entities)
            }
            children
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to fetch children for $parentId from network", e)
            emptyList()
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        return tree.search(query)
    }

    suspend fun sync(): Boolean = syncMutex.withLock {
        val sectionIds = tree.getActiveCategoryIds()
        val allEntities = mutableListOf<CachedMediaItemEntity>()
        var anySuccess = false

        FirebaseUtils.safeLog("Sync started: ${sectionIds.size} sections")

        for (sectionId in sectionIds) {
            try {
                val children = tree.getChildren(sectionId)
                children.forEachIndexed { index, item ->
                    allEntities.add(item.toEntity(sectionId, index))
                    syncChildrenRecursively(item, allEntities)
                }
                anySuccess = true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to sync section $sectionId", e)
                FirebaseUtils.safeSetCustomKey("sync_failed_section", sectionId)
                FirebaseUtils.safeRecordException(e)
            }
        }

        if (anySuccess) {
            dao.deleteAll()
            dao.insertAll(allEntities)
        }

        FirebaseUtils.safeLog("Sync completed: ${allEntities.size} items, success=$anySuccess")
        return anySuccess
    }

    private suspend fun syncChildrenRecursively(
        item: MediaItem,
        allEntities: MutableList<CachedMediaItemEntity>
    ) {
        val mediaType = item.mediaMetadata.mediaType
        val mediaId = item.mediaId

        val shouldFetchChildren = item.mediaMetadata.isBrowsable == true && (
                mediaType == MEDIA_TYPE_ARTIST ||
                mediaType == MEDIA_TYPE_ALBUM ||
                mediaType == MEDIA_TYPE_PLAYLIST ||
                mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        )

        if (!shouldFetchChildren) return

        try {
            val children = tree.getChildren(mediaId)
            children.forEachIndexed { index, child ->
                allEntities.add(child.toEntity(mediaId, index))

                // For artists, recurse into their albums (which may contain tracks)
                // For folders, recurse into subfolders/audiobooks
                if (mediaType == MEDIA_TYPE_ARTIST || mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) {
                    syncChildrenRecursively(child, allEntities)
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to sync children for $mediaId", e)
        }
    }

    private fun MediaItem.toEntity(parentId: String, sortOrder: Int): CachedMediaItemEntity {
        val metadata = mediaMetadata
        val extrasJson = metadata.extras?.let { bundle ->
            val json = JSONObject()
            for (key in bundle.keySet()) {
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                }
            }
            if (json.length() > 0) json.toString() else null
        }

        val isFavorite = (metadata.userRating as? HeartRating)?.isHeart == true

        return CachedMediaItemEntity(
            mediaId = mediaId,
            parentId = parentId,
            title = metadata.title?.toString() ?: "",
            subtitle = metadata.albumArtist?.toString(),
            artUri = metadata.artworkUri?.let { contentUri ->
                AlbumArtContentProvider.originalUri(contentUri)?.toString() ?: contentUri.toString()
            },
            mediaType = metadata.mediaType ?: 0,
            isPlayable = metadata.isPlayable == true,
            isBrowsable = metadata.isBrowsable == true,
            sortOrder = sortOrder,
            durationMs = metadata.durationMs,
            isFavorite = isFavorite,
            extras = extrasJson
        )
    }

    private fun CachedMediaItemEntity.toMediaItem(): MediaItem {
        val extras = extras?.let { json ->
            val jsonObj = JSONObject(json)
            val bundle = android.os.Bundle()
            for (key in jsonObj.keys()) {
                when (val value = jsonObj.get(key)) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                }
            }
            bundle
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumArtist(subtitle)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(isPlayable)
            .setMediaType(mediaType)
            .setUserRating(HeartRating(isFavorite))

        if (artUri != null) {
            val uri = artUri.toUri()
            val artworkUri = if (uri.scheme == "http" || uri.scheme == "https") {
                AlbumArtContentProvider.mapUri(uri)
            } else {
                uri
            }
            metadataBuilder.setArtworkUri(artworkUri)
        }

        if (durationMs != null) {
            metadataBuilder.setDurationMs(durationMs)
        }

        if (extras != null) {
            metadataBuilder.setExtras(extras)
        }

        val itemBuilder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())

        val isAudiobook = extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
        if (isPlayable && (mediaType == MEDIA_TYPE_MUSIC || isAudiobook)) {
            itemBuilder.setUri(itemFactory.streamingUri(mediaId))
        }

        return itemBuilder.build()
    }
}
