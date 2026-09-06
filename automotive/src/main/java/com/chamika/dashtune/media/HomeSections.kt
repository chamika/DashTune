package com.chamika.dashtune.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_IDS_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_TITLE_PREF
import com.chamika.dashtune.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder

/**
 * Builds the Home tab: one browse node whose children carry section headers, so the AAOS
 * Media Center renders them as a stack of titled rows.
 *
 * Two rules shape everything here. Every tile must resolve to a queue that plays for a long
 * time, because a tile that ends after three minutes is a worse deal than the browse tree it
 * replaced. And the whole node has to arrive inside the browse timeout, so sections are
 * fetched in parallel and any one that fails or is slow is dropped rather than failing Home.
 */
@OptIn(UnstableApi::class)
class HomeSections(
    private val context: Context,
    private val api: ApiClient,
    private val itemFactory: MediaItemFactory
) {

    companion object {
        /**
         * Per-section budget. Deliberately shorter than the tree's own retrying fetchers:
         * the browse call gives up at 8s, so a section that has not answered by now is
         * better dropped than allowed to take Home down with it.
         */
        const val SECTION_TIMEOUT_MS = 5_000L

        /** How many recently played tracks to pull for the play-history derived sections. */
        const val RECENT_PLAYS_LIMIT = 100

        private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    }

    /**
     * @param tilesPerRow how many tiles fit one row on this head unit; sections are trimmed
     * to it so none of them wraps.
     */
    suspend fun build(tilesPerRow: Int): List<MediaItem> = coroutineScope {
        val gridCount = (tilesPerRow - 1).coerceAtLeast(1)

        // Started first: three sections derive from it, and it is the slowest query.
        val recentTracks = async { section("recent plays") { recentlyPlayedTracks() } ?: emptyList() }

        val continueListening = async { section("continue listening") { continueListening() } }
        val newForYou = async { section("new for you") { newForYou(gridCount) } }

        val madeForYou = async {
            section("made for you") { madeForYou(gridCount, recentTracks.await()) }
        }
        val jumpBackIn = async {
            section("jump back in") { jumpBackIn(tilesPerRow, recentTracks.await()) }
        }
        val mixes = async {
            section("mixes") { mixes(gridCount, recentTracks.await()) }
        }

        buildList {
            addAll(continueListening.await().orEmpty())
            addAll(madeForYou.await().orEmpty())
            addAll(newForYou.await().orEmpty())
            addAll(jumpBackIn.await().orEmpty())
            addAll(mixes.await().orEmpty())
        }
    }

    /**
     * Runs one section under its own timeout. A section that fails leaves the rest of Home
     * intact — a partial Home is far better than an error screen in a moving car.
     */
    private suspend fun <T> section(name: String, block: suspend () -> T): T? = try {
        val result = withTimeoutOrNull(SECTION_TIMEOUT_MS) { block() }
        if (result == null) Log.w(LOG_TAG, "Home section '$name' timed out")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Home section '$name' failed", e)
        null
    }

    // --- Section 1: Continue listening ---------------------------------------------------

    /**
     * The saved music queue first, then the in-progress audiobook. Either row is omitted
     * when there is nothing to resume, and the section disappears entirely when both are.
     */
    private suspend fun continueListening(): List<MediaItem> = buildList {
        resumeQueueTile()?.let { add(it) }
        continueBookTile()?.let { add(it) }
    }

    private fun resumeQueueTile(): MediaItem? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val ids = prefs.getString(PLAYLIST_IDS_PREF, "")
            .orEmpty()
            .split(",")
            .filter { it.isNotBlank() }
        if (ids.isEmpty()) return null

        val index = prefs.getInt(PLAYLIST_INDEX_PREF, 0).coerceIn(0, ids.lastIndex)
        val title = prefs.getString(PLAYLIST_TITLE_PREF, null)
            ?: context.getString(R.string.resume_queue_fallback_title)

        // Art comes from the track that is up next, so the tile looks like what it plays.
        val artworkUri = runCatching { itemFactory.artUriFor(ids[index]) }.getOrNull()

        return itemFactory.resumeQueue(title, index, ids.size, artworkUri)
    }

    private suspend fun continueBookTile(): MediaItem? {
        val bookViews = viewIds(CollectionType.BOOKS)
        if (bookViews.isEmpty()) return null

        val inProgress = bookViews
            .flatMap { viewId ->
                api.itemsApi.getResumeItems(
                    parentId = viewId,
                    mediaTypes = listOf(MediaType.AUDIO),
                    limit = 1
                ).content.items
            }
            .filter { it.userData?.lastPlayedDate != null }
            .maxByOrNull { it.userData!!.lastPlayedDate!! }
            ?: return null

        return runCatching {
            itemFactory.create(
                inProgress,
                group = context.getString(R.string.continue_listening),
                parent = inProgress.parentId?.toString(),
                isAudiobook = true,
                singleItemStyle = LIST
            )
        }.getOrNull()
    }

    // --- Section 2: Made for you ---------------------------------------------------------

    /**
     * Artist radios plus a favourites shuffle. Favourite artists come first because they are
     * the strongest signal, then the artists actually being played, then anything at all —
     * a new user with no history still gets a full row.
     */
    private suspend fun madeForYou(count: Int, recentTracks: List<BaseItemDto>): List<MediaItem> {
        val group = context.getString(R.string.made_for_you)

        val seeds = LinkedHashMap<UUID, BaseItemDto>()

        runCatching {
            api.artistsApi.getAlbumArtists(
                isFavorite = true,
                sortBy = listOf(ItemSortBy.RANDOM),
                limit = count
            ).content.items
        }.getOrDefault(emptyList()).forEach { seeds.putIfAbsent(it.id, it) }

        if (seeds.size < count) {
            val playedArtistIds = recentTracks
                .flatMap { it.albumArtists.orEmpty() }
                .map { it.id }
                .distinct()
                .filterNot { seeds.containsKey(it) }
                .take(count - seeds.size)
            if (playedArtistIds.isNotEmpty()) {
                runCatching {
                    api.itemsApi.getItems(
                        ids = playedArtistIds,
                        includeItemTypes = listOf(BaseItemKind.MUSIC_ARTIST)
                    ).content.items
                }.getOrDefault(emptyList()).forEach { seeds.putIfAbsent(it.id, it) }
            }
        }

        if (seeds.size < count) {
            runCatching {
                api.artistsApi.getAlbumArtists(
                    sortBy = listOf(ItemSortBy.RANDOM),
                    limit = count
                ).content.items
            }.getOrDefault(emptyList()).forEach { seeds.putIfAbsent(it.id, it) }
        }

        val radios = seeds.values.take(count).map { itemFactory.artistRadio(it, group) }

        return radios + itemFactory.actionTile(
            mediaId = MediaItemFactory.SHUFFLE_FAVOURITES,
            title = context.getString(R.string.shuffle_favourites),
            subtitle = context.getString(R.string.shuffled),
            art = "art_favourites",
            group = group
        )
    }

    // --- Section 3: New for you ----------------------------------------------------------

    /**
     * Recently added albums, prefixed so a tap continues into the rest of the newest tracks
     * rather than stopping when the album ends, plus a shuffle of the newest tracks.
     */
    private suspend fun newForYou(count: Int): List<MediaItem> {
        val group = context.getString(R.string.new_for_you)

        // Trimmed again after the fetch: a server that ignores `limit` would otherwise wrap
        // this section onto a second row and push everything below it off the first screen.
        val albums = api.userLibraryApi.getLatestMedia(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            limit = count
        ).content.take(count).mapNotNull {
            runCatching {
                itemFactory.create(
                    it,
                    group = group,
                    idPrefix = MediaItemFactory.NEW_ALBUM_PREFIX,
                    singleItemStyle = GRID
                )
            }.getOrNull()
        }

        return albums + itemFactory.actionTile(
            mediaId = MediaItemFactory.SHUFFLE_NEW,
            title = context.getString(R.string.shuffle_new),
            subtitle = context.getString(R.string.latest_tracks_subtitle, MediaItemResolver.LATEST_TRACKS_LIMIT),
            art = "art_shuffle",
            group = group
        )
    }

    // --- Section 4: Jump back in ---------------------------------------------------------

    /**
     * Albums derived from the play history. Album-level DatePlayed is not reliable across
     * Jellyfin versions, so the albums are read off the recently played tracks instead.
     */
    private suspend fun jumpBackIn(count: Int, recentTracks: List<BaseItemDto>): List<MediaItem> {
        val group = context.getString(R.string.jump_back_in)

        val albumIds = recentTracks.mapNotNull { it.albumId }.distinct().take(count)
        if (albumIds.isEmpty()) return emptyList()

        val albums = api.itemsApi.getItems(
            ids = albumIds,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM)
        ).content.items.associateBy { it.id }

        // getItems does not preserve the order of `ids`, so restore the recency order.
        return albumIds.mapNotNull { albums[it] }.mapNotNull {
            runCatching {
                itemFactory.create(it, group = group, singleItemStyle = GRID)
            }.getOrNull()
        }
    }

    // --- Section 5: Mixes ----------------------------------------------------------------

    /** The genres played most, then a whole-library shuffle. */
    private suspend fun mixes(count: Int, recentTracks: List<BaseItemDto>): List<MediaItem> {
        val group = context.getString(R.string.mixes)

        // Rank by how often each genre shows up in what was actually played.
        val played = recentTracks.flatMap { it.genreItems.orEmpty() }
        val ranked = LinkedHashMap<UUID, String>()
        played
            .groupingBy { it.id }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(count)
            .forEach { entry ->
                played.firstOrNull { it.id == entry.key }?.name
                    ?.let { ranked[entry.key] = it }
            }

        // A new user has no history, so fall back to whatever genres the library has.
        if (ranked.size < count) {
            runCatching {
                api.genresApi.getGenres(
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    limit = count
                ).content.items
            }.getOrDefault(emptyList()).forEach { genre ->
                genre.name?.let { ranked.putIfAbsent(genre.id, it) }
            }
        }

        val genreMixes = ranked.entries.take(count).map { (id, name) ->
            itemFactory.genreMix(id, name, group)
        }

        return genreMixes + itemFactory.actionTile(
            mediaId = MediaItemFactory.SHUFFLE_LIBRARY,
            title = context.getString(R.string.shuffle_library),
            subtitle = context.getString(R.string.shuffled),
            art = "art_shuffle",
            group = group
        )
    }

    // --- Shared queries ------------------------------------------------------------------

    /**
     * Recently played tracks, newest first. Scoped to the music libraries so audiobook
     * chapters (also AUDIO items) never leak into the music sections.
     */
    private suspend fun recentlyPlayedTracks(): List<BaseItemDto> {
        val musicViews = viewIds(CollectionType.MUSIC)
        if (musicViews.isEmpty()) return emptyList()

        return musicViews
            .flatMap { viewId ->
                api.itemsApi.getItems(
                    parentId = viewId,
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    sortBy = listOf(ItemSortBy.DATE_PLAYED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    fields = listOf(ItemFields.GENRES),
                    limit = RECENT_PLAYS_LIMIT
                ).content.items
            }
            .filter { it.userData?.lastPlayedDate != null }
            .sortedByDescending { it.userData!!.lastPlayedDate!! }
    }

    private suspend fun viewIds(type: CollectionType): List<UUID> =
        api.userViewsApi.getUserViews().content.items
            .filter { it.collectionType == type }
            .map { it.id }
}
