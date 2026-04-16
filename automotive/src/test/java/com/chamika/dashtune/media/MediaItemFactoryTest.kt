package com.chamika.dashtune.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.api.operations.UniversalAudioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryTest {

    private lateinit var factory: MediaItemFactory
    private lateinit var context: Context
    private lateinit var jellyfinApi: ApiClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        jellyfinApi = mockk(relaxed = true)

        // Mock AlbumArtContentProvider.mapUri to avoid the URI processing issue
        mockkObject(AlbumArtContentProvider.Companion)
        every { AlbumArtContentProvider.mapUri(any()) } returns Uri.parse("content://com.chamika.dashtune/test")

        // Mock the universalAudioApi extension property
        val mockUniversalAudioApi = mockk<UniversalAudioApi>(relaxed = true)
        every { jellyfinApi.universalAudioApi } returns mockUniversalAudioApi
        every { mockUniversalAudioApi.getUniversalAudioStreamUrl(any(), any(), any(), any(), any(), any(), any()) } returns "http://localhost:8096/Audio/test-id/universal"

        factory = MediaItemFactory(context, jellyfinApi, 256)
    }

    /**
     * Builds a [UserItemDataDto] with all required fields specified.
     * The Jellyfin SDK 1.8.x does not provide default values so every
     * field must be provided explicitly.
     */
    private fun userItemData(
        played: Boolean = false,
        playbackPositionTicks: Long? = null,
        playedPercentage: Double? = null,
        isFavorite: Boolean = false,
    ): UserItemDataDto = UserItemDataDto(
        rating = null,
        playedPercentage = playedPercentage,
        unplayedItemCount = null,
        playbackPositionTicks = playbackPositionTicks,
        playCount = null,
        isFavorite = isFavorite,
        likes = null,
        lastPlayedDate = null,
        played = played,
        key = "",
        itemId = null,
    )

    private fun baseItem(
        type: BaseItemKind,
        name: String = "Test Item",
        childCount: Int? = null
    ): BaseItemDto {
        return BaseItemDto(
            id = UUID.randomUUID(),
            type = type,
            name = name,
            childCount = childCount,
        )
    }

    @Test
    fun `create music artist is browsable and not playable`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ARTIST))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ARTIST, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create music album is playable and not browsable`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ALBUM))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, item.mediaMetadata.mediaType)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `create playlist is playable and not browsable and has no URI`() {
        val item = factory.create(baseItem(BaseItemKind.PLAYLIST))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, item.mediaMetadata.mediaType)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `create audio track is playable and has streaming URI`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertNotNull(item.localConfiguration)
    }

    @Test
    fun `create audiobook has audiobook flag and streaming URI`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK))

        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, item.mediaMetadata.mediaType)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertNotNull(item.localConfiguration)
    }

    @Test
    fun `create audiobook with children is browsable`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK, childCount = 5))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `create audiobook without children is not browsable`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK, childCount = 0))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `create folder is browsable and not playable`() {
        val item = factory.create(baseItem(BaseItemKind.FOLDER))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `create unsupported type throws exception`() {
        factory.create(baseItem(BaseItemKind.MOVIE))
    }

    @Test
    fun `create audio track with audiobook flag preserves flag`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO), isAudiobook = true)

        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create sets mediaId from item id`() {
        val dto = baseItem(BaseItemKind.AUDIO)
        val item = factory.create(dto)

        assertEquals(dto.id.toString(), item.mediaId)
    }

    // --- Category node creation tests ---

    @Test
    fun `rootNode is browsable and not playable with ROOT_ID`() {
        val item = factory.rootNode()

        assertEquals(ROOT_ID, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, item.mediaMetadata.mediaType)
    }

    @Test
    fun `latestAlbums is browsable with LATEST_ALBUMS id`() {
        val item = factory.latestAlbums()

        assertEquals(LATEST_ALBUMS, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, item.mediaMetadata.mediaType)
    }

    @Test
    fun `randomAlbums is browsable with RANDOM_ALBUMS id`() {
        val item = factory.randomAlbums()

        assertEquals(RANDOM_ALBUMS, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `books is browsable with BOOKS id`() {
        val item = factory.books()

        assertEquals(BOOKS, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `favourites is browsable with FAVOURITES id`() {
        val item = factory.favourites()

        assertEquals(FAVOURITES, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, item.mediaMetadata.mediaType)
    }

    @Test
    fun `playlists is browsable with PLAYLISTS id`() {
        val item = factory.playlists()

        assertEquals(PLAYLISTS, item.mediaId)
        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, item.mediaMetadata.mediaType)
    }

    // --- Group extras tests ---

    @Test
    fun `create artist with group sets content style group title extra`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ARTIST), group = "Artists")

        assertEquals("Artists", item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    @Test
    fun `create album with group sets content style group title extra`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ALBUM), group = "Albums")

        assertEquals("Albums", item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    @Test
    fun `create playlist with group sets content style group title extra`() {
        val item = factory.create(baseItem(BaseItemKind.PLAYLIST), group = "Playlists")

        assertEquals("Playlists", item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    @Test
    fun `create audio track with group sets content style group title extra`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO), group = "Tracks")

        assertEquals("Tracks", item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    @Test
    fun `create artist without group does not set group title extra`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ARTIST))

        assertNull(item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    // --- PARENT_KEY propagation tests ---

    @Test
    fun `create audio track with parent sets PARENT_KEY in extras`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO), parent = "album-123")

        assertEquals("album-123", item.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    @Test
    fun `create audio track without parent does not set PARENT_KEY`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO))

        assertNull(item.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    @Test
    fun `create audiobook with parent sets PARENT_KEY in extras`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK), parent = "books-folder")

        assertEquals("books-folder", item.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    // --- Completion status tests (via forAudiobook and forTrack with isAudiobook=true) ---

    @Test
    fun `audiobook fully played sets completion status to fully played`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Played Book",
            userData = userItemData(played = true)
        )
        val item = factory.create(dto)

        assertEquals(
            2, // COMPLETION_STATUS_FULLY_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
        assertEquals(
            1.0,
            item.mediaMetadata.extras?.getDouble("android.media.extra.COMPLETION_PERCENTAGE") ?: 0.0,
            0.001
        )
    }

    @Test
    fun `audiobook partially played sets completion status to partially played`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "In-Progress Book",
            userData = userItemData(
                playbackPositionTicks = 50_000L,
                playedPercentage = 40.0
            )
        )
        val item = factory.create(dto)

        assertEquals(
            1, // COMPLETION_STATUS_PARTIALLY_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
        assertEquals(
            0.4,
            item.mediaMetadata.extras?.getDouble("android.media.extra.COMPLETION_PERCENTAGE") ?: 0.0,
            0.001
        )
    }

    @Test
    fun `audiobook not started sets completion status to not played`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Unplayed Book",
            userData = userItemData(played = false, playbackPositionTicks = 0L)
        )
        val item = factory.create(dto)

        assertEquals(
            0, // COMPLETION_STATUS_NOT_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
    }

    @Test
    fun `audiobook with no userData does not set completion status`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "No UserData Book",
            userData = null
        )
        val item = factory.create(dto)

        assertFalse(
            item.mediaMetadata.extras?.containsKey("android.media.extra.COMPLETION_STATUS") == true
        )
    }

    @Test
    fun `audio track with isAudiobook flag and partial play sets completion status`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Chapter 1",
            userData = userItemData(
                playbackPositionTicks = 10_000L,
                playedPercentage = 25.0
            )
        )
        val item = factory.create(dto, isAudiobook = true)

        assertEquals(
            1, // COMPLETION_STATUS_PARTIALLY_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
    }
}