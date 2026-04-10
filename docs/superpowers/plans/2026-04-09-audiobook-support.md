# Audiobook Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add audiobook browsing, configurable browse categories, and server-synced playback position tracking to DashTune.

**Architecture:** Extend the existing media tree/factory/repository pipeline to handle `BaseItemKind.AUDIO_BOOK` items. Add a configurable categories setting (min 2, max 4 from 5 options). Add 30-second playback progress reporting for audiobooks, with server-based resume position on playback start.

**Tech Stack:** Media3 1.9.2, Jellyfin SDK 1.8.6, Room, SharedPreferences, Android Automotive OS media browser.

**Spec:** `docs/superpowers/specs/2026-04-09-audiobook-support-design.md`

**Build command:** `./gradlew :automotive:assembleDebug`

---

### Task 1: Android Resources

Add the book icon drawable, new string resources, and category preference arrays.

**Files:**
- Create: `automotive/src/main/res/drawable/ic_book.xml`
- Modify: `automotive/src/main/res/values/strings.xml`
- Modify: `automotive/src/main/res/values/arrays.xml`

- [ ] **Step 1: Create ic_book.xml drawable**

Create `automotive/src/main/res/drawable/ic_book.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M18,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,4h5v8l-2.5,-1.5L6,12V4z" />
</vector>
```

This is the Material Design "book" icon, matching the style of the existing icons (`ic_playlists.xml`, `ic_casino.xml`, etc.).

- [ ] **Step 2: Add new strings to strings.xml**

In `automotive/src/main/res/values/strings.xml`, add before the closing `</resources>` tag:

```xml
    <string name="books">Books</string>
    <string name="browse_categories">Browse categories</string>
    <string name="browse_categories_summary">Choose which categories appear (2–4)</string>
    <string name="min_categories_warning">Select at least 2 categories</string>
    <string name="max_categories_warning">Select at most 4 categories</string>
```

- [ ] **Step 3: Add category arrays to arrays.xml**

In `automotive/src/main/res/values/arrays.xml`, add before the closing `</resources>` tag:

```xml
    <string-array name="browseCategories">
        <item>Latest</item>
        <item>Favourites</item>
        <item>Books</item>
        <item>Playlists</item>
        <item>Random</item>
    </string-array>

    <string-array name="browseCategoryValues">
        <item>latest</item>
        <item>favourites</item>
        <item>books</item>
        <item>playlists</item>
        <item>random</item>
    </string-array>

    <string-array name="defaultBrowseCategories">
        <item>latest</item>
        <item>favourites</item>
        <item>books</item>
        <item>playlists</item>
    </string-array>
```

- [ ] **Step 4: Build to verify resources compile**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add automotive/src/main/res/drawable/ic_book.xml \
       automotive/src/main/res/values/strings.xml \
       automotive/src/main/res/values/arrays.xml
git commit -m "feat: add audiobook resources (icon, strings, arrays)

Add ic_book drawable, new string resources for Books category and
browse categories setting, and arrays for category preference.

Refs #8"
```

---

### Task 2: MediaItemFactory — Audiobook Support

Add the `BOOKS` constant, `books()` category node, `forAudiobook()` builder, and update `create()` to handle `AUDIO_BOOK`.

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/media/MediaItemFactory.kt`

- [ ] **Step 1: Add BOOKS constant and IS_AUDIOBOOK_KEY to companion object**

In `MediaItemFactory.kt`, add to the `companion object` block (after the `PARENT_KEY` line):

```kotlin
        const val BOOKS = "BOOKS_ID"
        const val IS_AUDIOBOOK_KEY = "is_audiobook"
```

- [ ] **Step 2: Add books() category node method**

In `MediaItemFactory.kt`, add after the `playlists()` method (after line 104):

```kotlin
    fun books(): MediaItem {
        return albumCategory(BOOKS, "Books", "ic_book")
    }
```

This reuses the existing `albumCategory()` helper, giving Books the same grid layout style as Latest and Random.

- [ ] **Step 3: Add forAudiobook() private method**

