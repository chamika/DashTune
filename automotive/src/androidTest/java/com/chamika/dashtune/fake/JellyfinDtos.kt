package com.chamika.dashtune.fake

import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto

/**
 * Encoder for the simulated server's responses.
 *
 * Responses are built from the SDK's own `BaseItemDto` / `BaseItemDtoQueryResult` and encoded
 * with the SDK's own [ApiSerializer] — the very `Json` instance the app decodes with. Field
 * names, enum spellings and the UUID format are therefore guaranteed to match; a hand-rolled
 * JSON fixture would silently decode to nulls the moment one of them drifted.
 */
object JellyfinJson {

    fun encode(value: Any): String = ApiSerializer.encodeRequestBody(value)!!

    /**
     * `/Items/Latest` answers with a bare array rather than a query result. ApiSerializer
     * resolves a serializer from the runtime class, which does not work for a generic list,
     * so the array is assembled from individually encoded elements.
     */
    fun encodeArray(values: List<Any>): String =
        values.joinToString(prefix = "[", separator = ",", postfix = "]") { encode(it) }
}

/**
 * `UserItemDataDto` has six non-nullable fields; omitting any one of them fails
 * deserialization on the device with a decoding error rather than an empty list, so this
 * always emits the full set.
 */
fun FakeItem.toUserData(): UserItemDataDto = UserItemDataDto(
    playbackPositionTicks = playbackPositionTicks,
    playCount = if (played) 1 else 0,
    isFavorite = isFavorite,
    played = played,
    playedPercentage = playedPercentage,
    lastPlayedDate = lastPlayedAt,
    key = id.toString(),
    itemId = id,
)

fun FakeItem.toDto(): BaseItemDto = BaseItemDto(
    id = id,
    name = name,
    type = kind,
    parentId = parentId,
    albumId = albumId,
    albumArtist = albumArtist,
    albumArtists = albumArtistIds.mapIndexed { index, artistId ->
        NameGuidPair(name = albumArtist ?: "Artist $index", id = artistId)
    }.ifEmpty { null },
    artistItems = albumArtistIds.mapIndexed { index, artistId ->
        NameGuidPair(name = albumArtist ?: "Artist $index", id = artistId)
    }.ifEmpty { null },
    genres = genreNames.ifEmpty { null },
    indexNumber = indexNumber,
    parentIndexNumber = parentIndexNumber,
    runTimeTicks = runTimeTicks,
    childCount = childCount,
    collectionType = collectionType,
    isFolder = kind in browsableKinds,
    mediaType = if (isAudio || kind == BaseItemKind.AUDIO_BOOK) MediaType.AUDIO else MediaType.UNKNOWN,
    // Only tracks that carry their own Primary tag resolve art to their own id; everything
    // else falls back to the album id (MediaItemFactory.forTrack).
    imageTags = if (hasOwnImage || kind != BaseItemKind.AUDIO) mapOf(ImageType.PRIMARY to "tag-$id") else null,
    userData = toUserData(),
)

private val browsableKinds = setOf(
    BaseItemKind.MUSIC_ALBUM,
    BaseItemKind.MUSIC_ARTIST,
    BaseItemKind.PLAYLIST,
    BaseItemKind.FOLDER,
    BaseItemKind.COLLECTION_FOLDER,
)

fun queryResult(items: List<FakeItem>, startIndex: Int = 0, totalRecordCount: Int = items.size) =
    BaseItemDtoQueryResult(
        items = items.map { it.toDto() },
        totalRecordCount = totalRecordCount,
        startIndex = startIndex,
    )
