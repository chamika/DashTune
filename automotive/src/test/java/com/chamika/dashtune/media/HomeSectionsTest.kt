package com.chamika.dashtune.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_IDS_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.ArtistsApi
import org.jellyfin.sdk.api.operations.GenresApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.util.UUID

/**
 * Covers the Home tab's shape rather than its styling: the section order, the per-row tile
 * budget, that every section degrades to nothing rather than taking Home down with it, and
 * the query shapes that make the history-derived sections correct (music-scoped so audiobook
 * chapters cannot leak in, DatePlayed descending, genres ranked by play count).
 */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsTest {

    private lateinit var context: Context
    private lateinit var api: ApiClient
    private lateinit var itemsApi: ItemsApi
    private lateinit var artistsApi: ArtistsApi
    private lateinit var genresApi: GenresApi
    private lateinit var userLibraryApi: UserLibraryApi
    private lateinit var userViewsApi: UserViewsApi
    private lateinit var itemFactory: MediaItemFactory
    private lateinit var sections: HomeSections

    private val musicViewId: UUID = UUID.randomUUID()
    private val booksViewId: UUID = UUID.randomUUID()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()

        api = mockk(relaxed = true)
        itemsApi = mockk(relaxed = true)
        artistsApi = mockk(relaxed = true)
        genresApi = mockk(relaxed = true)
        userLibraryApi = mockk(relaxed = true)
        userViewsApi = mockk(relaxed = true)
        every { api.itemsApi } returns itemsApi
        every { api.artistsApi } returns artistsApi
        every { api.genresApi } returns genresApi
        every { api.userLibraryApi } returns userLibraryApi
        every { api.userViewsApi } returns userViewsApi

        itemFactory = fakeFactory()

        // Empty by default; each test opts into the data its section needs.
        coEvery { userViewsApi.getUserViews() } returns result(
            dto(BaseItemKind.COLLECTION_FOLDER, "Music", musicViewId)
                .copy(collectionType = CollectionType.MUSIC),
            dto(BaseItemKind.COLLECTION_FOLDER, "Books", booksViewId)
                .copy(collectionType = CollectionType.BOOKS)
        )
        givenRecentlyPlayed()
        givenAlbumsById()
        givenFavouriteArtists()
        givenAnyArtists()
        givenResumeItems()
        givenGenres()
        givenLatestAlbums()

        sections = HomeSections(context, api, itemFactory)
    }

    // --- Layout ---

    @Test
    fun `sections appear in the designed order`() = runTest {
        givenAFullLibrary()
        givenASavedQueue()
        givenAnInProgressBook()

        val groups = sections.build(tilesPerRow = 4).map { it.groupTitle() }.distinct()

        assertEquals(
            listOf(
                context.getString(R.string.continue_listening),
                context.getString(R.string.made_for_you),
                context.getString(R.string.new_for_you),
                context.getString(R.string.jump_back_in),
                context.getString(R.string.mixes)
            ),
            groups
        )
    }

    @Test
    fun `no section is wider than one row on a four-tile screen`() = runTest {
        givenAFullLibrary()

        val perGroup = sections.build(tilesPerRow = 4)
            .filterNot { it.groupTitle() == context.getString(R.string.continue_listening) }
            .groupingBy { it.groupTitle() }
            .eachCount()

        assertTrue(perGroup.isNotEmpty())
        perGroup.forEach { (group, count) -> assertTrue("$group had $count", count <= 4) }
    }

    @Test
    fun `no section is wider than one row on a three-tile screen`() = runTest {
        givenAFullLibrary()

        val perGroup = sections.build(tilesPerRow = 3)
            .filterNot { it.groupTitle() == context.getString(R.string.continue_listening) }
            .groupingBy { it.groupTitle() }
            .eachCount()

        assertTrue(perGroup.isNotEmpty())
        perGroup.forEach { (group, count) -> assertTrue("$group had $count", count <= 3) }
    }

    @Test
    fun `every shuffle section keeps its action tile when the row shrinks`() = runTest {
        givenAFullLibrary()

        val ids = sections.build(tilesPerRow = 3).map { it.mediaId }

        assertTrue(ids.contains(MediaItemFactory.SHUFFLE_FAVOURITES))
        assertTrue(ids.contains(MediaItemFactory.SHUFFLE_NEW))
        assertTrue(ids.contains(MediaItemFactory.SHUFFLE_LIBRARY))
    }

    // --- Continue listening ---

    @Test
    fun `continue listening is absent when there is nothing to resume`() = runTest {
        givenAFullLibrary()

        val titles = sections.build(tilesPerRow = 4).map { it.groupTitle() }

        assertFalse(titles.contains(context.getString(R.string.continue_listening)))
    }

    @Test
    fun `the saved queue comes before the in-progress book`() = runTest {
        givenAFullLibrary()
        givenASavedQueue()
        givenAnInProgressBook()

        val resumeRow = sections.build(tilesPerRow = 4)
            .filter { it.groupTitle() == context.getString(R.string.continue_listening) }

        assertEquals(2, resumeRow.size)
        assertEquals(MediaItemFactory.RESUME_QUEUE, resumeRow[0].mediaId)
    }

    @Test
    fun `the book row stands alone when no music queue was saved`() = runTest {
        givenAFullLibrary()
        givenAnInProgressBook()

        val resumeRow = sections.build(tilesPerRow = 4)
            .filter { it.groupTitle() == context.getString(R.string.continue_listening) }

        assertEquals(1, resumeRow.size)
        assertTrue(resumeRow[0].mediaId != MediaItemFactory.RESUME_QUEUE)
    }

    // --- Resilience ---

    @Test
    fun `one failing section leaves the others standing`() = runTest {
        givenAFullLibrary()
        coEvery {
            userLibraryApi.getLatestMedia(includeItemTypes = any(), limit = any())
        } throws IllegalStateException("boom")

        val groups = sections.build(tilesPerRow = 4).map { it.groupTitle() }.distinct()

        assertFalse(groups.contains(context.getString(R.string.new_for_you)))
        assertTrue(groups.contains(context.getString(R.string.made_for_you)))
        assertTrue(groups.contains(context.getString(R.string.jump_back_in)))
        assertTrue(groups.contains(context.getString(R.string.mixes)))
    }

    @Test
    fun `a library with no music at all still returns the shuffle tiles`() = runTest {
        val built = sections.build(tilesPerRow = 4)

        // Nothing to recommend, but the actions still work.
        assertTrue(built.map { it.mediaId }.contains(MediaItemFactory.SHUFFLE_FAVOURITES))
        assertTrue(built.map { it.mediaId }.contains(MediaItemFactory.SHUFFLE_LIBRARY))
    }

    // --- Query shapes ---

    @Test
    fun `recently played is scoped to music so audiobook chapters cannot leak in`() = runTest {
        val parentId = slot<UUID>()
        val sortBy = slot<Collection<ItemSortBy>>()
        val sortOrder = slot<Collection<SortOrder>>()
        coEvery {
            itemsApi.getItems(
                parentId = capture(parentId),
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = capture(sortBy),
                sortOrder = capture(sortOrder),
                fields = any(),
                limit = any()
            )
        } returns result()

        sections.build(tilesPerRow = 4)

        assertEquals(musicViewId, parentId.captured)
        assertEquals(listOf(ItemSortBy.DATE_PLAYED), sortBy.captured.toList())
        assertEquals(listOf(SortOrder.DESCENDING), sortOrder.captured.toList())
    }

    @Test
    fun `jump back in shows the albums of the most recently played tracks, newest first`() =
        runTest {
            val albumA = UUID.randomUUID()
            val albumB = UUID.randomUUID()
            givenRecentlyPlayed(
                track("Newest", albumId = albumB, playedAt = LocalDateTime.of(2026, 1, 3, 0, 0)),
                track("Older", albumId = albumA, playedAt = LocalDateTime.of(2026, 1, 1, 0, 0))
            )
            // Deliberately the other way round: the server does not honour `ids` order.
            givenAlbumsById(
                dto(BaseItemKind.MUSIC_ALBUM, "Album A", albumA),
                dto(BaseItemKind.MUSIC_ALBUM, "Album B", albumB)
            )

            val jumpBackIn = sections.build(tilesPerRow = 4)
                .filter { it.groupTitle() == context.getString(R.string.jump_back_in) }

            assertEquals(listOf("Album B", "Album A"), jumpBackIn.map { it.title() })
        }

    @Test
    fun `mixes rank genres by how often they were played`() = runTest {
        val rock = NameGuidPair(id = UUID.randomUUID(), name = "Rock")
        val jazz = NameGuidPair(id = UUID.randomUUID(), name = "Jazz")
        givenRecentlyPlayed(
            track("One", genres = listOf(jazz)),
            track("Two", genres = listOf(rock)),
            track("Three", genres = listOf(rock))
        )

        val mixes = sections.build(tilesPerRow = 3)
            .filter { it.groupTitle() == context.getString(R.string.mixes) }
            .filterNot { it.mediaId == MediaItemFactory.SHUFFLE_LIBRARY }

        assertEquals(
            listOf(
                MediaItemFactory.SHUFFLE_GENRE_PREFIX + rock.id,
                MediaItemFactory.SHUFFLE_GENRE_PREFIX + jazz.id
            ),
            mixes.map { it.mediaId }
        )
    }

    @Test
    fun `new albums are prefixed so tapping one continues past the album`() = runTest {
        val albumId = UUID.randomUUID()
        givenLatestAlbums(dto(BaseItemKind.MUSIC_ALBUM, "Fresh", albumId))

        val newForYou = sections.build(tilesPerRow = 4)
            .filter { it.groupTitle() == context.getString(R.string.new_for_you) }

        assertEquals(MediaItemFactory.NEW_ALBUM_PREFIX + albumId, newForYou[0].mediaId)
    }

    @Test
    fun `favourite artists seed the radios before anything else`() = runTest {
        val favourite = UUID.randomUUID()
        givenFavouriteArtists(dto(BaseItemKind.MUSIC_ARTIST, "Favourite Artist", favourite))

        val radios = sections.build(tilesPerRow = 4)
            .filter { it.groupTitle() == context.getString(R.string.made_for_you) }
            .filterNot { it.mediaId == MediaItemFactory.SHUFFLE_FAVOURITES }

        assertEquals(MediaItemFactory.RADIO_ARTIST_PREFIX + favourite, radios[0].mediaId)
    }

    // --- Fixtures ---

    private fun givenAFullLibrary() {
        // The album id on the played track has to be the album the lookup returns, or
        // "Jump back in" correctly finds nothing to show.
        val playedAlbumId = UUID.randomUUID()
        givenRecentlyPlayed(
            track("Played", albumId = playedAlbumId, genres = listOf(
                NameGuidPair(id = UUID.randomUUID(), name = "Rock")
            ))
        )
        givenAlbumsById(dto(BaseItemKind.MUSIC_ALBUM, "Recent Album", playedAlbumId))
        givenFavouriteArtists(
            dto(BaseItemKind.MUSIC_ARTIST, "Artist One"),
            dto(BaseItemKind.MUSIC_ARTIST, "Artist Two"),
            dto(BaseItemKind.MUSIC_ARTIST, "Artist Three")
        )
        givenLatestAlbums(
            dto(BaseItemKind.MUSIC_ALBUM, "New One"),
            dto(BaseItemKind.MUSIC_ALBUM, "New Two"),
            dto(BaseItemKind.MUSIC_ALBUM, "New Three")
        )
        givenGenres(
            dto(BaseItemKind.MUSIC_GENRE, "Jazz"),
            dto(BaseItemKind.MUSIC_GENRE, "Folk"),
            dto(BaseItemKind.MUSIC_GENRE, "Rock")
        )
    }

    private fun givenRecentlyPlayed(vararg tracks: BaseItemDto) {
        coEvery {
            itemsApi.getItems(
                parentId = any(),
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                sortBy = any(),
                sortOrder = any(),
                fields = any(),
                limit = any()
            )
        } returns result(*tracks)
    }

    private fun givenAlbumsById(vararg albums: BaseItemDto) {
        coEvery {
            itemsApi.getItems(
                ids = any(),
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM)
            )
        } returns result(*albums)
    }

    private fun givenFavouriteArtists(vararg artists: BaseItemDto) {
        coEvery {
            artistsApi.getAlbumArtists(isFavorite = true, sortBy = any(), limit = any())
        } returns result(*artists)
    }

    private fun givenAnyArtists(vararg artists: BaseItemDto) {
        coEvery {
            artistsApi.getAlbumArtists(sortBy = any(), limit = any())
        } returns result(*artists)
    }

    private fun givenResumeItems(vararg items: BaseItemDto) {
        coEvery {
            itemsApi.getResumeItems(parentId = any(), mediaTypes = any(), limit = any())
        } returns result(*items)
    }

    private fun givenGenres(vararg genres: BaseItemDto) {
        coEvery {
            genresApi.getGenres(
                includeItemTypes = any(),
                sortBy = any(),
                sortOrder = any(),
                limit = any()
            )
        } returns result(*genres)
    }

    private fun givenLatestAlbums(vararg albums: BaseItemDto) {
        coEvery {
            userLibraryApi.getLatestMedia(includeItemTypes = any(), limit = any())
        } returns Response(content = albums.toList(), status = 200, headers = emptyMap())
    }

    private fun givenASavedQueue() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PLAYLIST_IDS_PREF, "${UUID.randomUUID()},${UUID.randomUUID()}")
            .putInt(PLAYLIST_INDEX_PREF, 1)
            .commit()
    }

    private fun givenAnInProgressBook() {
        givenResumeItems(
            dto(BaseItemKind.AUDIO_BOOK, "Half-read Book").copy(
                userData = userData(playedAt = LocalDateTime.of(2026, 1, 2, 0, 0))
            )
        )
    }

    /**
     * A factory that records what it was asked to build. Real MediaItems, so ids and group
     * titles are assertable, but no Jellyfin URL building or Android resource lookups.
     */
    private fun fakeFactory(): MediaItemFactory {
        val factory = mockk<MediaItemFactory>(relaxed = true)
        every {
            factory.create(any(), any(), any(), any(), any(), any(), any())
        } answers {
            val item = firstArg<BaseItemDto>()
            val prefix = arg<String>(5)
            tile(prefix + item.id, item.name.orEmpty(), secondArg())
        }
        every { factory.artistRadio(any(), any()) } answers {
            val item = firstArg<BaseItemDto>()
            tile(
                MediaItemFactory.RADIO_ARTIST_PREFIX + item.id,
                "${item.name} radio",
                secondArg()
            )
        }
        every { factory.genreMix(any(), any(), any()) } answers {
            tile(
                MediaItemFactory.SHUFFLE_GENRE_PREFIX + firstArg<UUID>(),
                "${secondArg<String>()} mix",
                thirdArg()
            )
        }
        every { factory.actionTile(any(), any(), any(), any(), any()) } answers {
            tile(firstArg(), secondArg(), arg(4))
        }
        every { factory.resumeQueue(any(), any(), any(), any()) } answers {
            tile(
                MediaItemFactory.RESUME_QUEUE,
                firstArg(),
                context.getString(R.string.continue_listening)
            )
        }
        return factory
    }

    private fun tile(id: String, title: String, group: String?): MediaItem {
        val extras = android.os.Bundle()
        if (group != null) {
            extras.putString(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                group
            )
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun MediaItem.groupTitle(): String? = mediaMetadata.extras
        ?.getString(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)

    private fun MediaItem.title(): String? = mediaMetadata.title?.toString()

    private fun dto(
        type: BaseItemKind,
        name: String,
        id: UUID = UUID.randomUUID()
    ) = BaseItemDto(id = id, type = type, name = name)

    private fun track(
        name: String,
        albumId: UUID? = UUID.randomUUID(),
        genres: List<NameGuidPair> = emptyList(),
        playedAt: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
    ) = dto(BaseItemKind.AUDIO, name).copy(
        albumId = albumId,
        genreItems = genres,
        userData = userData(playedAt)
    )

    private fun userData(playedAt: LocalDateTime?) = UserItemDataDto(
        rating = null,
        playedPercentage = null,
        unplayedItemCount = null,
        playbackPositionTicks = 1_000L,
        playCount = 1,
        isFavorite = false,
        likes = null,
        lastPlayedDate = playedAt,
        played = false,
        key = "",
        itemId = UUID.randomUUID()
    )

    private fun result(vararg items: BaseItemDto) = Response(
        content = BaseItemDtoQueryResult(
            items = items.toList(),
            totalRecordCount = items.size,
            startIndex = 0
        ),
        status = 200,
        headers = emptyMap()
    )
}