In `MediaItemFactory.kt`, add after the `forPlaylist()` method (after line 204):

```kotlin
    private fun forAudiobook(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        extras.putBoolean(IS_AUDIOBOOK_KEY, true)

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }
```

- [ ] **Step 4: Update forTrack() to accept isAudiobook parameter**

In `MediaItemFactory.kt`, update the `forTrack()` method signature:

From:
```kotlin
    private fun forTrack(
        item: BaseItemDto,
        group: String? = null,
        parent: String? = null
    ): MediaItem {
```

To:
```kotlin
    private fun forTrack(
        item: BaseItemDto,
        group: String? = null,
        parent: String? = null,
        isAudiobook: Boolean = false
    ): MediaItem {
```

Then, inside `forTrack()`, add after the `if (parent != null)` block and before the `val metadata` builder:

```kotlin
        if (isAudiobook) {
            extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        }
```

- [ ] **Step 5: Update create() to handle AUDIO_BOOK and pass isAudiobook**

In `MediaItemFactory.kt`, update the `create()` method signature:

From:
```kotlin
    fun create(
        baseItemDto: BaseItemDto,
        group: String? = null,
        parent: String? = null
    ): MediaItem {
```

To:
```kotlin
    fun create(
        baseItemDto: BaseItemDto,
        group: String? = null,
        parent: String? = null,
        isAudiobook: Boolean = false
    ): MediaItem {
```

Then update the `when` block:

From:
```kotlin
        return when (baseItemDto.type) {
            BaseItemKind.MUSIC_ARTIST -> forArtist(baseItemDto, group)
            BaseItemKind.MUSIC_ALBUM -> forAlbum(baseItemDto, group)
            BaseItemKind.PLAYLIST -> forPlaylist(baseItemDto, group)
            BaseItemKind.AUDIO -> forTrack(baseItemDto, group, parent)
            else -> throw UnsupportedOperationException("Can't create mediaItem for ${baseItemDto.type}")
        }
```

To:
```kotlin
        return when (baseItemDto.type) {
            BaseItemKind.MUSIC_ARTIST -> forArtist(baseItemDto, group)
            BaseItemKind.MUSIC_ALBUM -> forAlbum(baseItemDto, group)
            BaseItemKind.AUDIO_BOOK -> forAudiobook(baseItemDto, group)
            BaseItemKind.PLAYLIST -> forPlaylist(baseItemDto, group)
            BaseItemKind.AUDIO -> forTrack(baseItemDto, group, parent, isAudiobook)
            else -> throw UnsupportedOperationException("Can't create mediaItem for ${baseItemDto.type}")
        }
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/media/MediaItemFactory.kt
git commit -m "feat: add audiobook support to MediaItemFactory

Add BOOKS constant, books() category node, forAudiobook() builder for
AUDIO_BOOK items, and is_audiobook flag propagation to tracks.

Refs #8"
```

---

### Task 3: JellyfinMediaTree — Books, Configurable Categories, Search

Add `getBooks()`, make `getChildren(ROOT_ID)` read the browse categories preference, propagate audiobook flag to child tracks, and add audiobooks to search and favourites.

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/media/JellyfinMediaTree.kt`

- [ ] **Step 1: Add new imports**

In `JellyfinMediaTree.kt`, add to the import section:

```kotlin
import androidx.preference.PreferenceManager
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
```

- [ ] **Step 2: Add getActiveCategoryIds() method**

In `JellyfinMediaTree.kt`, add after the `mediaItems` cache declaration (after line 41):

```kotlin
    fun getActiveCategoryIds(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val selected = prefs.getStringSet(
            "browse_categories",
            setOf("latest", "favourites", "books", "playlists")
        )!!
        val canonicalOrder = listOf(
            "latest" to LATEST_ALBUMS,
            "favourites" to FAVOURITES,
            "books" to BOOKS,
            "playlists" to PLAYLISTS,
            "random" to RANDOM_ALBUMS
        )
        return canonicalOrder.filter { it.first in selected }.map { it.second }
    }
