package com.chamika.dashtune.media

import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.NEW_ALBUM_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.RADIO_ARTIST_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_LIBRARY
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_NEW
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_FOLDER_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.SHUFFLE_GENRE_PREFIX
import com.chamika.dashtune.media.MediaItemFactory.Companion.isHomeActionId
import com.chamika.dashtune.media.MediaItemFactory.Companion.isShuffleId

class MediaItemResolver(
    private val repository: MediaRepository
) {

    companion object {
        /** Size of the "Shuffle new" queue and of the tail appended after a new album. */
        const val LATEST_TRACKS_LIMIT = 50

        /**
         * The spoken query when [mediaItems] is a voice "play X" request, which Media3 hands over
         * as one item with no id and the query in its request metadata. Blank for "play
         * DashTune" with nothing named. Null for anything else.
         */
        fun voiceQuery(mediaItems: List<MediaItem>): String? =
            mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery

        private fun isVoiceRequest(item: MediaItem): Boolean =
            item.mediaId.isEmpty() && item.requestMetadata.searchQuery != null
    }

    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()

        mediaItems.forEach {
            if (isVoiceRequest(it)) {
                playlist.addAll(resolveVoiceSearch(it.requestMetadata.searchQuery!!, it.requestMetadata.extras))
                return@forEach
            }

            // Home tiles resolve to whole queues rather than to a Jellyfin item, so they are
            // handled before the normal item lookup — there is nothing on the server to fetch.
            if (it.mediaId.startsWith(RADIO_ARTIST_PREFIX)) {
                playlist.addAll(
                    repository.getArtistRadioTracks(it.mediaId.removePrefix(RADIO_ARTIST_PREFIX))
                )
                return@forEach
            }

            if (it.mediaId == SHUFFLE_FAVOURITES) {
                playlist.addAll(repository.getFavouriteTracksShuffled())
                return@forEach
            }

            if (it.mediaId == SHUFFLE_NEW) {
                playlist.addAll(repository.getLatestTracks(LATEST_TRACKS_LIMIT).shuffled())
                return@forEach
            }

            if (it.mediaId == SHUFFLE_LIBRARY) {
                playlist.addAll(repository.getLibraryShuffled())
                return@forEach
            }

            // A "New for you" album plays in full, then keeps going through the rest of the
            // recently added tracks, so the queue outlasts the album's own 40 minutes.
            if (it.mediaId.startsWith(NEW_ALBUM_PREFIX)) {
                playlist.addAll(newAlbumQueue(it.mediaId.removePrefix(NEW_ALBUM_PREFIX)))
                return@forEach
            }

            if (it.mediaId.startsWith(SHUFFLE_FOLDER_PREFIX)) {
                playlist.addAll(
                    repository.getShuffledTracks(it.mediaId.removePrefix(SHUFFLE_FOLDER_PREFIX))
                )
                return@forEach
            }

            if (it.mediaId.startsWith(SHUFFLE_GENRE_PREFIX)) {
                playlist.addAll(
                    repository.getShuffledGenreTracks(it.mediaId.removePrefix(SHUFFLE_GENRE_PREFIX))
                )
                return@forEach
            }

            val item = repository.getItem(it.mediaId)
            val isAudiobook = item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
            val isExpandable = (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                    item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST) &&
                    (!isAudiobook || item.mediaMetadata.isBrowsable == true)

            if (isExpandable) {
                val children = resolveMediaItems(repository.getChildren(item.mediaId))
                if (children.isNotEmpty()) {
                    children.forEach(playlist::add)
                } else if (item.localConfiguration?.uri != null) {
                    // Single-file audiobook with no children — play directly
                    Log.i(LOG_TAG, "Playing single-file audiobook directly: ${item.mediaMetadata.title}")
                    playlist.add(item)
                } else {
                    Log.w(LOG_TAG, "Empty children and no URI for ${item.mediaMetadata.title} (type=${item.mediaMetadata.mediaType}, playable=${item.mediaMetadata.isPlayable})")
                }
            } else if (item.mediaMetadata.isPlayable == true) {
                playlist.add(item)
            } else {
                Log.e(LOG_TAG, "Cannot add media ${item.mediaMetadata.title}")
            }
        }

        return playlist
    }

    /**
     * The album's own tracks in order, then the remaining recently added tracks. Duplicates
     * are dropped so a track from the album is never queued twice.
     */
    private suspend fun newAlbumQueue(albumId: String): List<MediaItem> {
        val albumTracks = resolveMediaItems(repository.getChildren(albumId))
        val albumTrackIds = albumTracks.map { it.mediaId }.toSet()
        val continuation = repository.getLatestTracks(LATEST_TRACKS_LIMIT)
            .filterNot { it.mediaId in albumTrackIds }
        return albumTracks + continuation
    }

    /**
     * The queue for a voice request: the library shuffled when nothing was named, else the
     * best match for what was said (see [pickVoiceTarget]) played the way a tap would play it.
     */
    suspend fun resolveVoiceSearch(query: String, extras: Bundle?): List<MediaItem> {
        if (query.isBlank()) return repository.getLibraryShuffled()
        val target = pickVoiceTarget(query, extras) ?: return emptyList()
        return queueForVoiceTarget(target)
    }

    /** An artist plays its radio, a track carries on into a mix of it, a container plays in full. */
    suspend fun queueForVoiceTarget(target: MediaItem): List<MediaItem> =
        when (target.mediaMetadata.mediaType) {
            MediaMetadata.MEDIA_TYPE_ARTIST -> repository.getArtistRadioTracks(target.mediaId)
            MediaMetadata.MEDIA_TYPE_MUSIC -> listOf(target) +
                repository.getSongMixTracks(target.mediaId).filterNot { it.mediaId == target.mediaId }
            else -> resolveMediaItems(listOf(target))
        }

    /**
     * What a voice query most likely meant. The assistant says what kind of thing was asked for
     * ([MediaStore.EXTRA_MEDIA_FOCUS]) and the names it heard; without those the search, which
     * lists artists first, would hand an artist back for "play Bohemian Rhapsody". An exact
     * title match wins over a partial one, and for a song the artist that was heard breaks ties.
     */
    suspend fun pickVoiceTarget(query: String, extras: Bundle?): MediaItem? {
        val (wantedType, heardName) = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ->
                MediaMetadata.MEDIA_TYPE_ARTIST to extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE ->
                MediaMetadata.MEDIA_TYPE_ALBUM to extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE ->
                MediaMetadata.MEDIA_TYPE_MUSIC to extras.getString(MediaStore.EXTRA_MEDIA_TITLE)
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE ->
                MediaMetadata.MEDIA_TYPE_PLAYLIST to extras.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)
            else -> null to null
        }
        val term = heardName?.takeIf { it.isNotBlank() } ?: query
        val hits = repository.search(term)
        val candidates = hits.filter { it.mediaMetadata.mediaType == wantedType }.ifEmpty { hits }

        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            ?.takeIf { it.isNotBlank() && wantedType != MediaMetadata.MEDIA_TYPE_ARTIST }
        fun MediaItem.titled() = mediaMetadata.title?.toString().equals(term, ignoreCase = true)
        fun MediaItem.by() = artist == null ||
            mediaMetadata.albumArtist?.toString()?.contains(artist, ignoreCase = true) == true

        return candidates.firstOrNull { it.titled() && it.by() }
            ?: candidates.firstOrNull { it.titled() }
            ?: candidates.firstOrNull { it.by() }
            ?: candidates.firstOrNull()
    }

    suspend fun isSingleItemWithParent(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val mediaId = mediaItems[0].mediaId
        // A voice request has no id at all; resolveMediaItems turns its query into a queue.
        if (mediaId.isEmpty()) return false
        // Home action tiles and prefixed albums have no Jellyfin item behind their id, so
        // there is no parent to expand — resolveMediaItems handles them itself.
        if (isHomeActionId(mediaId) || mediaId.startsWith(NEW_ALBUM_PREFIX)) return false
        // A shuffle pseudo-item may be cached as a normal folder child in Room, which
        // would make the DB-parent fallback below misidentify it as "one track from a
        // folder" and play only that folder's immediate children non-recursively.
        if (isShuffleId(mediaId)) return false
        val item = repository.getItem(mediaId)
        // An album/playlist is itself an expandable container: tapping it should play its
        // own tracks, not expand the whole parent folder. In folder browse these have a
        // (non-static) folder as parent, so without this they'd be treated as "one item
        // within a parent" — resolveMediaItems would expand every sibling album and the
        // selected album's id would vanish from the queue, starting playback on the wrong
        // track. Audiobooks keep the parent-expand path so chapter resume still works.
        val isAudiobook = item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
        val mediaType = item.mediaMetadata.mediaType
        if (!isAudiobook && (mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST)
        ) {
            return false
        }
        if (item.mediaMetadata.extras?.containsKey(PARENT_KEY) == true) return true
        // Fall back to DB parent relationship (handles stale cache)
        return repository.getContentParentId(mediaId) != null
    }

    suspend fun expandSingleItem(item: MediaItem): List<MediaItem> {
        val parentId = repository.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)
            ?: repository.getContentParentId(item.mediaId)
            ?: return listOf(item)
        val children = repository.getChildren(parentId)
            // Folder-browse and genre children carry an injected "Shuffle all" pseudo-item
            // at the front. It's a browse-only affordance, not a real sibling — resolving it would
            // splice a whole random shuffle of the folder's descendants into the queue and
            // start playback on the wrong track. Drop it so tapping a song plays that song
            // and its actual siblings in order.
            .filterNot { isShuffleId(it.mediaId) }
        return resolveMediaItems(children)
    }
}
