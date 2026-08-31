package com.chamika.dashtune

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.DashTuneSessionCallback.Companion.DOWNLOAD_COMMAND
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.DOWNLOADS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.childrenOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** True when the row advertises the "Download for offline" browse action. */
private fun MediaItem.offersDownload(): Boolean =
    DOWNLOAD_COMMAND in mediaMetadata.supportedCommands

private fun MediaItem.contentStyle(key: String): Int? =
    mediaMetadata.extras?.getInt(key)

private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

/**
 * Every category that can hold a downloadable container, plus the ids of the rows inside it
 * that are expected to offer the action. Shared by both states so neither can drift.
 */
private val ALL_CATEGORIES = listOf(
    "latest", "favourites", "books", "playlists",
    "random", "folders", "artists", "albums", "genres", "downloads",
)

/**
 * The "Download for offline" action with the Downloads category switched **on**.
 *
 * The action is only useful where the AAOS host can draw it: its layouts put a
 * `browse_item_actions_container` in list rows and nothing equivalent in grid rows, so these
 * tests assert the list content style alongside the action itself. A row that advertises the
 * command inside a grid is invisible but still tappable, which is worse than not offering it.
 */
@RunWith(AndroidJUnit4::class)
class DownloadActionE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(categories = ALL_CATEGORIES.toSet())

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    // --- Rows that should offer the action, one per category ---

    @Test
    fun latestAlbumsOfferTheDownloadAction() {
        val albums = browser.childrenOf(LATEST_ALBUMS)

        assertTrue("latest must not be empty", albums.isNotEmpty())
        assertTrue("every album row offers the action", albums.all { it.offersDownload() })
    }

    @Test
    fun randomAlbumsOfferTheDownloadAction() {
        val albums = browser.childrenOf(RANDOM_ALBUMS)

        assertTrue("random must not be empty", albums.isNotEmpty())
        assertTrue("every album row offers the action", albums.all { it.offersDownload() })
    }

    @Test
    fun playlistsOfferTheDownloadAction() {
        val playlists = browser.childrenOf(PLAYLISTS)

        assertEquals(fixture.playlists.size, playlists.size)
        assertTrue("every playlist row offers the action", playlists.all { it.offersDownload() })
    }

    @Test
    fun audiobooksOfferTheDownloadActionAtBothLevels() {
        val topLevel = browser.childrenOf(BOOKS)
        val standalone = topLevel.single { it.mediaId == fixture.standaloneBook.id.toString() }
        val collection = topLevel.single { it.mediaId == fixture.bookCollection.id.toString() }

        assertTrue("a standalone book is downloadable", standalone.offersDownload())
        assertFalse("a book collection is a folder, not a container to pin", collection.offersDownload())

        val book = browser.childrenOf(fixture.bookCollection.id.toString()).single()
        assertTrue("a book inside a collection is downloadable", book.offersDownload())
    }

    @Test
    fun favouriteAlbumOffersTheDownloadActionButTracksAndArtistsDoNot() {
        val favourites = browser.childrenOf(FAVOURITES).associateBy { it.mediaId }

        assertTrue(
            "a favourited album is a container to pin",
            favourites.getValue(fixture.favouriteAlbum.id.toString()).offersDownload()
        )
        assertFalse(
            "a single track is not a container",
            favourites.getValue(fixture.favouriteTrack.id.toString()).offersDownload()
        )
        assertFalse(
            "an artist is browsable, not a container to pin",
            favourites.getValue(fixture.favouriteArtist.id.toString()).offersDownload()
        )
    }

    @Test
    fun albumsFromALetterBucketOfferTheDownloadAction() {
        val albums = browser.childrenOf("LETTER:$ALBUMS:B")

        assertTrue("the B bucket must not be empty", albums.isNotEmpty())
        assertTrue("every album row offers the action", albums.all { it.offersDownload() })
    }

    @Test
    fun albumsUnderAnArtistOfferTheDownloadAction() {
        // Artists -> letter bucket -> artist -> albums. The albums three levels down were the
        // case that regressed: forArtist hardcoded a grid, leaving them no room for the action.
        val artist = browser.childrenOf("LETTER:$ARTISTS:A").first()
        val albums = browser.childrenOf(artist.mediaId)

        assertFalse("the artist row itself is not downloadable", artist.offersDownload())
        assertTrue("an artist must have albums to make this meaningful", albums.isNotEmpty())
        assertTrue("every album under an artist offers the action", albums.all { it.offersDownload() })
        assertEquals(
            "the artist must serve its albums as a list, or the action cannot be drawn",
            LIST,
            artist.contentStyle(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }

    @Test
    fun albumsUnderAGenreOfferTheDownloadActionButTheShuffleRowDoesNot() {
        val genre = browser.childrenOf(GENRES).first()
        val children = browser.childrenOf(genre.mediaId)
        val shuffle = children.first()
        val albums = children.drop(1)

        assertFalse("a genre row is not downloadable", genre.offersDownload())
        assertFalse("the shuffle row has nothing to pin", shuffle.offersDownload())
        assertTrue("a genre must have albums", albums.isNotEmpty())
        assertTrue("every album under a genre offers the action", albums.all { it.offersDownload() })
    }

    @Test
    fun albumsInAFolderOfferTheDownloadActionButTheShuffleRowDoesNot() {
        val children = browser.childrenOf(FOLDERS)
        val shuffle = children.first()
        val albums = children.drop(1)

        assertFalse("the shuffle row has nothing to pin", shuffle.offersDownload())
        assertTrue("every album in a folder offers the action", albums.all { it.offersDownload() })
    }

    // --- Rows that must never offer it, whatever the setting ---

    @Test
    fun tracksNeverOfferTheDownloadAction() {
        val tracks = browser.childrenOf(fixture.featuredAlbum.id.toString())

        assertTrue("the album must have tracks", tracks.isNotEmpty())
        assertTrue("a track is not a container to pin", tracks.none { it.offersDownload() })
    }

    @Test
    fun chaptersNeverOfferTheDownloadAction() {
        // Browse parent-before-child: the tree caches an audiobook's isAudiobook flag on the
        // way down, and asking for chapters cold resolves the book as a plain item instead.
        browser.childrenOf(BOOKS)
        browser.childrenOf(fixture.bookCollection.id.toString())
        val chapters = browser.childrenOf(fixture.multiChapterBook.id.toString())

        assertEquals(fixture.chapters.size, chapters.size)
        assertTrue("a chapter is not a container to pin", chapters.none { it.offersDownload() })
    }

    @Test
    fun categoryNodesAndLetterBucketsNeverOfferTheDownloadAction() {
        val categories = browser.childrenOf(ROOT_ID)
        assertEquals(ALL_CATEGORIES.size, categories.size)
        assertTrue("a category is browse scaffolding", categories.none { it.offersDownload() })

        val buckets = browser.childrenOf(ARTISTS)
        assertTrue("a letter bucket is browse scaffolding", buckets.none { it.offersDownload() })
    }

    @Test
    fun downloadsStartsEmptyAndIsServedAsAList() {
        assertTrue("nothing is pinned yet", browser.childrenOf(DOWNLOADS).isEmpty())

        val node = browser.childrenOf(ROOT_ID).single { it.mediaId == DOWNLOADS }
        assertEquals(
            "Downloads must be a list so its remove action can be drawn",
            LIST,
            node.contentStyle(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    // --- The layout the action depends on ---

    @Test
    fun everyCategoryHoldingDownloadableRowsIsServedAsAList() {
        val listStyled = browser.childrenOf(ROOT_ID).filter { it.mediaId != FAVOURITES }

        listStyled.forEach { category ->
            assertEquals(
                "${category.mediaId} must serve playable rows as a list",
                LIST,
                category.contentStyle(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
            )
        }
    }

    @Test
    fun cachedRowsStillOfferTheDownloadActionAndAListLayout() {
        // The first browse populates Room; the second is served from it. Cached rows replay
        // stored extras, so this is the path where a stale grid style or a dropped action
        // would surface.
        browser.childrenOf(LATEST_ALBUMS)
        val cached = browser.childrenOf(LATEST_ALBUMS)

        assertTrue("cached albums must still offer the action", cached.all { it.offersDownload() })

        val artist = browser.childrenOf("LETTER:$ARTISTS:A").first()
        browser.childrenOf(artist.mediaId)
        val cachedArtist = browser.childrenOf("LETTER:$ARTISTS:A").first()

        assertEquals(
            "a cached artist must not fall back to the grid it was stored with",
            LIST,
            cachedArtist.contentStyle(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }
}

/**
 * The same surfaces with the Downloads category switched **off**.
 *
 * Nothing may offer the action. This matters most in the corners that are list-styled whatever
 * the setting — Folders, Genres, letter buckets, audiobooks — because those are the only places
 * an ungated action had somewhere to draw itself, which is what made the leak look arbitrary.
 */
@RunWith(AndroidJUnit4::class)
class DownloadActionDisabledE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(
        categories = ALL_CATEGORIES.toSet() - "downloads"
    )

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    @Test
    fun downloadsCategoryIsAbsentFromTheRoot() {
        assertTrue(
            "Downloads must not appear when it is not selected",
            browser.childrenOf(ROOT_ID).none { it.mediaId == DOWNLOADS }
        )
    }

    @Test
    fun albumsAndPlaylistsDoNotOfferTheDownloadAction() {
        assertTrue(browser.childrenOf(LATEST_ALBUMS).none { it.offersDownload() })
        assertTrue(browser.childrenOf(RANDOM_ALBUMS).none { it.offersDownload() })
        assertTrue(browser.childrenOf(PLAYLISTS).none { it.offersDownload() })
        assertTrue(browser.childrenOf("LETTER:$ALBUMS:B").none { it.offersDownload() })
    }

    @Test
    fun audiobooksDoNotOfferTheDownloadAction() {
        // Audiobook nodes are list-styled whatever the setting, so an ungated action would be
        // visible here rather than merely advertised.
        assertTrue(browser.childrenOf(BOOKS).none { it.offersDownload() })
        assertTrue(
            browser.childrenOf(fixture.bookCollection.id.toString()).none { it.offersDownload() }
        )
    }

    @Test
    fun listStyledFolderAndGenreRowsDoNotOfferTheDownloadAction() {
        assertTrue(browser.childrenOf(FOLDERS).none { it.offersDownload() })

        val genre = browser.childrenOf(GENRES).first()
        assertTrue(browser.childrenOf(genre.mediaId).none { it.offersDownload() })
    }

    @Test
    fun albumsUnderAnArtistDoNotOfferTheDownloadActionAndKeepTheGrid() {
        val artist = browser.childrenOf("LETTER:$ARTISTS:A").first()

        assertEquals(
            "with downloads off an artist keeps its artwork grid",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            artist.contentStyle(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
        assertTrue(browser.childrenOf(artist.mediaId).none { it.offersDownload() })
    }

    @Test
    fun favouritesDoNotOfferTheDownloadAction() {
        assertTrue(browser.childrenOf(FAVOURITES).none { it.offersDownload() })
    }

    @Test
    fun cachedRowsDoNotOfferTheDownloadActionEither() {
        browser.childrenOf(LATEST_ALBUMS)
        val cached = browser.childrenOf(LATEST_ALBUMS)

        assertTrue("the cached path is gated too", cached.none { it.offersDownload() })
    }
}