```

- [ ] **Step 3: Update getItem() to handle BOOKS**

In `JellyfinMediaTree.kt`, in the `getItem()` method's `when` block, add the `BOOKS` case after `PLAYLISTS`:

From:
```kotlin
                PLAYLISTS -> itemFactory.playlists()
                else -> retryOnFailure {
```

To:
```kotlin
                PLAYLISTS -> itemFactory.playlists()
                BOOKS -> itemFactory.books()
                else -> retryOnFailure {
```

- [ ] **Step 4: Update getChildren() to use configurable categories and handle BOOKS**

In `JellyfinMediaTree.kt`, update the `when` block inside `getChildren()`:

From:
```kotlin
        return when (id) {
            ROOT_ID -> listOf(
                getItem(LATEST_ALBUMS),
                getItem(RANDOM_ALBUMS),
                getItem(FAVOURITES),
                getItem(PLAYLISTS)
            )

            LATEST_ALBUMS -> getLatestAlbums()
            RANDOM_ALBUMS -> getRandomAlbums()
            FAVOURITES -> getFavourite()
            PLAYLISTS -> getPlaylists()
            else -> getItemChildren(id)
        }
```

To:
```kotlin
        return when (id) {
            ROOT_ID -> getActiveCategoryIds().map { getItem(it) }
            LATEST_ALBUMS -> getLatestAlbums()
            RANDOM_ALBUMS -> getRandomAlbums()
            FAVOURITES -> getFavourite()
            PLAYLISTS -> getPlaylists()
            BOOKS -> getBooks()
            else -> getItemChildren(id)
        }
```

- [ ] **Step 5: Add getBooks() method**

In `JellyfinMediaTree.kt`, add after the `getPlaylists()` method:

```kotlin
    private suspend fun getBooks(): List<MediaItem> = retryOnFailure {
        val response = api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
            recursive = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = MAX_ITEMS
        )

        response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }
```

- [ ] **Step 6: Update getItemChildren() to propagate audiobook flag**

In `JellyfinMediaTree.kt`, replace the entire `getItemChildren()` method:

From:
```kotlin
    private suspend fun getItemChildren(id: String): List<MediaItem> {
        if (getItem(id).mediaMetadata.mediaType == MEDIA_TYPE_ARTIST) {
            return getArtistAlbums(id)
        }

        var sortBy = listOf(
            ItemSortBy.PARENT_INDEX_NUMBER,
            ItemSortBy.INDEX_NUMBER,
            ItemSortBy.SORT_NAME
        )

        if (getItem(id).mediaMetadata.mediaType == MEDIA_TYPE_PLAYLIST) {
            sortBy = listOf(ItemSortBy.DEFAULT)
        }

        return retryOnFailure {
            val response = api.itemsApi.getItems(
                sortBy = sortBy,
                parentId = id.toUUID()
            )

            response.content.items.map {
                val item = itemFactory.create(it, parent = id)
                mediaItems.put(item.mediaId, item)
                item
            }
        }
    }
```

To:
```kotlin
    private suspend fun getItemChildren(id: String): List<MediaItem> {
        val parentItem = getItem(id)

        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_ARTIST) {
            return getArtistAlbums(id)
        }

        val isAudiobook = parentItem.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true

        var sortBy = listOf(
            ItemSortBy.PARENT_INDEX_NUMBER,
            ItemSortBy.INDEX_NUMBER,
            ItemSortBy.SORT_NAME
        )

        if (parentItem.mediaMetadata.mediaType == MEDIA_TYPE_PLAYLIST) {
            sortBy = listOf(ItemSortBy.DEFAULT)
        }

        return retryOnFailure {
            val response = api.itemsApi.getItems(
                sortBy = sortBy,
                parentId = id.toUUID()
            )

            response.content.items.map {
                val item = itemFactory.create(it, parent = id, isAudiobook = isAudiobook)
                mediaItems.put(item.mediaId, item)
                item
            }
        }
    }
