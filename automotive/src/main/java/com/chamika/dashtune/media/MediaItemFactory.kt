package com.chamika.dashtune.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.preference.PreferenceManager
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.DashTuneSessionCallback.Companion.DOWNLOAD_COMMAND
import com.chamika.dashtune.DashTuneSessionCallback.Companion.REMOVE_DOWNLOAD_COMMAND
import com.chamika.dashtune.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@OptIn(UnstableApi::class)
class MediaItemFactory(
    private val context: Context,
    private val jellyfinApi: ApiClient,
    private val artSize: Int
) {

    companion object {
        const val ROOT_ID = "ROOT_ID"
        const val LATEST_ALBUMS = "LATEST_ALBUMS_ID"
        const val RANDOM_ALBUMS = "RANDOM_ALBUMS_ID"
        const val FAVOURITES = "FAVOURITES_ID"
        const val PLAYLISTS = "PLAYLISTS_ID"
        const val BOOKS = "BOOKS_ID"
        const val FOLDERS = "FOLDERS_ID"
        const val DOWNLOADS = "DOWNLOADS_ID"
        const val ARTISTS = "ARTISTS_ID"
        const val ALBUMS = "ALBUMS_ID"
        const val GENRES = "GENRES_ID"
        const val SHUFFLE_FOLDER_PREFIX = "SHUFFLE_FOLDER:"
        // A genre shuffle can't reuse the folder one: folders shuffle by parentId,
        // genres by genreIds, so the two need distinguishable media ids.
        const val SHUFFLE_GENRE_PREFIX = "SHUFFLE_GENRE:"
        const val LETTER_BUCKET_PREFIX = "LETTER:"
        const val PARENT_KEY = "PARENT_KEY"
        const val IS_AUDIOBOOK_KEY = "is_audiobook"
        const val IS_FOLDER_KEY = "is_folder_browse"

        /** Alphabet index shown under Artists and Albums; "#" collects non-alphabetic names. */
        val LETTERS: List<String> = listOf("#") + ('A'..'Z').map(Char::toString)

        fun isShuffleId(id: String): Boolean =
            id.startsWith(SHUFFLE_FOLDER_PREFIX) || id.startsWith(SHUFFLE_GENRE_PREFIX)

        fun letterBucketId(categoryId: String, letter: String): String =
            "$LETTER_BUCKET_PREFIX$categoryId:$letter"

        /** Splits a [letterBucketId] back into its category id and letter, or null if malformed. */
        fun parseLetterBucketId(id: String): Pair<String, String>? {
            val body = id.removePrefix(LETTER_BUCKET_PREFIX)
            val separator = body.indexOf(':')
            if (separator <= 0 || separator == body.lastIndex) return null
            return body.substring(0, separator) to body.substring(separator + 1)
        }

        private const val EXTRA_COMPLETION_STATUS = "android.media.extra.COMPLETION_STATUS"
        private const val EXTRA_COMPLETION_PERCENTAGE = "android.media.extra.COMPLETION_PERCENTAGE"
        private const val COMPLETION_STATUS_NOT_PLAYED = 0
        private const val COMPLETION_STATUS_PARTIALLY_PLAYED = 1
        private const val COMPLETION_STATUS_FULLY_PLAYED = 2
    }

    fun rootNode(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("Root")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    fun latestAlbums(): MediaItem {
        return albumCategory(LATEST_ALBUMS, "Latest", "ic_schedule")
    }

    fun randomAlbums(): MediaItem {
        return albumCategory(RANDOM_ALBUMS, "Random", "ic_casino")
    }

    fun favourites(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle("Favourites")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/ic_star_filled".toUri())
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(FAVOURITES)
            .setMediaMetadata(metadata)
            .build()
    }

    fun playlists(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle("Playlists")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/ic_playlists".toUri())
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            .build()

        return MediaItem.Builder()
            .setMediaId(PLAYLISTS)
            .setMediaMetadata(metadata)
            .build()
    }

    fun books(): MediaItem {
        return albumCategory(BOOKS, "Books", "ic_book")
    }

    fun folders(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.folders))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/ic_folder".toUri())
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(FOLDERS)
            .setMediaMetadata(metadata)
            .build()
    }

    fun downloads(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.downloads))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/ic_download".toUri())
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(DOWNLOADS)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * A row under the Downloads node for a pinned container. Carries the "remove download" browse
     * action instead of "download for offline". Folder-typed containers (e.g. the Favourites
     * pseudo-container) are browsable; albums and playlists stay playable so tapping plays them.
     */
    fun downloadedContainer(
        id: String,
        title: String,
        subtitle: String?,
        artUri: String?,
        mediaType: Int
    ): MediaItem {
        val isFolder = mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_MIXED ||
            mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS ||
            mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumArtist(subtitle)
            .setIsBrowsable(isFolder)
            .setIsPlayable(!isFolder)
            .setMediaType(mediaType)
            .setSupportedCommands(listOf(REMOVE_DOWNLOAD_COMMAND))

        artworkUriFor(id, artUri)?.let { metadataBuilder.setArtworkUri(it) }

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * Resolves a persisted artwork string (an original http(s) URL or an app URI) back to a
     * displayable Uri, falling back to the item's own primary image when nothing was stored.
     */
    private fun artworkUriFor(id: String, storedArtUri: String?): Uri? {
        if (storedArtUri != null) {
            val uri = storedArtUri.toUri()
            return if (uri.scheme == "http" || uri.scheme == "https") {
                AlbumArtContentProvider.mapUri(uri)
            } else {
                uri
            }
        }
        return try {
            artUri(id.toUUID())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun artists(): MediaItem {
        return albumCategory(
            ARTISTS,
            context.getString(R.string.artists),
            "ic_artist",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
        )
    }

    fun albums(): MediaItem {
        return albumCategory(ALBUMS, context.getString(R.string.albums), "ic_album")
    }

    fun genres(): MediaItem {
        return albumCategory(
            GENRES,
            context.getString(R.string.genres),
            "ic_genre",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_GENRES,
            // Genres rarely carry artwork, so a grid of blank tiles reads worse than a list.
            contentStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
    }

    /**
     * One letter of the Artists/Albums alphabet index. Built entirely client side so it
     * still resolves when the tree cache is cold and the network is down.
     */
    fun letterBucket(categoryId: String, letter: String): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val mediaType = if (categoryId == ARTISTS) {
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
        } else {
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(letter)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(letterBucketId(categoryId, letter))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun albumCategory(
        id: String,
        label: String,
        icon: String,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        contentStyle: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    ): MediaItem {
        val extras = Bundle()
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, contentStyle)
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, contentStyle)

        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/$icon".toUri())
            .setExtras(extras)
            .setMediaType(mediaType)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forArtist(item: BaseItemDto, group: String? = null, isFolderBrowse: Boolean = false): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        if (isFolderBrowse) {
            extras.putBoolean(IS_FOLDER_KEY, true)
        }

        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forGenre(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_GENRE)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forAlbum(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras)
            .setSupportedCommands(listOf(DOWNLOAD_COMMAND))
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forPlaylist(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(extras)
            .setSupportedCommands(listOf(DOWNLOAD_COMMAND))
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forAudiobook(item: BaseItemDto, group: String? = null, parent: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        if (parent != null) {
            extras.putString(PARENT_KEY, parent)
        }
        extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        setCompletionStatus(extras, item)

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable((item.childCount ?: 0) > 0)
            .setIsPlayable(true)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras)
            .setSupportedCommands(listOf(DOWNLOAD_COMMAND))
            .build()

        val audioStream = streamingUri(item.id.toString())

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .setUri(audioStream)
            .build()
    }

    fun forFolder(
        item: BaseItemDto,
        group: String? = null,
        isAudiobook: Boolean = false,
        isFolderBrowse: Boolean = false
    ): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        if (isAudiobook) {
            extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        }
        if (isFolderBrowse) {
            extras.putBoolean(IS_FOLDER_KEY, true)
        }
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    fun shuffleAll(folderId: String): MediaItem = shuffleItem(SHUFFLE_FOLDER_PREFIX + folderId)

    fun shuffleGenre(genreId: String): MediaItem = shuffleItem(SHUFFLE_GENRE_PREFIX + genreId)

    private fun shuffleItem(mediaId: String): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.shuffle_all))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri("android.resource://com.chamika.dashtune/drawable/ic_shuffle".toUri())
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forTrack(
        item: BaseItemDto,
        group: String? = null,
        parent: String? = null,
        isAudiobook: Boolean = false
    ): MediaItem {
        val hasOwnImage = item.imageTags?.containsKey(ImageType.PRIMARY) == true
        val artUrl = artUri(if (hasOwnImage) item.id else (item.albumId ?: item.id))

        val audioStream = streamingUri(item.id.toString())

        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        if (parent != null) {
            extras.putString(PARENT_KEY, parent)
        }

        if (isAudiobook) {
            extras.putBoolean(IS_AUDIOBOOK_KEY, true)
            setCompletionStatus(extras, item)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUrl)
            .setUserRating(HeartRating(item.userData?.isFavorite == true))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setDurationMs(item.runTimeTicks?.div(10_000))
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .setUri(audioStream)
            .build()
    }

    private fun setCompletionStatus(extras: Bundle, item: BaseItemDto) {
        val userData = item.userData ?: return
        when {
            userData.played == true -> {
                extras.putInt(EXTRA_COMPLETION_STATUS, COMPLETION_STATUS_FULLY_PLAYED)
                extras.putDouble(EXTRA_COMPLETION_PERCENTAGE, 1.0)
            }
            (userData.playbackPositionTicks ?: 0) > 0 -> {
                extras.putInt(EXTRA_COMPLETION_STATUS, COMPLETION_STATUS_PARTIALLY_PLAYED)
                val percentage = (userData.playedPercentage ?: 0.0) / 100.0
                extras.putDouble(EXTRA_COMPLETION_PERCENTAGE, percentage)
            }
            else -> {
                extras.putInt(EXTRA_COMPLETION_STATUS, COMPLETION_STATUS_NOT_PLAYED)
            }
        }
    }

    private fun artUri(id: UUID): Uri {
        val artUrl = ImageApi(jellyfinApi).getItemImageUrl(
            id,
            ImageType.PRIMARY,
            quality = 90,
            maxWidth = artSize,
            maxHeight = artSize,
        )
        return AlbumArtContentProvider.mapUri(artUrl.toUri())
    }

    fun streamingUri(mediaId: String): String {
        val preferenceBitrate = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString("bitrate", "Direct stream")!!

        val bitrate = if (preferenceBitrate == "Direct stream") null else preferenceBitrate.toIntOrNull()

        val allowedContainers = listOf("flac", "mp3", "m4a", "aac", "ogg")
        return jellyfinApi.universalAudioApi.getUniversalAudioStreamUrl(
            mediaId.toUUID(),
            container = allowedContainers,
            audioBitRate = bitrate,
            maxStreamingBitrate = bitrate,
            transcodingContainer = "mp3",
            audioCodec = "mp3",
        )
    }

    fun create(
        baseItemDto: BaseItemDto,
        group: String? = null,
        parent: String? = null,
        isAudiobook: Boolean = false,
        isFolderBrowse: Boolean = false
    ): MediaItem {
        return when (baseItemDto.type) {
            BaseItemKind.MUSIC_ARTIST -> forArtist(baseItemDto, group, isFolderBrowse)
            BaseItemKind.MUSIC_ALBUM -> forAlbum(baseItemDto, group)
            BaseItemKind.MUSIC_GENRE, BaseItemKind.GENRE -> forGenre(baseItemDto, group)
            BaseItemKind.AUDIO_BOOK -> forAudiobook(baseItemDto, group, parent)
            BaseItemKind.FOLDER -> forFolder(baseItemDto, group, isAudiobook, isFolderBrowse)
            BaseItemKind.COLLECTION_FOLDER -> forFolder(baseItemDto, group, isAudiobook, isFolderBrowse)
            BaseItemKind.PLAYLIST -> forPlaylist(baseItemDto, group)
            BaseItemKind.AUDIO -> forTrack(baseItemDto, group, parent, isAudiobook)
            else -> throw UnsupportedOperationException("Can't create mediaItem for ${baseItemDto.type}")
        }
    }
}
