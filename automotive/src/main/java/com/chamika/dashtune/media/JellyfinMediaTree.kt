package com.chamika.dashtune.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ARTIST
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_GENRE
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_PLAYLIST
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.R
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.DOWNLOADS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_FOLDER_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.LETTERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.LETTER_BUCKET_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FOLDER_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_GENRE_PREFIX
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import java.net.ConnectException
import java.util.concurrent.TimeoutException

private const val MAX_ITEMS = 120
private const val SHUFFLE_MAX_ITEMS = 500
private const val REQUEST_HARD_TIMEOUT_MS = 10_000L

class JellyfinMediaTree(
    private val context: Context,
    private val api: ApiClient,
    private val itemFactory: MediaItemFactory
) {

    private val mediaItems: Cache<String, MediaItem> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .build()

    fun invalidateCache() {
        mediaItems.invalidateAll()
    }

    fun getActiveCategoryIds(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = setOf("latest", "favourites", "books", "playlists")
        val selected = prefs.getStringSet("browse_categories", defaults) ?: defaults
        val canonicalOrder = listOf(
            "latest" to LATEST_ALBUMS,
            "favourites" to FAVOURITES,
            "books" to BOOKS,
            "playlists" to PLAYLISTS,
            "random" to RANDOM_ALBUMS,
            "folders" to FOLDERS,
            // Appended rather than slotted in alphabetically so existing users'
            // root tab order doesn't shift when they upgrade.
            "artists" to ARTISTS,
            "albums" to ALBUMS,
            "genres" to GENRES,
            "downloads" to DOWNLOADS
        )
        val validKeys = canonicalOrder.map { it.first }.toSet()
        val validSelected = selected.intersect(validKeys)
        val finalSelected = validSelected.ifEmpty { defaults }
        return canonicalOrder.filter { it.first in finalSelected }.map { it.second }
    }

    private suspend fun <T : Any> retryOnFailure(
        maxRetries: Int = 2,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                // Hard cap per attempt so no tree network call can suspend forever
                // even if a caller has no timeout guard of its own (issue #31).
                return withTimeoutOrNull(REQUEST_HARD_TIMEOUT_MS) { block() }
                    ?: throw TimeoutException("Request timed out after ${REQUEST_HARD_TIMEOUT_MS}ms")
            } catch (e: CancellationException) {
                // A caller's outer timeout cancelled us — propagate, never retry.
                throw e
            } catch (e: Exception) {
                if (e is TimeoutException || e is ConnectException ||
                    e.cause is TimeoutException || e.cause is ConnectException
                ) {
                    lastException = e
                    if (attempt < maxRetries) {
                        val delayMs = 1000L * (attempt + 1)
                        Log.w(LOG_TAG, "Retry ${attempt + 1}/$maxRetries after ${delayMs}ms", e)
                        delay(delayMs)
                    }
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    suspend fun getItem(id: String): MediaItem {
        if (mediaItems.getIfPresent(id) == null) {
            val newItem = when {
                id == ROOT_ID -> itemFactory.rootNode()
                id == LATEST_ALBUMS -> itemFactory.latestAlbums()
                id == RANDOM_ALBUMS -> itemFactory.randomAlbums()
                id == FAVOURITES -> itemFactory.favourites()
                id == PLAYLISTS -> itemFactory.playlists()
                id == BOOKS -> itemFactory.books()
                id == FOLDERS -> itemFactory.folders()
                id == ARTISTS -> itemFactory.artists()
                id == ALBUMS -> itemFactory.albums()
                id == GENRES -> itemFactory.genres()
                id == DOWNLOADS -> itemFactory.downloads()
                id.startsWith(SHUFFLE_FOLDER_PREFIX) ->
                    itemFactory.shuffleAll(id.removePrefix(SHUFFLE_FOLDER_PREFIX))
                id.startsWith(SHUFFLE_GENRE_PREFIX) ->
                    itemFactory.shuffleGenre(id.removePrefix(SHUFFLE_GENRE_PREFIX))
                id.startsWith(LETTER_BUCKET_PREFIX) -> {
                    val (categoryId, letter) = MediaItemFactory.parseLetterBucketId(id)
                        ?: throw UnsupportedOperationException("Malformed letter bucket id: $id")
                    itemFactory.letterBucket(categoryId, letter)
                }
                else -> retryOnFailure {
                    val response = api.userLibraryApi.getItem(id.toUUID())
                    val dto = response.content
                    // A CollectionFolder is a top-level music library, only reachable via
                    // the Folders category, so rebuild it as a folder-browse node. Without
                    // this, create() can't build a CollectionFolder at all and throws — the
                    // "Media isn't available" crash when the tree cache is cold (e.g. after
                    // a sync/invalidateCache re-fills Room but never re-seeds the tree cache
                    // via getFolders).
                    if (dto.type == BaseItemKind.COLLECTION_FOLDER) {
                        itemFactory.forFolder(dto, isFolderBrowse = true)
                    } else {
                        // Note: cold-fetching a book folder by ID (tree cache evicted, not in
                        // Room) loses the isAudiobook flag since it's not inferrable from the
                        // DTO alone. Mitigated by repository.getItem checking Room first and by
                        // browse flows always warming the tree cache parent-before-child.
                        itemFactory.create(dto)
                    }
                }
            }

            mediaItems.put(id, newItem)
        }

        return mediaItems.getIfPresent(id)!!
    }

    suspend fun getChildren(id: String): List<MediaItem> {
        return when {
            id == ROOT_ID -> getActiveCategoryIds().map { getItem(it) }
            id == LATEST_ALBUMS -> getLatestAlbums()
            id == RANDOM_ALBUMS -> getRandomAlbums()
            id == FAVOURITES -> getFavourite()
            id == PLAYLISTS -> getPlaylists()
            id == BOOKS -> getBooks()
            id == FOLDERS -> getFolders()
            id == ARTISTS || id == ALBUMS -> getLetterBuckets(id)
            id == GENRES -> getGenres()
            id.startsWith(LETTER_BUCKET_PREFIX) -> getLetterBucketChildren(id)
            else -> getItemChildren(id)
        }
    }

    /**
     * The full A–Z index is always shown. Probing each letter for emptiness would cost 27
     * round trips every time the category is opened, which is the wrong trade on a car's
     * connection — an occasional empty letter is cheaper than that latency.
     */
    private fun getLetterBuckets(categoryId: String): List<MediaItem> = LETTERS.map { letter ->
        val item = itemFactory.letterBucket(categoryId, letter)
        mediaItems.put(item.mediaId, item)
        item
    }

    private suspend fun getLetterBucketChildren(bucketId: String): List<MediaItem> {
        val (categoryId, letter) = MediaItemFactory.parseLetterBucketId(bucketId) ?: run {
            Log.w(LOG_TAG, "Malformed letter bucket id: $bucketId")
            return emptyList()
        }

        // Jellyfin has no "starts with a non-letter" filter, but names sorting before "A"
        // are exactly the digits and symbols the "#" bucket is meant to collect.
        val startsWith = if (letter == "#") null else letter
        val lessThan = if (letter == "#") "A" else null

        return retryOnFailure {
            val items = if (categoryId == ARTISTS) {
                api.artistsApi.getAlbumArtists(
                    nameStartsWith = startsWith,
                    nameLessThan = lessThan,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    limit = MAX_ITEMS
                ).content.items
            } else {
                api.itemsApi.getItems(
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    nameStartsWith = startsWith,
                    nameLessThan = lessThan,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    limit = MAX_ITEMS
                ).content.items
            }

            items.mapNotNull {
                try {
                    val item = itemFactory.create(it)
                    mediaItems.put(item.mediaId, item)
                    item
                } catch (e: UnsupportedOperationException) {
                    Log.w(LOG_TAG, "Skipping unsupported item type in $categoryId: ${it.type}")
                    null
                }
            }
        }
    }

    private suspend fun getGenres(): List<MediaItem> = retryOnFailure {
        val response = api.genresApi.getGenres(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            limit = MAX_ITEMS
        )

        response.content.items.mapNotNull {
            try {
                val item = itemFactory.create(it)
                mediaItems.put(item.mediaId, item)
                item
            } catch (e: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Skipping unsupported item type in genres: ${it.type}")
                null
            }
        }
    }

    private suspend fun getGenreAlbums(genreId: String): List<MediaItem> {
        val albums = retryOnFailure {
            val response = api.itemsApi.getItems(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                genreIds = listOf(genreId.toUUID()),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )

            response.content.items.map {
                val item = itemFactory.create(it)
                mediaItems.put(item.mediaId, item)
                item
            }
        }

        if (albums.isEmpty()) return albums

        val shuffle = itemFactory.shuffleGenre(genreId)
        mediaItems.put(shuffle.mediaId, shuffle)
        return listOf(shuffle) + albums
    }

    suspend fun getShuffledGenreTracks(genreId: String): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            genreIds = listOf(genreId.toUUID()),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.RANDOM),
            limit = SHUFFLE_MAX_ITEMS
        )

        response.content.items.mapNotNull {
            try {
                val item = itemFactory.create(it)
                mediaItems.put(item.mediaId, item)
                item
            } catch (e: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Skipping unsupported item type in genre shuffle: ${it.type}")
                null
            }
        }
    }

    private suspend fun getLatestAlbums(): List<MediaItem> = retryOnFailure {
        val response = api.userLibraryApi.getLatestMedia(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            limit = MAX_ITEMS
        )

        response.content.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun getRandomAlbums(): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            sortBy = listOf(ItemSortBy.RANDOM),
            limit = MAX_ITEMS
        )

        response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun getPlaylists(): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            recursive = true,
            sortOrder = listOf(SortOrder.DESCENDING),
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            limit = MAX_ITEMS
        )

        response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun getBooks(): List<MediaItem> = retryOnFailure {
        val views = api.userViewsApi.getUserViews()
        val booksLibrary = views.content.items.firstOrNull {
            it.collectionType == CollectionType.BOOKS
        } ?: return@retryOnFailure emptyList()

        val response = api.itemsApi.getItems(
            parentId = booksLibrary.id,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = MAX_ITEMS
        )

        response.content.items.mapNotNull {
            try {
                val item = itemFactory.create(it, isAudiobook = true)
                mediaItems.put(item.mediaId, item)
                item
            } catch (e: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Skipping unsupported item type in books: ${it.type}")
                null
            }
        }
    }

    private suspend fun getFolders(): List<MediaItem> {
        val musicLibraries = retryOnFailure {
            val views = api.userViewsApi.getUserViews()
            views.content.items.filter { it.collectionType == CollectionType.MUSIC }
        }

        return when {
            musicLibraries.isEmpty() -> emptyList()
            musicLibraries.size == 1 -> {
                val library = musicLibraries.first()
                val libraryId = library.id.toString()
                // Seed the cache so getItemChildren sees a MEDIA_TYPE_FOLDER_ALBUMS
                // parent (not an unsupported COLLECTION_FOLDER kind) and injects
                // "Shuffle all" for the root itself.
                mediaItems.put(libraryId, itemFactory.forFolder(library, isFolderBrowse = true))
                getItemChildren(libraryId)
            }
            else -> musicLibraries.map {
                val item = itemFactory.forFolder(it, isFolderBrowse = true)
                mediaItems.put(item.mediaId, item)
                item
            }
        }
    }

    private suspend fun getItemChildren(id: String): List<MediaItem> {
        val parentItem = getItem(id)
        val isFolderBrowse = parentItem.mediaMetadata.extras?.getBoolean(IS_FOLDER_KEY) == true

        // A genre has no parentId relationship to its albums — they're linked by genreIds —
        // so the generic children query below would come back empty.
        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_GENRE) {
            return getGenreAlbums(id)
        }

        // Jellyfin's music library organizes content by Artist/Album metadata rather than
        // raw filesystem folders, so nested folders can come back typed as MUSIC_ARTIST.
        // Outside folder browsing that's a deliberate shortcut ("show albums by this
        // artist"); inside folder browsing it must fall through to a literal parentId
        // children query below, since the artist-albums query returns nothing for a
        // directory that isn't a real tagged artist.
        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_ARTIST && !isFolderBrowse) {
            return getArtistAlbums(id)
        }

        val isAudiobook = parentItem.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true

        var sortBy = listOf(
            ItemSortBy.PARENT_INDEX_NUMBER,
            ItemSortBy.INDEX_NUMBER,
            ItemSortBy.SORT_NAME
        )

        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_PLAYLIST) {
            sortBy = listOf(ItemSortBy.DEFAULT)
        }

        val children = retryOnFailure {
            val response = api.itemsApi.getItems(
                sortBy = sortBy,
                parentId = id.toUUID()
            )

            response.content.items.mapNotNull {
                try {
                    val item = itemFactory.create(
                        it,
                        parent = id,
                        isAudiobook = isAudiobook,
                        isFolderBrowse = isFolderBrowse
                    )
                    mediaItems.put(item.mediaId, item)
                    item
                } catch (e: UnsupportedOperationException) {
                    Log.w(LOG_TAG, "Skipping unsupported item type: ${it.type}")
                    null
                }
            }
        }

        if (isFolderBrowse && children.isNotEmpty()) {
            val shuffle = itemFactory.shuffleAll(id)
            mediaItems.put(shuffle.mediaId, shuffle)
            return listOf(shuffle) + children
        }

        return children
    }

    suspend fun getShuffledTracks(folderId: String): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            parentId = folderId.toUUID(),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.RANDOM),
            limit = SHUFFLE_MAX_ITEMS
        )

        response.content.items.mapNotNull {
            try {
                val item = itemFactory.create(it)
                mediaItems.put(item.mediaId, item)
                item
            } catch (e: UnsupportedOperationException) {
                Log.w(LOG_TAG, "Skipping unsupported item type in shuffle: ${it.type}")
                null
            }
        }
    }

    private suspend fun getArtistAlbums(id: String): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            sortBy = listOf(
                ItemSortBy.PARENT_INDEX_NUMBER,
                ItemSortBy.INDEX_NUMBER,
                ItemSortBy.SORT_NAME
            ),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            albumArtistIds = listOf(id.toUUID()),
        )

        response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun getFavourite(): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            recursive = true,
            filters = listOf(ItemFilter.IS_FAVORITE),
            includeItemTypes = listOf(
                BaseItemKind.AUDIO,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST,
                BaseItemKind.AUDIO_BOOK
            )
        )

        response.content.items.map {
            val item = itemFactory.create(
                it,
                groupForItem(it),
                parent = FAVOURITES
            )
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private fun groupForItem(dto: BaseItemDto): String = when (dto.type) {
        BaseItemKind.MUSIC_ALBUM -> context.getString(R.string.albums)
        BaseItemKind.MUSIC_ARTIST -> context.getString(R.string.artists)
        BaseItemKind.AUDIO_BOOK -> context.getString(R.string.books)
        else -> context.getString(R.string.tracks)
    }

    suspend fun search(query: String): List<MediaItem> = retryOnFailure {
        val items = mutableListOf<MediaItem>()

        var response = api.artistsApi.getAlbumArtists(
            searchTerm = query,
            limit = 10,
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.artists))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.albums))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.playlists))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.books))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            limit = 20
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.tracks))
            mediaItems.put(item.mediaId, item)
            item
        })

        items
    }
}