```

- [ ] **Step 7: Update getFavourite() to include audiobooks**

In `JellyfinMediaTree.kt`, in `getFavourite()`, update the `includeItemTypes`:

From:
```kotlin
            includeItemTypes = listOf(
                BaseItemKind.AUDIO,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST
            )
```

To:
```kotlin
            includeItemTypes = listOf(
                BaseItemKind.AUDIO,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST,
                BaseItemKind.AUDIO_BOOK
            )
```

- [ ] **Step 8: Update groupForItem() for audiobooks**

In `JellyfinMediaTree.kt`, replace `groupForItem()`:

From:
```kotlin
    private fun groupForItem(dto: BaseItemDto): String = (
            if (dto.type == BaseItemKind.MUSIC_ALBUM)
                context.getString(R.string.albums)
            else if (dto.type == BaseItemKind.MUSIC_ARTIST)
                context.getString(R.string.artists)
            else
                context.getString(R.string.tracks)
            )
```

To:
```kotlin
    private fun groupForItem(dto: BaseItemDto): String = when (dto.type) {
        BaseItemKind.MUSIC_ALBUM -> context.getString(R.string.albums)
        BaseItemKind.MUSIC_ARTIST -> context.getString(R.string.artists)
        BaseItemKind.AUDIO_BOOK -> context.getString(R.string.books)
        else -> context.getString(R.string.tracks)
    }
```

- [ ] **Step 9: Add audiobooks to search()**

In `JellyfinMediaTree.kt`, in `search()`, add a new audiobook search block after the playlist search and before the audio search (before the `response = api.itemsApi.getItems(... includeItemTypes = listOf(BaseItemKind.AUDIO)` block):

```kotlin
        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.books))
            mediaItems.put(item.mediaId, item)
            item
        })
```

- [ ] **Step 10: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/media/JellyfinMediaTree.kt
git commit -m "feat: add audiobook browsing, configurable categories, search

Add getBooks() for AUDIO_BOOK browsing, configurable root categories
via SharedPreferences, audiobook flag propagation to child tracks,
audiobooks in favourites and search results.

Refs #8"
```

---

### Task 4: MediaRepository — Books and Dynamic Sync

Add `BOOKS` to the static ID set, update sync to use the active categories, and fix extras Boolean serialization for the `is_audiobook` flag.

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/data/MediaRepository.kt`

- [ ] **Step 1: Add BOOKS import**

In `MediaRepository.kt`, add to imports:

```kotlin
import com.chamika.dashtune.media.MediaItemFactory.Companion.BOOKS
```

- [ ] **Step 2: Add BOOKS to staticIds**

In `MediaRepository.kt`, update the `staticIds` set:

From:
```kotlin
    private val staticIds = setOf(ROOT_ID, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS)
```

To:
```kotlin
    private val staticIds = setOf(ROOT_ID, LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS, BOOKS)
```

- [ ] **Step 3: Update sync() to use active categories**

In `MediaRepository.kt`, in `sync()`, change the hardcoded section list:

From:
```kotlin
        val sectionIds = listOf(LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS)
```

To:
```kotlin
        val sectionIds = tree.getActiveCategoryIds()
```

- [ ] **Step 4: Fix Boolean serialization in toEntity()**

In `MediaRepository.kt`, in the `toEntity()` extension function, update the extras serialization `when` block. Add Boolean handling:

From:
```kotlin
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                }
```

To:
```kotlin
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                }
```

- [ ] **Step 5: Fix Boolean deserialization in toMediaItem()**

In `MediaRepository.kt`, in the `toMediaItem()` extension function, update the extras deserialization `when` block. Add Boolean handling:

From:
```kotlin
                when (val value = jsonObj.get(key)) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                }
```

To:
```kotlin
                when (val value = jsonObj.get(key)) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                }
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/data/MediaRepository.kt
git commit -m "feat: update MediaRepository for audiobooks and dynamic sync

Add BOOKS to static IDs, use configurable active categories for sync,
fix Boolean serialization in extras for is_audiobook flag.

