package com.chamika.dashtune.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_TRACKS

/** Browse IDs retain the selected list even when a host sends back only the ID. */
@OptIn(UnstableApi::class)
object TrackList {
    fun parentId(mediaId: String): String? =
        listOf(RANDOM_TRACKS, FAVOURITES).firstOrNull { mediaId.startsWith("$it/") }

    fun trackId(mediaId: String): String =
        parentId(mediaId)?.let { mediaId.removePrefix("$it/") } ?: mediaId

    fun isSong(item: MediaItem): Boolean =
        item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC &&
            item.mediaMetadata.isPlayable == true &&
            item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) != true

    fun browseItem(item: MediaItem, parentId: String): MediaItem {
        if (parentId != RANDOM_TRACKS && parentId != FAVOURITES) return item
        val metadata = item.mediaMetadata
        val extras = Bundle(metadata.extras ?: Bundle()).apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return item.buildUpon()
            .setMediaId(if (isSong(item)) "$parentId/${trackId(item.mediaId)}" else item.mediaId)
            .setMediaMetadata(metadata.buildUpon()
                .setSubtitle(metadata.artist ?: metadata.albumArtist)
                .setExtras(extras)
                .build())
            .build()
    }
}
