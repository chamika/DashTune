package com.chamika.dashtune

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chamika.dashtune.media.MediaItemFactory.Companion.ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ARTISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.FOLDERS
import com.chamika.dashtune.media.MediaItemFactory.Companion.GENRES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FOLDER_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_GENRE_PREFIX
import com.chamika.dashtune.support.DashTuneE2eRule
import com.chamika.dashtune.support.childIdsOf
import com.chamika.dashtune.support.childTitlesOf
import com.chamika.dashtune.support.childrenOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the browse tree the way an AAOS head unit does — a real MediaBrowser bound to the
 * real MediaLibraryService — with only the Jellyfin server simulated.
 *
 * All nine categories are enabled. The settings screen caps the user at four, but
 * `JellyfinMediaTree.getActiveCategoryIds` applies no cap, so enabling them all lets one
 * class cover every category's query path.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeE2eTest {

    @get:Rule
    val dashTune = DashTuneE2eRule(
        categories = setOf(
            "latest", "favourites", "books", "playlists",
            "random", "folders", "artists", "albums", "genres",
        )
    )

    private val browser get() = dashTune.browser
    private val fixture get() = dashTune.fixture

    @Test
    fun rootExposesEveryConfiguredCategoryInCanonicalOrder() {
        assertEquals(
            listOf(
                LATEST_ALBUMS, FAVOURITES, BOOKS, PLAYLISTS,
                RANDOM_ALBUMS, FOLDERS, ARTISTS, ALBUMS, GENRES,
            ),
            browser.childIdsOf(ROOT_ID)
        )
    }

    @Test
    fun rootFollowsTheBrowseCategoriesPreference() {
        PreferenceManager.getDefaultSharedPreferences(dashTune.context).edit()
            .putStringSet("browse_categories", setOf("genres", "latest"))
            .commit()

        // Root children are served straight from the tree, never from Room, so the new
        // preference takes effect on the next browse without an invalidation.
        assertEquals(listOf(LATEST_ALBUMS, GENRES), browser.childIdsOf(ROOT_ID))
    }

    @Test
    fun latestListsTheAlbumLibrary() {
        val titles = browser.childTitlesOf(LATEST_ALBUMS)

        assertEquals(fixture.albums.size, titles.size)
        assertEquals(fixture.albums.map { it.name }, titles)
    }

    @Test
    fun albumDrillDownYieldsOrderedPlayableTracks() {
        val tracks = browser.childrenOf(fixture.featuredAlbum.id.toString())

        assertEquals(fixture.featuredTracks.map { it.name }, tracks.map { it.mediaMetadata.title })
        assertTrue("tracks must be playable", tracks.all { it.mediaMetadata.isPlayable == true })
        assertTrue("tracks must not be browsable", tracks.none { it.mediaMetadata.isBrowsable == true })
        assertEquals(
            "RunTimeTicks should convert to milliseconds",
            listOf(3_000L, 3_000L),
            tracks.map { it.mediaMetadata.durationMs }
        )
    }

    @Test
    fun favouritesReturnsEveryFavouritedKind() {
        val ids = browser.childIdsOf(FAVOURITES)

        assertEquals(
            setOf(
                fixture.favouriteTrack.id.toString(),
                fixture.favouriteAlbum.id.toString(),
                fixture.favouriteArtist.id.toString(),
            ),
            ids.toSet()
        )
    }

    @Test
    fun artistsExposesAnAlphabetIndexWhoseLettersQueryByPrefix() {
        val buckets = browser.childIdsOf(ARTISTS)

        assertEquals("# plus A-Z", 27, buckets.size)
        assertEquals("LETTER:$ARTISTS:#", buckets.first())

        val aArtists = browser.childTitlesOf("LETTER:$ARTISTS:A")
        assertEquals(
            fixture.artists.filter { it.name.startsWith("A") }.map { it.name },
            aArtists
        )
        assertTrue(
            "the letter bucket must reach /Artists/AlbumArtists with nameStartsWith",
            dashTune.server.requestsTo("/Artists/AlbumArtists")
                .any { it.query("nameStartsWith") == "A" }
        )
    }

    @Test
    fun albumsLetterBucketReturnsOnlyThatLetter() {
        val titles = browser.childTitlesOf("LETTER:$ALBUMS:B")

        assertEquals(fixture.albums.count { it.name.startsWith("B") }, titles.size)
        assertTrue("every album must start with B", titles.all { it.startsWith("B") })
    }

    @Test
    fun genresListAlphabeticallyAndDrillIntoShufflePlusAlbums() {
        val genreIds = browser.childIdsOf(GENRES)
        assertEquals(
            fixture.genres.sortedBy { it.name }.map { it.id.toString() },
            genreIds
        )

        val firstGenre = fixture.genres.sortedBy { it.name }.first()
        val children = browser.childIdsOf(firstGenre.id.toString())

        assertEquals(
            "a genre leads with its shuffle entry",
            "$SHUFFLE_GENRE_PREFIX${firstGenre.id}",
            children.first()
        )
        assertEquals(
            fixture.albums.count { firstGenre.id in it.genreIds },
            children.size - 1
        )
    }

    @Test
    fun playlistsAreNewestFirstAndKeepServerOrderInside() {
        // sortBy=DateCreated, sortOrder=Descending — "Night Drive" was created later.
        assertEquals(listOf("Night Drive", "Road Trip"), browser.childTitlesOf(PLAYLISTS))

        val roadTrip = fixture.playlists.first { it.name == "Road Trip" }
        assertEquals(
            fixture.playlistTracks.filter { it.parentId == roadTrip.id }.map { it.name },
            browser.childTitlesOf(roadTrip.id.toString())
        )
    }

    @Test
    fun foldersDescendIntoTheSingleMusicLibraryAndOfferShuffleAll() {
        val children = browser.childIdsOf(FOLDERS)

        assertEquals(
            "a lone music library is descended into, led by Shuffle all",
            "$SHUFFLE_FOLDER_PREFIX${fixture.musicView.id}",
            children.first()
        )
        assertEquals(fixture.albums.size + 1, children.size)
    }

    @Test
    fun albumArtResolvesThroughTheContentProviderToRealBytes() {
        val track = browser.childrenOf(fixture.featuredAlbum.id.toString()).first()
        val artUri = requireNotNull(track.mediaMetadata.artworkUri) { "track had no artwork uri" }

        assertEquals("art must be proxied for the system UI", "content", artUri.scheme)

        val bytes = dashTune.context.contentResolver.openInputStream(artUri).use { it!!.readBytes() }
        assertTrue("expected a PNG body, got ${bytes.size} bytes", bytes.size > 8)
        assertEquals(
            "PNG magic",
            listOf(0x89, 0x50, 0x4E, 0x47),
            bytes.take(4).map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun everyApiCallCarriesTheAccessToken() {
        browser.childIdsOf(LATEST_ALBUMS)

        val apiCalls = dashTune.server.requests.filter { !it.path.contains("/Images/") }
        assertTrue("expected the browse to have hit the server", apiCalls.isNotEmpty())
        assertTrue(
            "every API call must carry the token from onLogin(): $apiCalls",
            apiCalls.all { it.authorization?.contains("Token=\"${DashTuneE2eRule.TEST_TOKEN}\"") == true }
        )
    }
}