Refs #8"
```

---

### Task 5: Settings UI — Browse Categories Preference

Add the `MultiSelectListPreference` for browse categories and validation in `SettingsFragment`.

**Files:**
- Modify: `automotive/src/main/res/xml/preferences.xml`
- Modify: `automotive/src/main/java/com/chamika/dashtune/settings/SettingsFragment.kt`

- [ ] **Step 1: Add MultiSelectListPreference to preferences.xml**

In `automotive/src/main/res/xml/preferences.xml`, add after the `prefetch_count` ListPreference and before the `version` Preference:

```xml
    <MultiSelectListPreference
        android:defaultValue="@array/defaultBrowseCategories"
        android:entries="@array/browseCategories"
        android:entryValues="@array/browseCategoryValues"
        android:key="browse_categories"
        android:summary="@string/browse_categories_summary"
        android:title="@string/browse_categories" />
```

- [ ] **Step 2: Add validation in SettingsFragment**

In `automotive/src/main/java/com/chamika/dashtune/settings/SettingsFragment.kt`, add new imports:

```kotlin
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
```

Then, inside `onCreatePreferences()`, add after the `findPreference<Preference>("version")?.summary = viewModel.versionString()` line:

```kotlin
        findPreference<MultiSelectListPreference>("browse_categories")?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as? Set<String> ?: return@setOnPreferenceChangeListener false
            when {
                selected.size < 2 -> {
                    Toast.makeText(requireContext(), R.string.min_categories_warning, Toast.LENGTH_SHORT).show()
                    false
                }
                selected.size > 4 -> {
                    Toast.makeText(requireContext(), R.string.max_categories_warning, Toast.LENGTH_SHORT).show()
                    false
                }
                else -> true
            }
        }
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add automotive/src/main/res/xml/preferences.xml \
       automotive/src/main/java/com/chamika/dashtune/settings/SettingsFragment.kt
git commit -m "feat: add configurable browse categories setting

Add MultiSelectListPreference with min 2, max 4 validation.
Default categories: Latest, Favourites, Books, Playlists.

Refs #8"
```

---

### Task 6: DashTuneSessionCallback — Resume Position and Shuffle

Add audiobook resume position from server (with 3-second timeout and local fallback) and shuffle save/restore behavior.

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt`

- [ ] **Step 1: Add new imports**

In `DashTuneSessionCallback.kt`, add to the import section:

```kotlin
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemSortBy
```

- [ ] **Step 2: Add getAudiobookResumePosition() helper method**

In `DashTuneSessionCallback.kt`, add before the closing `}` of the class:

```kotlin
    private suspend fun getAudiobookResumePosition(
        bookId: String,
        chapters: List<MediaItem>
    ): Pair<Int, Long>? {
        val response = jellyfinApi.itemsApi.getItems(
            parentId = bookId.toUUID(),
            sortBy = listOf(
                ItemSortBy.PARENT_INDEX_NUMBER,
                ItemSortBy.INDEX_NUMBER,
                ItemSortBy.SORT_NAME
            )
        )

        val chaptersData = response.content.items
        val lastInProgress = chaptersData
            .filter { (it.userData?.playbackPositionTicks ?: 0) > 0 }
            .maxByOrNull { it.userData?.lastPlayedDate ?: return@maxByOrNull null }
            ?: return null

        val chapterIndex = chapters.indexOfFirst { it.mediaId == lastInProgress.id.toString() }
        if (chapterIndex < 0) return null

        val positionMs = (lastInProgress.userData?.playbackPositionTicks ?: 0) / 10_000
        return Pair(chapterIndex, positionMs)
    }
```

- [ ] **Step 3: Add handleAudiobookShuffle() helper method**

In `DashTuneSessionCallback.kt`, add after `getAudiobookResumePosition()`:

