package com.chamika.dashtune.data

import android.os.SystemClock
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
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
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
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MediaRepository(
    private val dao: MediaCacheDao,
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
        ROOT_ID, HOME, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS, BOOKS, FOLDERS,
        ARTISTS, ALBUMS, GENRES
    )

    /**
     * Sections whose children are not crawled during [sync]. Folders are arbitrarily deep
     * on-disk trees, and the alphabet index would fan out to 27 buckets × every artist ×
     * every album — either turns a routine sync into a library-sized fetch. Deeper levels
     * cache lazily via [getChildren] as the user browses.
     */
    private val shallowSyncSections = setOf(HOME, FOLDERS, ARTISTS, ALBUMS, GENRES)

    /**
     * Home rebuilt this recently is served from memory. Home is five live queries, and a
     * paginating browser asks for the same node several times in a row; without this every
     * page would re-run all of them. Kept short because Home is meant to look different
     * after each drive.
     */
    private val homeCacheTtlMs = 2 * 60 * 1000L

    @Volatile
    private var homeChildren: List<MediaItem>? = null

    @Volatile
    private var homeCachedAtMs = 0L

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

        if (parentId == HOME) {
            return getHomeChildren()
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

    /**
     * Home is always built live when the TTL has expired, then written through to Room so it
     * still renders on a dead connection. Room is only read when the live build comes back
     * empty, because a stale Home that never refreshes is worse than a slightly slow one.
     */
    private suspend fun getHomeChildren(): List<MediaItem> {
        val cached = homeChildren
        if (cached != null && SystemClock.elapsedRealtime() - homeCachedAtMs < homeCacheTtlMs) {
            return cached
        }

        // Explicitly on IO: browse futures resolve on the main dispatcher, and unlike the
        // ordinary getChildren path this one does not go through `scope`, so without this
        // every section's HTTP call dies with NetworkOnMainThreadException.
        val fresh = try {
            withContext(Dispatchers.IO) { tree.getChildren(HOME) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to build Home", e)
            emptyList()
        }

        if (fresh.isNotEmpty()) {
            homeChildren = fresh
            homeCachedAtMs = SystemClock.elapsedRealtime()
            try {
                dao.deleteByParent(HOME)
                dao.insertAll(fresh.mapIndexed { index, item -> item.toEntity(HOME, index) })
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to cache Home", e)
            }
            return fresh
        }

        return dao.getChildrenByParent(HOME).map { it.toMediaItem() }
    }

    /** Forces the next Home browse to rebuild — the play history behind it has moved on. */
    fun invalidateHome() {
        homeChildren = null
        homeCachedAtMs = 0L
    }

    suspend fun getArtistRadioTracks(artistId: String): List<MediaItem> {
        return try {
            withContext(Dispatchers.IO) { tree.getArtistRadioTracks(artistId) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Artist radio failed for $artistId, falling back to cache", e)
            cachedDescendantTracks(artistId).shuffled()
        }
    }

    suspend fun getLatestTracks(limit: Int): List<MediaItem> {
        return try {
            withContext(Dispatchers.IO) { tree.getLatestTracks(limit) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Latest tracks query failed", e)
            emptyList()
        }
    }

    suspend fun getFavouriteTracksShuffled(): List<MediaItem> {
        return try {
            withContext(Dispatchers.IO) { tree.getFavouriteTracksShuffled() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Favourite shuffle failed, falling back to cache", e)
            cachedDescendantTracks(FAVOURITES).shuffled()
        }
    }

    suspend fun getLibraryShuffled(): List<MediaItem> {
        return try {
            withContext(Dispatchers.IO) { tree.getLibraryShuffled() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Library shuffle failed", e)
            emptyList()
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
        invalidateHome()
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
