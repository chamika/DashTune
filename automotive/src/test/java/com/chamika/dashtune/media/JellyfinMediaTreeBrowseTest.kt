package com.chamika.dashtune.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LETTERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_GENRE_PREFIX
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.ArtistsApi
import org.jellyfin.sdk.api.operations.GenresApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Covers the Artists/Albums alphabet index and Genres browsing added for issue #37.
 * The point of these tests is the *query shape* sent to Jellyfin — a genre's albums are
 * linked by genreIds rather than parentId, and the "#" bucket relies on nameLessThan —
 * so the item factory is mocked and the API calls are asserted directly.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinMediaTreeBrowseTest {

    private companion object {
        const val MAX_ITEMS = 120
        const val SHUFFLE_MAX_ITEMS = 500
    }

    private lateinit var context: Context
    private lateinit var api: ApiClient
    private lateinit var artistsApi: ArtistsApi
    private lateinit var itemsApi: ItemsApi
    private lateinit var genresApi: GenresApi
    private lateinit var itemFactory: MediaItemFactory
    private lateinit var tree: JellyfinMediaTree

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()

        api = mockk(relaxed = true)
        artistsApi = mockk(relaxed = true)
        itemsApi = mockk(relaxed = true)
        genresApi = mockk(relaxed = true)
        every { api.artistsApi } returns artistsApi
        every { api.itemsApi } returns itemsApi
        every { api.genresApi } returns genresApi

        itemFactory = mockk(relaxed = true)
        every { itemFactory.letterBucket(any(), any()) } answers {
            browsable(MediaItemFactory.letterBucketId(firstArg(), secondArg()), secondArg())
        }
        every { itemFactory.artists() } returns browsable(ARTISTS, "Artists")
        every { itemFactory.albums() } returns browsable(ALBUMS, "Albums")
        every { itemFactory.genres() } returns browsable(GENRES, "Genres")
        every { itemFactory.shuffleGenre(any()) } answers {
            browsable(SHUFFLE_GENRE_PREFIX + firstArg<String>(), "Shuffle all")
        }
        every { itemFactory.create(any(), any(), any(), any(), any()) } answers {
            val dto = firstArg<BaseItemDto>()
            val type = if (dto.type == BaseItemKind.MUSIC_GENRE) {
                MediaMetadata.MEDIA_TYPE_GENRE
            } else {
                MediaMetadata.MEDIA_TYPE_ALBUM
            }
            browsable(dto.id.toString(), dto.name ?: "", type)
        }

        tree = JellyfinMediaTree(context = context, api = api, itemFactory = itemFactory)
    }

    private fun browsable(
        id: String,
        title: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun dto(type: BaseItemKind, name: String) =
        BaseItemDto(id = UUID.randomUUID(), type = type, name = name)

    private fun result(vararg items: BaseItemDto) = Response(
        content = BaseItemDtoQueryResult(
            items = items.toList(),
            totalRecordCount = items.size,
            startIndex = 0
        ),
        status = 200,
        headers = emptyMap()
    )

    // --- Alphabet index ---

    @Test
    fun `artists category lists the full alphabet index`() = runTest {
        val children = tree.getChildren(ARTISTS)

        assertEquals(LETTERS.size, children.size)
        assertEquals(LETTERS, children.map { it.mediaMetadata.title.toString() })
        assertEquals(
            MediaItemFactory.letterBucketId(ARTISTS, "#"),
            children.first().mediaId
        )
    }

    @Test
    fun `albums category lists the full alphabet index`() = runTest {
        val children = tree.getChildren(ALBUMS)

        assertEquals(LETTERS.size, children.size)
        assertTrue(children.all { it.mediaId.startsWith(MediaItemFactory.LETTER_BUCKET_PREFIX) })
    }

    @Test
    fun `artist letter bucket queries album artists whose name starts with that letter`() = runTest {
        coEvery {
            artistsApi.getAlbumArtists(
                nameStartsWith = "B",
                nameLessThan = null,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(dto(BaseItemKind.MUSIC_ARTIST, "Beach House"))

        val children = tree.getChildren(MediaItemFactory.letterBucketId(ARTISTS, "B"))

        assertEquals(listOf("Beach House"), children.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `hash bucket collects names sorting before A`() = runTest {
        coEvery {
            artistsApi.getAlbumArtists(
                nameStartsWith = null,
                nameLessThan = "A",
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(dto(BaseItemKind.MUSIC_ARTIST, "65daysofstatic"))

        val children = tree.getChildren(MediaItemFactory.letterBucketId(ARTISTS, "#"))

        assertEquals(listOf("65daysofstatic"), children.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `album letter bucket queries albums sorted by sort name`() = runTest {
        coEvery {
            itemsApi.getItems(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                nameStartsWith = "C",
                nameLessThan = null,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(dto(BaseItemKind.MUSIC_ALBUM, "Currents"))

        val children = tree.getChildren(MediaItemFactory.letterBucketId(ALBUMS, "C"))

        assertEquals(listOf("Currents"), children.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `malformed letter bucket id yields no children instead of throwing`() = runTest {
        assertEquals(emptyList<MediaItem>(), tree.getChildren("LETTER:no-separator"))
    }

    // --- Genres ---

    @Test
    fun `genres category lists music genres alphabetically`() = runTest {
        coEvery {
            genresApi.getGenres(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(
            dto(BaseItemKind.MUSIC_GENRE, "Ambient"),
            dto(BaseItemKind.MUSIC_GENRE, "Rock")
        )

        val children = tree.getChildren(GENRES)

        assertEquals(listOf("Ambient", "Rock"), children.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun `genre children are its albums behind a shuffle all entry`() = runTest {
        val genre = dto(BaseItemKind.MUSIC_GENRE, "Rock")
        val genreId = genre.id.toString()
        coEvery {
            genresApi.getGenres(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(genre)
        coEvery {
            itemsApi.getItems(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                genreIds = listOf(genre.id),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(dto(BaseItemKind.MUSIC_ALBUM, "Nevermind"))

        // Warm the tree cache the way browsing does, so the genre node is typed correctly.
        tree.getChildren(GENRES)
        val children = tree.getChildren(genreId)

        assertEquals(2, children.size)
        assertEquals(SHUFFLE_GENRE_PREFIX + genreId, children[0].mediaId)
        assertEquals("Nevermind", children[1].mediaMetadata.title.toString())
    }

    @Test
    fun `genre with no albums gets no shuffle entry`() = runTest {
        val genre = dto(BaseItemKind.MUSIC_GENRE, "Empty")
        val genreId = genre.id.toString()
        coEvery {
            genresApi.getGenres(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result(genre)
        coEvery {
            itemsApi.getItems(
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                genreIds = listOf(genre.id),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                limit = MAX_ITEMS
            )
        } returns result()

        tree.getChildren(GENRES)

        assertEquals(emptyList<MediaItem>(), tree.getChildren(genreId))
    }

    @Test
    fun `genre shuffle queries tracks by genre id rather than parent id`() = runTest {
        val genreId = UUID.randomUUID()
        coEvery {
            itemsApi.getItems(
                genreIds = listOf(genreId),
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = listOf(ItemSortBy.RANDOM),
                limit = SHUFFLE_MAX_ITEMS
            )
        } returns result(dto(BaseItemKind.AUDIO, "Lithium"))

        val tracks = tree.getShuffledGenreTracks(genreId.toString())

        assertEquals(listOf("Lithium"), tracks.map { it.mediaMetadata.title.toString() })
    }

    // --- Cold-cache resolution ---

    @Test
    fun `letter bucket and genre shuffle ids resolve without a network call`() = runTest {
        val bucket = tree.getItem(MediaItemFactory.letterBucketId(ALBUMS, "D"))
        assertEquals("D", bucket.mediaMetadata.title.toString())

        val shuffle = tree.getItem(SHUFFLE_GENRE_PREFIX + "genre-1")
        assertEquals(SHUFFLE_GENRE_PREFIX + "genre-1", shuffle.mediaId)
    }

    @Test
    fun `category ids resolve to their factory nodes`() = runTest {
        assertEquals(ARTISTS, tree.getItem(ARTISTS).mediaId)
        assertEquals(ALBUMS, tree.getItem(ALBUMS).mediaId)
        assertEquals(GENRES, tree.getItem(GENRES).mediaId)
    }
}