```kotlin
    private fun handleAudiobookShuffle(
        mediaSession: MediaSession,
        isAudiobookContent: Boolean
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        val player = mediaSession.player

        if (isAudiobookContent) {
            if (player.shuffleModeEnabled) {
                prefs.edit { putBoolean("shuffle_before_audiobook", true) }
                player.shuffleModeEnabled = false
                mediaSession.setMediaButtonPreferences(CommandButtons.createButtons(player))
            }
        } else {
            if (prefs.getBoolean("shuffle_before_audiobook", false)) {
                prefs.edit { putBoolean("shuffle_before_audiobook", false) }
                player.shuffleModeEnabled = true
                mediaSession.setMediaButtonPreferences(CommandButtons.createButtons(player))
            }
        }
    }
```

- [ ] **Step 4: Update onSetMediaItems() with audiobook resume and shuffle**

In `DashTuneSessionCallback.kt`, replace the entire `onSetMediaItems()` method:

From:
```kotlin
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(LOG_TAG, "onSetMediaItems $mediaItems")
        return SuspendToFutureAdapter.launchFuture {
            if (isSingleItemWithParent(mediaItems)) {
                val singleItem = mediaItems[0]
                val resolvedItems = expandSingleItem(singleItem)

                val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                    resolvedItems,
                    resolvedItems.indexOfFirst { it.mediaId == singleItem.mediaId },
                    startPositionMs
                )
                savePlaylist(resolvedItems)
                return@launchFuture mediaItemsWithStartPosition
            }

            val resolvedItems = resolveMediaItems(mediaItems)
            val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                resolvedItems,
                startIndex,
                startPositionMs
            )
            savePlaylist(resolvedItems)
            mediaItemsWithStartPosition
        }
    }
```

To:
```kotlin
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(LOG_TAG, "onSetMediaItems $mediaItems")
        return SuspendToFutureAdapter.launchFuture {
            if (isSingleItemWithParent(mediaItems)) {
                val singleItem = mediaItems[0]
                val resolvedItems = expandSingleItem(singleItem)

                val isAudiobookContent = resolvedItems.any {
                    it.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
                }
                handleAudiobookShuffle(mediaSession, isAudiobookContent)

                val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                    resolvedItems,
                    resolvedItems.indexOfFirst { it.mediaId == singleItem.mediaId },
                    startPositionMs
                )
                savePlaylist(resolvedItems)
                return@launchFuture mediaItemsWithStartPosition
            }

            val resolvedItems = resolveMediaItems(mediaItems)

            val isAudiobookContent = resolvedItems.any {
                it.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
            }
            handleAudiobookShuffle(mediaSession, isAudiobookContent)

            var finalStartIndex = startIndex
            var finalStartPositionMs = startPositionMs

            if (isAudiobookContent && mediaItems.size == 1) {
                val bookId = mediaItems[0].mediaId
                try {
                    val resumeInfo = withTimeoutOrNull(3000) {
                        getAudiobookResumePosition(bookId, resolvedItems)
                    }
                    if (resumeInfo != null) {
                        finalStartIndex = resumeInfo.first
                        finalStartPositionMs = resumeInfo.second
                        Log.i(LOG_TAG, "Audiobook resume: chapter=$finalStartIndex, position=$finalStartPositionMs ms")
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to get audiobook resume position, using local fallback", e)
                }
            }

            val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                resolvedItems,
                finalStartIndex,
                finalStartPositionMs
            )
            savePlaylist(resolvedItems)
            mediaItemsWithStartPosition
        }
    }
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt
git commit -m "feat: add audiobook resume position and shuffle handling

Query server for last-played chapter with 3-second timeout.
Save/restore shuffle state when switching between music and audiobooks.

Refs #8"
```

---

### Task 7: DashTuneMusicService — Progress Reporting

Add 30-second playback progress reporting to Jellyfin for audiobook tracks, with audiobook detection on track transition.

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/DashTuneMusicService.kt`

- [ ] **Step 1: Add new imports**

In `DashTuneMusicService.kt`, add to the import section:

```kotlin
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
```

- [ ] **Step 2: Add audiobook state fields**

In `DashTuneMusicService.kt`, add after the `currentTrack` field declaration (after `private var currentTrack: MediaItem? = null`):

```kotlin
    private var isPlayingAudiobook: Boolean = false
    private lateinit var audiobookProgressPoll: Runnable
