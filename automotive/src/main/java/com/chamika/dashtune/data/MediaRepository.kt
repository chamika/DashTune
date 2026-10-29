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
import com.chamika.dashtune.media.TrackList
import com.chamika.dashtune.media.MediaItemFactory.Companion.TRACK_ARTIST_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_TRACKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LETTER_BUCKET_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RESUME_QUEUE
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_NEW
import com.chamika.dashtune.FirebaseUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MediaRepository(
    private val dao: MediaCacheDao,
    private val tree: JellyfinMediaTree,
    private val itemFactory: MediaItemFactory
) {

    companion object {
        /**
         * Room parent holding the tracks of the last queue that was set, in order. Lets a cold
         * start restore the queue without one network call per track, which a queue of a few
         * hundred tracks cannot finish inside the resumption timeout.
         */
        const val SAVED_QUEUE = "SAVED_QUEUE_ID"
    }

    private val syncMutex = Mutex()

    private val inFlightLock = Any()
    private val inFlight = HashMap<String, Deferred<List<MediaItem>>>()

    // Deliberately not the caller's scope: a shared fetch must not be cancelled when the
    // browser that happened to start it gives up (browse calls run under a timeout).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One lane, so two queues set in quick succession are written in the order they were set. */
    private val queueWrites = Dispatchers.IO.limitedParallelism(1)

    private val staticIds = setOf(
        ROOT_ID, HOME, LATEST_ALBUMS, RANDOM_TRACKS, FAVOURITES, PLAYLISTS, BOOKS, FOLDERS,
        ARTISTS, ALBUMS, GENRES
    )

    /**
     * Sections whose children are not crawled during [sync]. Folders are arbitrarily deep
     * on-disk trees, and the alphabet index would fan out to 27 buckets × every artist ×
     * every album — either turns a routine sync into a library-sized fetch. Deeper levels
     * cache lazily via [getChildren] as the user browses.
     */
    private val shallowSyncSections = setOf(FOLDERS, GENRES)

    /**
     * Sections sync leaves alone. Home caches itself on every browse, and the Artists and
     * Albums indexes are built on the device. None of them fails when the server is
     * unreachable — Home just comes back with fewer tiles — so counting them let a sync that
     * never reached the server pass for a successful one and replace the cache with nothing.
     */
    private val unsyncedSections = setOf(HOME, ARTISTS, ALBUMS)

    /** Parents a full sync must not clear: neither is rebuilt by sync. */
    private val keptBySync = listOf(HOME, SAVED_QUEUE)

    /** Home tiles built without the server. A Home made only of these proves nothing was reached. */
    private val localHomeTiles = setOf(RESUME_QUEUE, SHUFFLE_FAVOURITES, SHUFFLE_NEW, SHUFFLE_LIBRARY)

    /**
     * Home rebuilt this recently is served from memory. Home is five live queries, and a
     * paginating browser asks for the same node several times in a row; without this every
     * page would re-run all of them. Kept short because Home is meant to look different
     * after each drive.
     */
    private val homeCacheTtlMs = 2 * 60 * 1000L

    /**
     * How long a Home built without the server is reused. Long enough that the pages of one
     * browse share it, short enough that the next browse tries the server again.
     */
    private val offlineHomeTtlMs = 30 * 1000L

    @Volatile
    private var homeChildren: List<MediaItem>? = null

    @Volatile
    private var homeExpiresAtMs = 0L

    suspend fun getItem(id: String): MediaItem {
        TrackList.parentId(id)?.let { parentId ->
            return getChildren(parentId).firstOrNull { it.mediaId == id }
                ?: TrackList.browseItem(getItem(TrackList.trackId(id)), parentId)
        }
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
        // The saved queue is not a container either: tapping one track of it must not queue
        // the whole previous session.
        return dao.getParentIds(mediaId).firstOrNull {
            it !in staticIds && it != SAVED_QUEUE && !it.startsWith(LETTER_BUCKET_PREFIX)
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
            return cached.map { TrackList.browseItem(it.toMediaItem(), parentId) }
        }

        // Paginating browsers ask for several pages at once, so a cold parent can be
        // requested concurrently. Without coalescing, each caller fetches independently —
        // and for RANDOM_TRACKS every fetch is a different set of tracks, so the writes
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

        return fetch.await().map { TrackList.browseItem(it, parentId) }
    }

    private suspend fun fetchAndCacheChildren(parentId: String): List<MediaItem> {
        return try {
            val children = tree.getChildren(parentId)
            if (children.isNotEmpty()) {
                val entities = children.mapIndexed { index, item ->
                    item.toEntity(parentId, index)
                }
                dao.replaceChildren(mapOf(parentId to entities))
            }
            children
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to fetch children for $parentId from network", e)
            emptyList()
        }
    }

    /**
     * Home is always built live when the TTL has expired, then written through to Room so it
     * still renders on a dead connection.
     *
     * A build is only trusted when the server contributed to it. With the server unreachable
     * every network section drops out and what is left is the handful of tiles built on the
     * device, so judging the build by emptiness would serve those over a perfectly good Home
     * in Room — and overwrite it. Such a build is never written back; the last live Home is
     * served instead, with the resume tile (local, so always current) taken from the new build.
     */
    private suspend fun getHomeChildren(): List<MediaItem> {
        val cached = homeChildren
        if (cached != null && SystemClock.elapsedRealtime() < homeExpiresAtMs) {
            return cached
        }

        // Explicitly on IO: browse futures resolve on the main dispatcher, and unlike the
        // ordinary getChildren path this one does not go through `scope`, so without this
        // every section's HTTP call dies with NetworkOnMainThreadException.
        val built = try {
            withContext(Dispatchers.IO) { tree.getChildren(HOME) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to build Home", e)
            emptyList()
        }

        if (built.any { it.mediaId !in localHomeTiles }) {
            remember(built, homeCacheTtlMs)
            try {
                dao.replaceChildren(
                    mapOf(HOME to built.mapIndexed { index, item -> item.toEntity(HOME, index) })
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to cache Home", e)
            }
            return built
        }

        val stored = try {
            dao.getChildrenByParent(HOME).map { it.toMediaItem() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read cached Home", e)
            emptyList()
        }

        Log.i(LOG_TAG, "Home built without the server; serving ${if (stored.isEmpty()) "local tiles" else "the cached Home"}")
        val offline = if (stored.isEmpty()) {
            built
        } else {
            listOfNotNull(built.firstOrNull { it.mediaId == RESUME_QUEUE }) +
                stored.filterNot { it.mediaId == RESUME_QUEUE }
        }
        remember(offline, offlineHomeTtlMs)
        return offline
    }

    private fun remember(home: List<MediaItem>, ttlMs: Long) {
        homeChildren = home
        homeExpiresAtMs = SystemClock.elapsedRealtime() + ttlMs
    }

    /** Forces the next Home browse to rebuild — the play history behind it has moved on. */
    fun invalidateHome() {
        homeChildren = null
        homeExpiresAtMs = 0L
    }

    /**
     * Writes the tracks of the queue just set under [SAVED_QUEUE], replacing the previous
     * queue. Runs in the repository's own scope so playback never waits on the write.
     */
    fun saveQueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val rows = items.mapIndexed { index, item -> item.toEntity(SAVED_QUEUE, index) }
        scope.launch(queueWrites) {
            try {
                dao.replaceChildren(mapOf(SAVED_QUEUE to rows))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to persist the queue", e)
            }
        }
    }

    /** The tracks of the last saved queue, in order. Room only, never the network. */
    suspend fun getSavedQueue(): List<MediaItem> =
        dao.getChildrenByParent(SAVED_QUEUE).map { it.toMediaItem() }

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

    /**
     * Refreshes the cache from the server. Returns true only when at least one section was
     * actually fetched from it.
     *
     * When every fetch succeeds the result is a complete picture of the library, so it
     * replaces the whole cache — which is also what eventually refreshes nodes cached lazily
     * while browsing (folders, genres, albums opened from search) that no section reaches.
     * When anything fails, only the parents that were re-fetched are replaced and everything
     * else keeps its last good copy: a sync at the start of a drive, while the car's
     * connection is still coming up, must never leave less offline content than it found.
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) { syncLocked() }

    // Callers include the service's main-thread scope, and the SDK reads response bodies on
    // whichever thread resumes it — a sync started from main failed with
    // NetworkOnMainThreadException on large libraries.
    private suspend fun syncLocked(): Boolean = syncMutex.withLock {
        val sectionIds = tree.getActiveCategoryIds().filterNot { it in unsyncedSections }
        val refreshed = LinkedHashMap<String, List<CachedMediaItemEntity>>()
        var complete = true

        FirebaseUtils.safeLog("Sync started: ${sectionIds.size} sections")

        for (sectionId in sectionIds) {
            try {
                val children = tree.getChildren(sectionId)
                refreshed[sectionId] = children.mapIndexed { index, item -> item.toEntity(sectionId, index) }
                if (sectionId !in shallowSyncSections) {
                    children.forEach { complete = syncChildrenRecursively(it, refreshed) && complete }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false
                Log.e(LOG_TAG, "Failed to sync section $sectionId", e)
                FirebaseUtils.safeSetCustomKey("sync_failed_section", sectionId)
                FirebaseUtils.safeRecordApiException(e)
            }
        }

        val reachedServer = sectionIds.any { it in refreshed }
        if (reachedServer) {
            if (complete) {
                dao.replaceAllExcept(refreshed.values.flatten(), keepParents = keptBySync)
            } else {
                dao.replaceChildren(refreshed)
            }
        }

        FirebaseUtils.safeLog(
            "Sync completed: ${refreshed.values.sumOf { it.size }} items, " +
                "success=$reachedServer, complete=$complete"
        )
        return reachedServer
    }

    /**
     * Adds [item]'s children (and, for artists and folders, theirs) to [refreshed]. Returns
     * false if any of those fetches failed, so the sync knows its picture is incomplete.
     */
    private suspend fun syncChildrenRecursively(
        item: MediaItem,
        refreshed: MutableMap<String, List<CachedMediaItemEntity>>
    ): Boolean {
        val mediaType = item.mediaMetadata.mediaType
        val mediaId = item.mediaId

        // Playlists are playable rather than browsable, so the browsable check alone never
        // let their tracks into the cache — and tapping a playlist offline needs exactly
        // those. Album tracks are still left to cache on first play: Latest and Random would
        // otherwise cost a request per album on every sync.
        val isRealPlaylist = mediaType == MEDIA_TYPE_PLAYLIST &&
                !MediaItemFactory.isShuffleId(mediaId) && !MediaItemFactory.isHomeActionId(mediaId)
        val shouldFetchChildren = isRealPlaylist || item.mediaMetadata.isBrowsable == true && (
                mediaType == MEDIA_TYPE_ARTIST ||
                mediaType == MEDIA_TYPE_ALBUM ||
                mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        )

        if (!shouldFetchChildren) return true

        return try {
            val children = tree.getChildren(mediaId)
            refreshed[mediaId] = children.mapIndexed { index, child -> child.toEntity(mediaId, index) }

            // For artists, recurse into their albums (which may contain tracks)
            // For folders, recurse into subfolders/audiobooks
            var complete = true
            if (mediaType == MEDIA_TYPE_ARTIST || mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS) {
                children.forEach { complete = syncChildrenRecursively(it, refreshed) && complete }
            }
            complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to sync children for $mediaId", e)
            false
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
            .setArtist(extras?.getString(TRACK_ARTIST_KEY) ?: subtitle)
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
