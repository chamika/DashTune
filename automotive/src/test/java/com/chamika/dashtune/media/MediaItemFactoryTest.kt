package com.chamika.dashtune.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
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
}