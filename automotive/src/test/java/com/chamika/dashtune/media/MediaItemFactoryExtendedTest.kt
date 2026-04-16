package com.chamika.dashtune.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
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
class MediaItemFactoryExtendedTest {

    private lateinit var factory: MediaItemFactory
    private lateinit var context: Context
    private lateinit var jellyfinApi: ApiClient
    private lateinit var mockUniversalAudioApi: UniversalAudioApi

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        jellyfinApi = mockk(relaxed = true)

        mockkObject(AlbumArtContentProvider.Companion)
        every { AlbumArtContentProvider.mapUri(any()) } returns Uri.parse("content://com.chamika.dashtune/test")

        mockUniversalAudioApi = mockk<UniversalAudioApi>(relaxed = true)
        every { jellyfinApi.universalAudioApi } returns mockUniversalAudioApi
        every { mockUniversalAudioApi.getUniversalAudioStreamUrl(any(), any(), any(), any(), any(), any(), any()) } returns "http://localhost:8096/Audio/test-id/universal"

        factory = MediaItemFactory(context, jellyfinApi, 256)
    }

    private fun userItemData(
        played: Boolean = false,
        playbackPositionTicks: Long = 0L,
        playedPercentage: Double? = null,
        isFavorite: Boolean = false,
    ): UserItemDataDto = UserItemDataDto(
        rating = null,
        playedPercentage = playedPercentage,
        unplayedItemCount = null,
        playbackPositionTicks = playbackPositionTicks,
        playCount = 0,
        isFavorite = isFavorite,
        likes = null,
        lastPlayedDate = null,
        played = played,
        key = "",
        itemId = UUID.randomUUID(),
    )

    // --- streamingUri tests ---

    @Test
    fun `streamingUri returns URL from universalAudioApi`() {
        val result = factory.streamingUri("12345678-1234-1234-1234-123456789abc")

        assertNotNull(result)
        assertEquals("http://localhost:8096/Audio/test-id/universal", result)
    }

    @Test
    fun `streamingUri passes null bitrate when preference is Direct stream`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("bitrate", "Direct stream")
            .commit()

        factory.streamingUri("12345678-1234-1234-1234-123456789abc")

        io.mockk.verify {
            mockUniversalAudioApi.getUniversalAudioStreamUrl(
                any(),
                container = any(),
                audioBitRate = isNull(),
                maxStreamingBitrate = isNull(),
                transcodingContainer = any(),
                audioCodec = any(),
            )
        }
    }

    @Test
    fun `streamingUri passes integer bitrate when preference is numeric`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("bitrate", "256")
            .commit()

        factory.streamingUri("12345678-1234-1234-1234-123456789abc")

        io.mockk.verify {
            mockUniversalAudioApi.getUniversalAudioStreamUrl(
                any(),
                container = any(),
                audioBitRate = eq(256),
                maxStreamingBitrate = eq(256),
                transcodingContainer = any(),
                audioCodec = any(),
            )
        }
    }

    // --- Constants uniqueness tests ---

    @Test
    fun `all category ID constants are unique`() {
        val ids = setOf(ROOT_ID, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS, BOOKS)
        assertEquals(6, ids.size)
    }

    @Test
    fun `IS_AUDIOBOOK_KEY has expected value`() {
        assertEquals("is_audiobook", IS_AUDIOBOOK_KEY)
    }

    @Test
    fun `PARENT_KEY has expected value`() {
        assertEquals("PARENT_KEY", PARENT_KEY)
    }

    // --- create dispatch tests ---

    @Test
    fun `create dispatches MUSIC_ARTIST to forArtist`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MUSIC_ARTIST, name = "Artist")
        val item = factory.create(dto)

        assertEquals(MediaMetadata.MEDIA_TYPE_ARTIST, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create dispatches MUSIC_ALBUM to forAlbum`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MUSIC_ALBUM, name = "Album")
        val item = factory.create(dto)

        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create dispatches AUDIO_BOOK to forAudiobook`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.AUDIO_BOOK, name = "Book")
        val item = factory.create(dto)

        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    @Test
    fun `create dispatches FOLDER to forFolder`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.FOLDER, name = "Folder")
        val item = factory.create(dto)

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    @Test
    fun `create dispatches PLAYLIST to forPlaylist`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.PLAYLIST, name = "Playlist")
        val item = factory.create(dto)

        assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create dispatches AUDIO to forTrack`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.AUDIO, name = "Track")
        val item = factory.create(dto)

        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
    }

    // --- Folder-specific tests ---

    @Test
    fun `folder has MEDIA_TYPE_FOLDER_ALBUMS`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.FOLDER, name = "AudioBooks")
        val item = factory.create(dto)

        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, item.mediaMetadata.mediaType)
    }

    @Test
    fun `folder with group sets group title extra`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.FOLDER, name = "Folder")
        val item = factory.create(dto, group = "Books")

        assertEquals("Books", item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
    }

    @Test
    fun `folder has content style list item extras`() {
        val dto = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.FOLDER, name = "Folder")
        val item = factory.create(dto)

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    // --- Audio track with favourite tests ---

    @Test
    fun `audio track with favourite userData gets heart rating`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Fav Track",
            userData = userItemData(isFavorite = true)
        )
        val item = factory.create(dto)

        val rating = item.mediaMetadata.userRating as? androidx.media3.common.HeartRating
        assertNotNull(rating)
        assertTrue(rating!!.isHeart)
    }

    @Test
    fun `audio track without favourite userData gets no heart`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Regular Track",
            userData = userItemData(isFavorite = false)
        )
        val item = factory.create(dto)

        val rating = item.mediaMetadata.userRating as? androidx.media3.common.HeartRating
        assertNotNull(rating)
        assertFalse(rating!!.isHeart)
    }

    // --- Track duration tests ---

    @Test
    fun `audio track with runTimeTicks sets durationMs`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Track",
            runTimeTicks = 3_600_000_0000L // 1 hour in ticks
        )
        val item = factory.create(dto)

        // runTimeTicks / 10_000 = durationMs
        assertEquals(360000L, item.mediaMetadata.durationMs)
    }

    @Test
    fun `audio track without runTimeTicks has null durationMs`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Track",
            runTimeTicks = null
        )
        val item = factory.create(dto)

        assertNull(item.mediaMetadata.durationMs)
    }

    // --- Audiobook-specific edge cases ---

    @Test
    fun `audiobook with childCount null is not browsable`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Single File Book",
            childCount = null
        )
        val item = factory.create(dto)

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `audiobook with childCount 1 is browsable`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "One Chapter Book",
            childCount = 1
        )
        val item = factory.create(dto)

        assertTrue(item.mediaMetadata.isBrowsable == true)
    }

    @Test
    fun `audiobook has streaming URI regardless of childCount`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Book"
        )
        val item = factory.create(dto)

        assertNotNull(item.localConfiguration)
    }

    @Test
    fun `audiobook parent parameter is set in extras`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Book"
        )
        val item = factory.create(dto, parent = "folder-1")

        assertEquals("folder-1", item.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    // --- Album specific tests ---

    @Test
    fun `album does not have streaming URI`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.MUSIC_ALBUM,
            name = "Album"
        )
        val item = factory.create(dto)

        assertNull(item.localConfiguration)
    }

    @Test
    fun `playlist does not have streaming URI`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.PLAYLIST,
            name = "Playlist"
        )
        val item = factory.create(dto)

        assertNull(item.localConfiguration)
    }

    // --- Category node content style extras tests ---

    @Test
    fun `latestAlbums has grid content style for playable items`() {
        val item = factory.latestAlbums()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }

    @Test
    fun `latestAlbums has grid content style for browsable items`() {
        val item = factory.latestAlbums()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    @Test
    fun `favourites has list content style for playable items`() {
        val item = factory.favourites()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }

    @Test
    fun `playlists has grid content style for playable items`() {
        val item = factory.playlists()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }

    @Test
    fun `books has grid content style for both playable and browsable items`() {
        val item = factory.books()

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    // --- Completion status edge cases ---

    @Test
    fun `audiobook with 100 percent playedPercentage but played=false is partially played`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Almost Done Book",
            userData = userItemData(
                played = false,
                playbackPositionTicks = 1000L,
                playedPercentage = 99.9
            )
        )
        val item = factory.create(dto)

        assertEquals(
            1, // PARTIALLY_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
    }

    @Test
    fun `audiobook with zero position ticks and not played is not played`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO_BOOK,
            name = "Fresh Book",
            userData = userItemData(played = false, playbackPositionTicks = 0L, playedPercentage = null)
        )
        val item = factory.create(dto)

        assertEquals(
            0, // NOT_PLAYED
            item.mediaMetadata.extras?.getInt("android.media.extra.COMPLETION_STATUS")
        )
    }

    // --- Artist content style tests ---

    @Test
    fun `artist has grid content style for playable and browsable items`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.MUSIC_ARTIST,
            name = "Artist"
        )
        val item = factory.create(dto)

        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    // --- Name preservation tests ---

    @Test
    fun `create preserves item name as title`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "My Special Song"
        )
        val item = factory.create(dto)

        assertEquals("My Special Song", item.mediaMetadata.title.toString())
    }

    @Test
    fun `create preserves albumArtist`() {
        val dto = BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.AUDIO,
            name = "Track",
            albumArtist = "The Great Artist"
        )
        val item = factory.create(dto)

        assertEquals("The Great Artist", item.mediaMetadata.albumArtist.toString())
    }
}