```

- [ ] **Step 3: Initialize audiobookProgressPoll in onCreate()**

In `DashTuneMusicService.kt`, add after the `playbackPoll` initialization block and before `callback = DashTuneSessionCallback(...)`:

```kotlin
        audiobookProgressPoll = Runnable {
            val p = mediaLibrarySession.player
            if (p.isPlaying && isPlayingAudiobook && p.currentMediaItem != null) {
                serviceScope.launch {
                    try {
                        jellyfinApi.playStateApi.reportPlaybackProgress(
                            PlaybackProgressInfo(
                                itemId = p.currentMediaItem!!.mediaId.toUUID(),
                                positionTicks = p.currentPosition * 10_000,
                                isPaused = false,
                                isMuted = false,
                                playMethod = PlayMethod.DIRECT_PLAY,
                                canSeek = true,
                                repeatMode = RepeatMode.REPEAT_NONE,
                                playbackOrder = PlaybackOrder.DEFAULT
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to report audiobook progress", e)
                    }
                }
                handler.postDelayed(audiobookProgressPoll, 30_000)
            }
        }
```

- [ ] **Step 4: Add audiobook detection to playerListener EVENT_MEDIA_ITEM_TRANSITION**

In `DashTuneMusicService.kt`, inside the `playerListener`'s `onEvents` method, add after the existing `FirebaseUtils.safeSetCustomKey("playlist_size", ...)` line and before the closing `}` of the `EVENT_MEDIA_ITEM_TRANSITION` block:

```kotlin
                    val wasPlayingAudiobook = isPlayingAudiobook
                    isPlayingAudiobook = player.currentMediaItem
                        ?.mediaMetadata?.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true

                    if (isPlayingAudiobook) {
                        handler.removeCallbacks(audiobookProgressPoll)
                        handler.postDelayed(audiobookProgressPoll, 30_000)
                    } else if (wasPlayingAudiobook) {
                        handler.removeCallbacks(audiobookProgressPoll)
                    }
```

- [ ] **Step 5: Manage audiobookProgressPoll in EVENT_IS_PLAYING_CHANGED**

In `DashTuneMusicService.kt`, update the `EVENT_IS_PLAYING_CHANGED` block:

From:
```kotlin
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    FirebaseUtils.safeSetCustomKey("is_playing", player.isPlaying)
                    if (player.isPlaying) {
                        startPlaybackPoll()
                    } else {
                        stopPlaybackPoll()
                    }
                }
```

To:
```kotlin
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    FirebaseUtils.safeSetCustomKey("is_playing", player.isPlaying)
                    if (player.isPlaying) {
                        startPlaybackPoll()
                        if (isPlayingAudiobook) {
                            handler.removeCallbacks(audiobookProgressPoll)
                            handler.postDelayed(audiobookProgressPoll, 30_000)
                        }
                    } else {
                        stopPlaybackPoll()
                        handler.removeCallbacks(audiobookProgressPoll)
                    }
                }
```

- [ ] **Step 6: Clean up audiobookProgressPoll in onDestroy()**

In `DashTuneMusicService.kt`, in `onDestroy()`, add after `handler.removeCallbacks(playbackPoll)`:

```kotlin
        handler.removeCallbacks(audiobookProgressPoll)
```

- [ ] **Step 7: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/DashTuneMusicService.kt
git commit -m "feat: add 30-second audiobook progress reporting to Jellyfin

Report playback progress every 30 seconds for audiobook tracks via
playStateApi.reportPlaybackProgress. Detect audiobook content on
track transition using is_audiobook metadata flag.

Refs #8"
```

---

### Task 8: Final Build Verification

Verify the complete build succeeds with all changes integrated.

**Files:** None (verification only)

- [ ] **Step 1: Full clean build**

Run: `./gradlew clean :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify all changes are committed**

Run: `git --no-pager status`
Expected: Clean working tree, nothing to commit.

- [ ] **Step 3: Review the commit log**

Run: `git --no-pager log --oneline -10`
Expected: 7 new commits for Tasks 1-7 plus the earlier spec commit.
