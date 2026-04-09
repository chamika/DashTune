package com.chamika.dashtune.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ARTIST
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_PLAYLIST
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.R
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.delay
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import java.net.ConnectException
import java.util.concurrent.TimeoutException

private const val MAX_ITEMS = 120

class JellyfinMediaTree(
    private val context: Context,
    private val api: ApiClient,
    private val itemFactory: MediaItemFactory
) {

    private val mediaItems: Cache<String, MediaItem> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .build()

    fun getActiveCategoryIds(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaults = setOf("latest", "favourites", "books", "playlists")
        val selected = prefs.getStringSet("browse_categories", defaults) ?: defaults
        val canonicalOrder = listOf(
            "latest" to LATEST_ALBUMS,
            "favourites" to FAVOURITES,
            "books" to BOOKS,
            "playlists" to PLAYLISTS,
            "random" to RANDOM_ALBUMS
        )
        val validKeys = canonicalOrder.map { it.first }.toSet()
        val validSelected = selected.intersect(validKeys)
        val finalSelected = validSelected.ifEmpty { defaults }
        return canonicalOrder.filter { it.first in finalSelected }.map { it.second }
    }

    private suspend fun <T> retryOnFailure(
        maxRetries: Int = 2,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
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
            val newItem = when (id) {
                ROOT_ID -> itemFactory.rootNode()
                LATEST_ALBUMS -> itemFactory.latestAlbums()
                RANDOM_ALBUMS -> itemFactory.randomAlbums()
                FAVOURITES -> itemFactory.favourites()
                PLAYLISTS -> itemFactory.playlists()
                BOOKS -> itemFactory.books()
                else -> retryOnFailure {
                    val response = api.userLibraryApi.getItem(id.toUUID())
                    itemFactory.create(response.content)
                }
            }

            mediaItems.put(id, newItem)
        }

        return mediaItems.getIfPresent(id)!!
    }

    suspend fun getChildren(id: String): List<MediaItem> {
        return when (id) {
            ROOT_ID -> getActiveCategoryIds().map { getItem(it) }
            LATEST_ALBUMS -> getLatestAlbums()
            RANDOM_ALBUMS -> getRandomAlbums()
            FAVOURITES -> getFavourite()
            PLAYLISTS -> getPlaylists()
            BOOKS -> getBooks()
            else -> getItemChildren(id)
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
        val response = api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
            recursive = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = MAX_ITEMS
        )

        response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun getItemChildren(id: String): List<MediaItem> {
        val parentItem = getItem(id)

        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_ARTIST) {
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

        return retryOnFailure {
            val response = api.itemsApi.getItems(
                sortBy = sortBy,
                parentId = id.toUUID()
            )

            response.content.items.map {
                val item = itemFactory.create(it, parent = id, isAudiobook = isAudiobook)
                mediaItems.put(item.mediaId, item)
                item
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
