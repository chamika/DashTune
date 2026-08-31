package com.chamika.dashtune.fake

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.time.LocalDateTime

/** Ticks per millisecond in Jellyfin's `RunTimeTicks` / `PlaybackPositionTicks`. */
const val TICKS_PER_MS = 10_000L

/** Where the server says the part-played chapter was left off. */
const val RESUME_POSITION_MS = 1_200L

/**
 * The library the E2E suite browses. Everything a test asserts against is exposed as a
 * named handle, so assertions read against the fixture rather than against magic strings.
 *
 * Sized so `RANDOM_ALBUMS` holds well over 60 albums — [com.chamika.dashtune.PaginatedBrowseTest]
 * needs more than 40 to make its three-page slice assertions meaningful, and used to skip
 * itself when a real server did not supply them.
 */
class DashTuneFixture {

    val library = FakeLibrary()

    val musicView = library.addView("Music", CollectionType.MUSIC)
    val booksView = library.addView("Audiobooks", CollectionType.BOOKS)

    /** First letters the generated names cycle through, so letter-bucket browsing has content. */
    val letters = listOf("A", "B", "C", "D", "E", "F")
    private val albumWords = listOf("Aurora", "Basalt", "Cinder", "Dawn", "Ember", "Fathom")
    private val artistWords = listOf("Ada Trio", "Bell Quartet", "Cove Duo", "Dune Band", "Echo Six", "Fern Group")

    val genres = listOf("Ambient", "Breaks", "Chill").map {
        library.add(FakeItem(name = it, kind = BaseItemKind.MUSIC_GENRE))
    }

    /** 24 album artists, 4 per letter. */
    val artists = (0 until 24).map { i ->
        library.add(
            FakeItem(
                name = "${artistWords[i % letters.size]} ${"%02d".format(i)}",
                kind = BaseItemKind.MUSIC_ARTIST,
            )
        )
    }

    /** 72 albums, 12 per letter, each parented to the music library so folder browse works. */
    val albums = (0 until 72).map { i ->
        val artist = artists[i % artists.size]
        val genre = genres[i % genres.size]
        library.add(
            FakeItem(
                name = "${albumWords[i % letters.size]} ${"%02d".format(i)}",
                kind = BaseItemKind.MUSIC_ALBUM,
                parentId = musicView.id,
                albumArtist = artist.name,
                albumArtistIds = listOf(artist.id),
                genreIds = listOf(genre.id),
                genreNames = listOf(genre.name),
                hasOwnImage = true,
                createdOrder = i,
            )
        )
    }

    /** Two tracks per album, so drilling into any album yields playable children. */
    val tracks = albums.flatMap { album ->
        (1..2).map { n ->
            library.add(
                FakeItem(
                    name = "${album.name} — Track $n",
                    kind = BaseItemKind.AUDIO,
                    parentId = album.id,
                    albumId = album.id,
                    albumArtist = album.albumArtist,
                    albumArtistIds = album.albumArtistIds,
                    genreIds = album.genreIds,
                    genreNames = album.genreNames,
                    indexNumber = n,
                    parentIndexNumber = 1,
                    runTimeTicks = FakeAudio.DURATION_MS * TICKS_PER_MS,
                )
            )
        }
    }

    /** The album the browse and playback tests drill into. */
    val featuredAlbum = albums.first()
    val featuredTracks = tracks.filter { it.parentId == featuredAlbum.id }

    val playlists = listOf("Road Trip" to 5, "Night Drive" to 9).map { (name, order) ->
        library.add(
            FakeItem(name = name, kind = BaseItemKind.PLAYLIST, createdOrder = order)
        )
    }

    /** Playlist entries are their own rows parented to the playlist, as Jellyfin models them. */
    val playlistTracks = playlists.flatMap { playlist ->
        (1..3).map { n ->
            library.add(
                FakeItem(
                    name = "${playlist.name} Pick $n",
                    kind = BaseItemKind.AUDIO,
                    parentId = playlist.id,
                    indexNumber = n,
                    runTimeTicks = FakeAudio.DURATION_MS * TICKS_PER_MS,
                )
            )
        }
    }

    // --- Favourites: deliberately one of each kind the favourites query asks for. ---
    val favouriteTrack = library.replace(tracks[3].copy(isFavorite = true))
    val favouriteAlbum = library.replace(albums[5].copy(isFavorite = true))
    val favouriteArtist = library.replace(artists[2].copy(isFavorite = true))

    // --- Audiobooks: collection -> book -> chapters, plus a standalone single-file book. ---
    val bookCollection = library.add(
        FakeItem(name = "Classics", kind = BaseItemKind.FOLDER, parentId = booksView.id)
    )

    val multiChapterBook = library.add(
        FakeItem(
            name = "The Long Voyage",
            kind = BaseItemKind.AUDIO_BOOK,
            parentId = bookCollection.id,
            albumArtist = "A Narrator",
            childCount = 3,
            runTimeTicks = FakeAudio.DURATION_MS * TICKS_PER_MS * 3,
        )
    )

    /**
     * Chapters carry one of each listening state, so the AAOS completion extras and the
     * server-side resume position both have something real to assert against.
     */
    val chapters = listOf(
        // Finished.
        chapter(1, played = true, playedPercentage = 100.0),
        // Part-way through — this is the chapter a resume should land on.
        chapter(
            2,
            positionMs = RESUME_POSITION_MS,
            playedPercentage = 40.0,
            lastPlayedAt = LocalDateTime.of(2026, 8, 30, 21, 15),
        ),
        // Untouched.
        chapter(3),
    )

    private fun chapter(
        number: Int,
        played: Boolean = false,
        positionMs: Long = 0,
        playedPercentage: Double? = null,
        lastPlayedAt: LocalDateTime? = null,
    ) = library.add(
        FakeItem(
            name = "Chapter $number",
            kind = BaseItemKind.AUDIO,
            parentId = multiChapterBook.id,
            albumArtist = "A Narrator",
            indexNumber = number,
            parentIndexNumber = 1,
            runTimeTicks = FakeAudio.DURATION_MS * TICKS_PER_MS,
            played = played,
            playbackPositionTicks = positionMs * TICKS_PER_MS,
            playedPercentage = playedPercentage,
            lastPlayedAt = lastPlayedAt,
        )
    )

    val standaloneBook = library.add(
        FakeItem(
            name = "A Short Tale",
            kind = BaseItemKind.AUDIO_BOOK,
            parentId = booksView.id,
            albumArtist = "A Narrator",
            childCount = 0,
            runTimeTicks = FakeAudio.DURATION_MS * TICKS_PER_MS,
        )
    )
}
