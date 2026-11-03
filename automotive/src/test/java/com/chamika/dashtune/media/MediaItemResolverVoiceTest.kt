package com.chamika.dashtune.media

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Play X on DashTune". Media3 hands a voice request over as one item with no id and the
 * spoken query in its request metadata; before this was handled, the empty id reached
 * `toUUID()` and every voice request failed with "Media unavailable".
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemResolverVoiceTest {

    private lateinit var repository: MediaRepository
    private lateinit var resolver: MediaItemResolver

    @Before
    fun setUp() {
        repository = mockk()
        resolver = MediaItemResolver(repository)
        coEvery { repository.getSongMixTracks(any()) } returns emptyList()
    }

    private fun hit(id: String, type: Int, title: String, artist: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setUri("https://jf.example/Audio/$id/universal")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setAlbumArtist(artist)
                .setMediaType(type)
                .setIsPlayable(type != MediaMetadata.MEDIA_TYPE_ARTIST)
                .setIsBrowsable(type != MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun voice(query: String, extras: Bundle? = null) = MediaItem.Builder()
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()
        )
        .build()

    private fun extras(vararg pairs: Pair<String, String>) = Bundle().apply {
        pairs.forEach { (k, v) -> putString(k, v) }
    }

    private val queen = hit("artist-queen", MediaMetadata.MEDIA_TYPE_ARTIST, "Queen")
    private val operaAlbum = hit("album-opera", MediaMetadata.MEDIA_TYPE_ALBUM, "A Night at the Opera", "Queen")
    private val rhapsody = hit("track-rhapsody", MediaMetadata.MEDIA_TYPE_MUSIC, "Bohemian Rhapsody", "Queen")
    private val rhapsodyCover = hit("track-cover", MediaMetadata.MEDIA_TYPE_MUSIC, "Bohemian Rhapsody", "Panic! at the Disco")

    @Test
    fun `a voice request is recognised by its empty id and query`() {
        assertEquals("queen", MediaItemResolver.voiceQuery(listOf(voice("queen"))))
        assertEquals("", MediaItemResolver.voiceQuery(listOf(voice(""))))
        assertNull(MediaItemResolver.voiceQuery(listOf(rhapsody)))
        assertNull(MediaItemResolver.voiceQuery(listOf(voice("a"), voice("b"))))
    }

    @Test
    fun `a song asked for by title and artist plays that song first, then a mix of it`() = runTest {
        coEvery { repository.search("Bohemian Rhapsody") } returns listOf(queen, operaAlbum, rhapsodyCover, rhapsody)
        val mix = hit("track-mix", MediaMetadata.MEDIA_TYPE_MUSIC, "Somebody to Love", "Queen")
        coEvery { repository.getSongMixTracks("track-rhapsody") } returns listOf(rhapsody, mix)

        val queue = resolver.resolveVoiceSearch(
            "bohemian rhapsody by queen",
            extras(
                MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
                MediaStore.EXTRA_MEDIA_TITLE to "Bohemian Rhapsody",
                MediaStore.EXTRA_MEDIA_ARTIST to "Queen",
            )
        )

        assertEquals(listOf("track-rhapsody", "track-mix"), queue.map { it.mediaId })
    }

    @Test
    fun `an artist asked for plays that artist's radio`() = runTest {
        coEvery { repository.search("Queen") } returns listOf(queen, operaAlbum)
        val radio = listOf(rhapsody)
        coEvery { repository.getArtistRadioTracks("artist-queen") } returns radio

        val queue = resolver.resolveVoiceSearch(
            "queen",
            extras(
                MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
                MediaStore.EXTRA_MEDIA_ARTIST to "Queen",
            )
        )

        assertEquals(radio, queue)
    }

    @Test
    fun `without a focus an exact title beats the artist the search lists first`() = runTest {
        coEvery { repository.search("A Night at the Opera") } returns listOf(queen, operaAlbum)
        coEvery { repository.getItem("album-opera") } returns operaAlbum
        coEvery { repository.getChildren("album-opera") } returns listOf(rhapsody)
        coEvery { repository.getItem("track-rhapsody") } returns rhapsody

        val queue = resolver.resolveVoiceSearch("A Night at the Opera", null)

        assertEquals(listOf("track-rhapsody"), queue.map { it.mediaId })
    }

    @Test
    fun `nothing named shuffles the library`() = runTest {
        coEvery { repository.getLibraryShuffled() } returns listOf(rhapsody)

        assertEquals(listOf(rhapsody), resolver.resolveVoiceSearch("", null))
    }

    @Test
    fun `no match gives nothing to play`() = runTest {
        coEvery { repository.search("zzz") } returns emptyList()

        assertEquals(emptyList<MediaItem>(), resolver.resolveVoiceSearch("zzz", null))
    }

    @Test
    fun `resolveMediaItems turns a voice item into its queue`() = runTest {
        coEvery { repository.search("Queen") } returns listOf(queen)
        coEvery { repository.getArtistRadioTracks("artist-queen") } returns listOf(rhapsody)

        val queue = resolver.resolveMediaItems(listOf(voice("Queen")))

        assertEquals(listOf("track-rhapsody"), queue.map { it.mediaId })
    }

    @Test
    fun `a voice item is never looked up as a child of some parent`() = runTest {
        assertFalse(resolver.isSingleItemWithParent(listOf(voice("queen"))))
        coVerify(exactly = 0) { repository.getItem(any()) }
    }
}
