package com.chamika.dashtune.fake

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.time.LocalDateTime
import java.util.UUID

/**
 * A single row of the simulated Jellyfin library.
 *
 * This is deliberately a flat list of items joined by [parentId] / [albumArtistIds] /
 * [genreIds] rather than a nested tree, because that is the shape Jellyfin itself queries:
 * every browse call the app makes is a filter over one flat item table
 * (`parentId=`, `albumArtistIds=`, `genreIds=`, `includeItemTypes=`, `nameStartsWith=`).
 * Modelling it the same way means [FakeJellyfinServer] can answer any of them with the same
 * filter pipeline instead of a special case per category.
 */
data class FakeItem(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val kind: BaseItemKind,
    val parentId: UUID? = null,
    val albumId: UUID? = null,
    val albumArtist: String? = null,
    val albumArtistIds: List<UUID> = emptyList(),
    val genreIds: List<UUID> = emptyList(),
    val genreNames: List<String> = emptyList(),
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val runTimeTicks: Long? = null,
    val childCount: Int? = null,
    val collectionType: CollectionType? = null,
    val isFavorite: Boolean = false,
    /** Drives `ImageTags[Primary]`, which decides whether art resolves to this id or [albumId]. */
    val hasOwnImage: Boolean = false,
    val played: Boolean = false,
    val playbackPositionTicks: Long = 0,
    val playedPercentage: Double? = null,
    /** Drives `UserData.LastPlayedDate`, which book-level resume uses to pick the latest chapter. */
    val lastPlayedAt: LocalDateTime? = null,
    /** Ordering key for `sortBy=DateCreated`; higher is newer. */
    val createdOrder: Int = 0,
) {
    val isAudio: Boolean get() = kind == BaseItemKind.AUDIO
}

/**
 * An in-memory Jellyfin library. Ids are fresh random UUIDs on every instance, which keeps
 * test classes from colliding in the app's ExoPlayer disk cache — that cache is keyed by
 * media id and outlives any single test, so reusing fixed ids would let a cached stream
 * satisfy a request the test expected to see hit the server.
 */
class FakeLibrary {

    private val _items = mutableListOf<FakeItem>()
    val items: List<FakeItem> get() = _items

    /** Library roots returned by `/UserViews`. */
    val views = mutableListOf<FakeItem>()

    fun add(item: FakeItem): FakeItem {
        _items += item
        return item
    }

    fun addView(name: String, collectionType: CollectionType): FakeItem {
        val view = FakeItem(
            name = name,
            kind = BaseItemKind.COLLECTION_FOLDER,
            collectionType = collectionType,
        )
        views += view
        _items += view
        return view
    }

    /** Swaps an item for an edited copy, matching on id. */
    fun replace(item: FakeItem): FakeItem {
        val index = _items.indexOfFirst { it.id == item.id }
        require(index >= 0) { "No item with id ${item.id} to replace" }
        _items[index] = item
        return item
    }

    fun byId(id: UUID): FakeItem? = _items.firstOrNull { it.id == id }

    fun childrenOf(parentId: UUID): List<FakeItem> = _items.filter { it.parentId == parentId }

    /** Every AUDIO item beneath [parentId], at any depth — what `recursive=true` means. */
    fun descendantsOf(parentId: UUID): List<FakeItem> {
        val out = mutableListOf<FakeItem>()
        val queue = ArrayDeque(childrenOf(parentId))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            out += next
            queue.addAll(childrenOf(next.id))
        }
        return out
    }
}
