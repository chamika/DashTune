package com.chamika.dashtune.data

import android.os.Bundle
import androidx.media3.session.MediaConstants
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
import com.chamika.dashtune.DashTuneSessionCallback.Companion.DOWNLOAD_COMMAND
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.data.db.PinnedDownloadDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.DOWNLOADS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LETTER_BUCKET_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.FirebaseUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MediaRepository(
    private val dao: MediaCacheDao,
    private val pinnedDownloadDao: PinnedDownloadDao,
    private val tree: JellyfinMediaTree,
    private val itemFactory: MediaItemFactory
) {

    private val syncMutex = Mutex()

    private val inFlightLock = Any()
    private val inFlight = HashMap<String, Deferred<List<MediaItem>>>()

    // Deliberately not the caller's scope: a shared fetch must not be cancelled when the
    // browser that happened to start it gives up (browse calls run under a timeout).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val staticIds = setOf(
        ROOT_ID, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS, BOOKS, FOLDERS,
        ARTISTS, ALBUMS, GENRES, DOWNLOADS
    )

    /**
     * Sections whose children are not crawled during [sync]. Folders are arbitrarily deep
     * on-disk trees, and the alphabet index would fan out to 27 buckets × every artist ×
     * every album — either turns a routine sync into a library-sized fetch. Deeper levels
     * cache lazily via [getChildren] as the user browses.
     */
    private val shallowSyncSections = setOf(FOLDERS, ARTISTS, ALBUMS, GENRES)

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
        // Letter buckets are browse scaffolding, not containers of playable siblings —
        // treat them like the static category ids so expandSingleItem never expands one.
        return dao.getParentIds(mediaId).firstOrNull {
            it !in staticIds && !it.startsWith(LETTER_BUCKET_PREFIX)
        }
    }

    suspend fun getChildren(parentId: String): List<MediaItem> {
        if (parentId == ROOT_ID) {
            return tree.getChildren(ROOT_ID)
        }

        // Downloads is served straight from the pinned-download table (local only), never from
        // the network-backed library cache — so it works fully offline and reflects removals.
        if (parentId == DOWNLOADS) {
            return getDownloads()
        }

        val cached = dao.getChildrenByParent(parentId)
        if (cached.isNotEmpty()) {
            return cached.map { it.toMediaItem() }
        }

        // Paginating browsers ask for several pages at once, so a cold parent can be
        // requested concurrently. Without coalescing, each caller fetches independently —
        // and for RANDOM_ALBUMS every fetch is a different set of albums, so the writes
        // below would union two random results under one parentId with colliding
        // sortOrder values, leaving the browse order unstable from then on.
        val fetch = synchronized(inFlightLock) {
            inFlight[parentId] ?: scope.async { fetchAndCacheChildren(parentId) }
                .also { inFlight[parentId] = it }
        }
        // Cleanup hangs off the fetch, not off this caller: browse runs under a timeout, and
        // a cancelled caller must neither leak the entry nor evict a fetch others still share.
        fetch.invokeOnCompletion {
            synchronized(inFlightLock) {
                if (inFlight[parentId] === fetch) inFlight.remove(parentId)
            }
        }

        return fetch.await()
    }

    private suspend fun fetchAndCacheChildren(parentId: String): List<MediaItem> {
        return try {
            val children = tree.getChildren(parentId)
            if (children.isNotEmpty()) {
                val entities = children.mapIndexed { index, item ->
                    item.toEntity(parentId, index)
                }
                // Drop whatever was cached under this parent first: insertAll only
                // REPLACEs rows whose (mediaId, parentId) still appears in the new list,
                // so items that dropped out would otherwise linger with stale sortOrder.
                dao.deleteByParent(parentId)
                dao.insertAll(entities)
            }
            children
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to fetch children for $parentId from network", e)
            emptyList()
        }
    }

    private suspend fun getDownloads(): List<MediaItem> {
        return pinnedDownloadDao.getAll().map { entity ->
            itemFactory.downloadedContainer(
                entity.containerId,
                entity.title,
                entity.subtitle,
                entity.artUri,
                entity.mediaType
            )
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        return tree.search(query)
    }

    suspend fun getShuffledTracks(folderId: String): List<MediaItem> {
        return try {
            tree.getShuffledTracks(folderId)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Shuffle query failed for $folderId, falling back to cache", e)
            cachedDescendantTracks(folderId).shuffled()
        }
    }

    suspend fun getShuffledGenreTracks(genreId: String): List<MediaItem> {
        return try {
            tree.getShuffledGenreTracks(genreId)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Genre shuffle query failed for $genreId, falling back to cache", e)
            cachedDescendantTracks(genreId).shuffled()
        }
    }

    private suspend fun cachedDescendantTracks(folderId: String, depth: Int = 0): List<MediaItem> {
        if (depth > 10) return emptyList()
        return dao.getChildrenByParent(folderId).flatMap {
            when {
                it.isPlayable && it.mediaType == MEDIA_TYPE_MUSIC -> listOf(it.toMediaItem())
                it.isBrowsable -> cachedDescendantTracks(it.mediaId, depth + 1)
                else -> emptyList()
            }
        }
    }

    fun invalidateCache() {
        tree.invalidateCache()
    }

    suspend fun sync(): Boolean = syncMutex.withLock {
        val sectionIds = tree.getActiveCategoryIds()
        val allEntities = mutableListOf<CachedMediaItemEntity>()
        var anySuccess = false

        FirebaseUtils.safeLog("Sync started: ${sectionIds.size} sections")

        for (sectionId in sectionIds) {
            // Downloads is a local-only view over the pinned-download table; there's nothing to
            // fetch, and its synthetic id isn't a Jellyfin UUID so a children query would throw.
            if (sectionId == DOWNLOADS) continue
            try {
                val children = tree.getChildren(sectionId)
                children.forEachIndexed { index, item ->
                    allEntities.add(item.toEntity(sectionId, index))
                    if (sectionId !in shallowSyncSections) {
                        syncChildrenRecursively(item, allEntities)
                    }
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

        // The persisted extras carry the content style captured when the row was first cached,
        // so a cached container keeps whatever layout it had then — including artist rows stored
        // as grids, which leaves their albums with no room for the download action. When
        // Downloads is on every container is list-styled, so stamp that over the stale value;
        // when it is off the persisted styles are the intended per-type ones, so leave them be.
        val effectiveExtras = if (itemFactory.downloadsCategoryEnabled) {
            (extras ?: Bundle()).apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
            }
        } else {
            extras
        }

        if (effectiveExtras != null) {
            metadataBuilder.setExtras(effectiveExtras)
        }

        // Re-advertise the "download for offline" browse action on cached container rows; the
        // fresh MediaItemFactory items carry it, but browse after a sync is served from here.
        // Audiobooks are cached with MEDIA_TYPE_ALBUM, so they're covered too. Shuffle rows are
        // excluded: they are synthetic playable playlists standing in for "play this folder
        // shuffled", so they match the container shape here but have nothing to pin. The items
        // MediaItemFactory builds carry no download action, and the cached copy must not either.
        if (isPlayable &&
            itemFactory.downloadsCategoryEnabled &&
            !MediaItemFactory.isShuffleId(mediaId) &&
            (mediaType == MEDIA_TYPE_ALBUM || mediaType == MEDIA_TYPE_PLAYLIST)
        ) {
            metadataBuilder.setSupportedCommands(listOf(DOWNLOAD_COMMAND))
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
