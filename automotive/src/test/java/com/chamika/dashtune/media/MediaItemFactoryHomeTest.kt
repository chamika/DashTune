package com.chamika.dashtune.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.R
import com.chamika.dashtune.media.MediaItemFactory.Companion.HOME
import com.chamika.dashtune.media.MediaItemFactory.Companion.NEW_ALBUM_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RADIO_ARTIST_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.RESUME_QUEUE
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_GENRE_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_NEW
import com.chamika.dashtune.media.MediaItemFactory.Companion.isHomeActionId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.operations.UniversalAudioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * The Home tiles. What matters here is the media id (the resolver dispatches on it), the
 * section header, and the per-item style that lets one browse node mix progress rows with
 * album grids.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryHomeTest {

    private lateinit var factory: MediaItemFactory
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val jellyfinApi = mockk<ApiClient>(relaxed = true)

        mockkObject(AlbumArtContentProvider.Companion)
        every { AlbumArtContentProvider.mapUri(any()) } returns
            Uri.parse("content://com.chamika.dashtune/test")

        val universalAudioApi = mockk<UniversalAudioApi>(relaxed = true)
        every { jellyfinApi.universalAudioApi } returns universalAudioApi
        every {
            universalAudioApi.getUniversalAudioStreamUrl(
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns "http://localhost:8096/Audio/test-id/universal"

        factory = MediaItemFactory(context, jellyfinApi, 256)
    }

    private fun dto(type: BaseItemKind, name: String, id: UUID = UUID.randomUUID()) =
        BaseItemDto(id = id, type = type, name = name)

    private fun singleItemStyle(item: androidx.media3.common.MediaItem): Int? =
        item.mediaMetadata.extras
            ?.takeIf { it.containsKey(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM) }
            ?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM)

    private fun groupTitle(item: androidx.media3.common.MediaItem): String? =
        item.mediaMetadata.extras
            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)

    @Test
    fun `the home category is a browsable root tab`() {
        val home = factory.home()

        assertEquals(HOME, home.mediaId)
        assertEquals(context.getString(R.string.home), home.mediaMetadata.title)
        assertTrue(home.mediaMetadata.isBrowsable == true)
        assertFalse(home.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `an artist radio carries the prefix the resolver dispatches on`() {
        val artist = dto(BaseItemKind.MUSIC_ARTIST, "Nina Simone")

        val radio = factory.artistRadio(artist, "Made for you")

        assertEquals(RADIO_ARTIST_PREFIX + artist.id, radio.mediaId)
        assertEquals("Nina Simone radio", radio.mediaMetadata.title)
        assertEquals("Made for you", groupTitle(radio))
        assertTrue(radio.mediaMetadata.isPlayable == true)
        assertFalse(radio.mediaMetadata.isBrowsable == true)
    }

    @Test
    fun `a genre mix reuses the existing genre shuffle id`() {
        val genreId = UUID.randomUUID()

        val mix = factory.genreMix(genreId, "Jazz", "Mixes")

        assertEquals(SHUFFLE_GENRE_PREFIX + genreId, mix.mediaId)
        assertEquals("Jazz mix", mix.mediaMetadata.title)
        assertTrue(mix.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `action tiles use the category grid style so the icon is drawn with margins`() {
        val tile = factory.actionTile(
            SHUFFLE_LIBRARY, "Shuffle library", "Shuffled", "ic_shuffle", "Mixes"
        )

        assertEquals(SHUFFLE_LIBRARY, tile.mediaId)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
            singleItemStyle(tile)
        )
        assertEquals("Mixes", groupTitle(tile))
        assertTrue(tile.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `the resume tile reports its place in the saved queue`() {
        val tile = factory.resumeQueue("Road trip", trackIndex = 6, trackCount = 42, artworkUri = null)

        assertEquals(RESUME_QUEUE, tile.mediaId)
        assertEquals("Road trip", tile.mediaMetadata.title)
        // One-based for the driver, zero-based in the code.
        assertEquals("Track 7 of 42", tile.mediaMetadata.subtitle)
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, singleItemStyle(tile))
    }

    @Test
    fun `the resume tile falls back to an icon when the queue has no art`() {
        val tile = factory.resumeQueue("Road trip", 0, 3, artworkUri = null)

        assertTrue(tile.mediaMetadata.artworkUri.toString().endsWith("ic_playlists"))
    }

    @Test
    fun `a new-for-you album is prefixed while a normal album is not`() {
        val album = dto(BaseItemKind.MUSIC_ALBUM, "Fragments")

        val plain = factory.create(album)
        val homeTile = factory.create(album, idPrefix = NEW_ALBUM_PREFIX)

        assertEquals(album.id.toString(), plain.mediaId)
        assertEquals(NEW_ALBUM_PREFIX + album.id, homeTile.mediaId)
    }

    @Test
    fun `single item style is only set when asked for`() {
        val album = dto(BaseItemKind.MUSIC_ALBUM, "Fragments")

        assertEquals(null, singleItemStyle(factory.create(album)))
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            singleItemStyle(
                factory.create(
                    album,
                    singleItemStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                )
            )
        )
    }

    @Test
    fun `every synthetic home tile is recognised as a home action`() {
        listOf(
            RESUME_QUEUE,
            SHUFFLE_FAVOURITES,
            SHUFFLE_NEW,
            SHUFFLE_LIBRARY,
            RADIO_ARTIST_PREFIX + UUID.randomUUID()
        ).forEach { assertTrue(it, isHomeActionId(it)) }
    }

    @Test
    fun `a real jellyfin id is not mistaken for a home action`() {
        assertFalse(isHomeActionId(UUID.randomUUID().toString()))
        // A prefixed album is expanded by its own branch, not the action branch.
        assertFalse(isHomeActionId(NEW_ALBUM_PREFIX + UUID.randomUUID()))
    }
}
