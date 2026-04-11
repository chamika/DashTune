# Regression Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add unit tests covering media item resolution, factory creation, and cache roundtrip to prevent regressions like the playlist/album playback bug.

**Architecture:** Extract `MediaItemResolver` from `DashTuneSessionCallback` to make resolution logic independently testable. Use Robolectric for Android framework classes, MockK for mocking Jellyfin API/DAO/repository, and kotlinx-coroutines-test for suspend functions. All tests are JVM unit tests (no emulator needed).

**Tech Stack:** JUnit 4, Robolectric 4.14.1, MockK 1.14.2, kotlinx-coroutines-test 1.10.2, Media3 test-utils 1.9.2

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `automotive/src/main/java/com/chamika/dashtune/media/MediaItemResolver.kt` | Extracted resolution logic: `resolveMediaItems`, `isSingleItemWithParent`, `expandSingleItem` |
| Modify | `automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt` | Delegate to `MediaItemResolver` instead of owning resolution logic |
| Modify | `gradle/libs.versions.toml` | Add test dependency versions |
| Modify | `automotive/build.gradle.kts` | Add test dependencies, Robolectric config |
| Create | `automotive/src/test/java/com/chamika/dashtune/media/MediaItemResolverTest.kt` | Tests for resolution logic (the bug area) |
| Create | `automotive/src/test/java/com/chamika/dashtune/media/MediaItemFactoryTest.kt` | Tests for MediaItem creation per type |
| Create | `automotive/src/test/java/com/chamika/dashtune/data/MediaRepositoryTest.kt` | Tests for cache entity ↔ MediaItem roundtrip |
| Create | `automotive/src/test/resources/robolectric.properties` | Robolectric SDK config |
| Delete | `automotive/src/test/java/com/chamika/dashtune/ExampleUnitTest.kt` | Placeholder test no longer needed |

---

### Task 1: Add test dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `automotive/build.gradle.kts`
- Create: `automotive/src/test/resources/robolectric.properties`

- [ ] **Step 1: Add dependency versions to version catalog**

Add to `gradle/libs.versions.toml`:

In `[versions]` section, after the existing `espressoCore` line:
```toml
mockk = "1.14.2"
robolectric = "4.14.1"
coroutinesTest = "1.10.2"
```

In `[libraries]` section, after the existing `androidx-espresso-core` line:
```toml
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
androidx-media3-test-utils = { group = "androidx.media3", name = "media3-test-utils", version.ref = "media3" }
```

- [ ] **Step 2: Add test dependencies to build.gradle.kts**

In `automotive/build.gradle.kts`, replace the existing test dependencies block:

```kotlin
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
```

with:

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.media3.test.utils)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
```

Also add `testOptions` inside the `android { }` block, after the `kotlin { }` block:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

- [ ] **Step 3: Create Robolectric properties**

Create `automotive/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

- [ ] **Step 4: Sync and verify build**

Run: `./gradlew :automotive:assembleDebug --quiet`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml automotive/build.gradle.kts automotive/src/test/resources/robolectric.properties
git commit -m "build: add test dependencies (Robolectric, MockK, coroutines-test)"
```

---

### Task 2: Extract MediaItemResolver from DashTuneSessionCallback

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/media/MediaItemResolver.kt`
- Modify: `automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt`

- [ ] **Step 1: Create MediaItemResolver class**

Create `automotive/src/main/java/com/chamika/dashtune/media/MediaItemResolver.kt`:

```kotlin
package com.chamika.dashtune.media

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY

class MediaItemResolver(
    private val repository: MediaRepository
) {

    suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()

        mediaItems.forEach {
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

    suspend fun isSingleItemWithParent(mediaItems: List<MediaItem>): Boolean {
        if (mediaItems.size != 1) return false
        val mediaId = mediaItems[0].mediaId
        val item = repository.getItem(mediaId)
        if (item.mediaMetadata.extras?.containsKey(PARENT_KEY) == true) return true
        return repository.getContentParentId(mediaId) != null
    }

    suspend fun expandSingleItem(item: MediaItem): List<MediaItem> {
        val parentId = repository.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)
            ?: repository.getContentParentId(item.mediaId)
            ?: return listOf(item)
        val children = repository.getChildren(parentId)
        return resolveMediaItems(children)
    }
}
```

- [ ] **Step 2: Update DashTuneSessionCallback to use MediaItemResolver**

In `DashTuneSessionCallback.kt`, add a `resolver` field alongside `repository`:

After line 68 (`private lateinit var repository: MediaRepository`), add:
```kotlin
    private lateinit var resolver: MediaItemResolver
```

In `ensureTreeInitialized()`, after `repository = MediaRepository(...)` (line 99), add:
```kotlin
            resolver = MediaItemResolver(repository)
```

- [ ] **Step 3: Replace resolution method calls with resolver delegation**

In `DashTuneSessionCallback.kt`, delete the three private methods `resolveMediaItems`, `isSingleItemWithParent`, and `expandSingleItem` (lines 307-352). Then update all call sites to use `resolver.` prefix:

Replace every occurrence of:
- `resolveMediaItems(` → `resolver.resolveMediaItems(`
- `isSingleItemWithParent(` → `resolver.isSingleItemWithParent(`
- `expandSingleItem(` → `resolver.expandSingleItem(`

There are these call sites to update:
1. Line 224: `if (isSingleItemWithParent(mediaItems))` → `if (resolver.isSingleItemWithParent(mediaItems))`
2. Line 226: `val resolvedItems = expandSingleItem(singleItem)` → `val resolvedItems = resolver.expandSingleItem(singleItem)`
3. Line 262: `val resolvedItems = resolveMediaItems(mediaItems)` → `val resolvedItems = resolver.resolveMediaItems(mediaItems)`
4. Line 212: `return SuspendToFutureAdapter.launchFuture { resolveMediaItems(mediaItems) }` → `return SuspendToFutureAdapter.launchFuture { resolver.resolveMediaItems(mediaItems) }`

Also remove imports that are no longer needed in DashTuneSessionCallback (they moved to MediaItemResolver):
- `com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY` — keep this, it's still used in `onSetMediaItems` for audiobook shuffle detection
- `com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY` — remove, only used in the extracted methods

- [ ] **Step 4: Build to verify refactor**

Run: `./gradlew :automotive:assembleDebug --quiet`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/media/MediaItemResolver.kt automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt
git commit -m "refactor: extract MediaItemResolver from DashTuneSessionCallback"
```

---

### Task 3: Write MediaItemResolver tests

**Files:**
- Create: `automotive/src/test/java/com/chamika/dashtune/media/MediaItemResolverTest.kt`

These are the critical regression tests covering the bug we just fixed.

- [ ] **Step 1: Create the test file with test helper and all tests**

Create `automotive/src/test/java/com/chamika/dashtune/media/MediaItemResolverTest.kt`:

```kotlin
package com.chamika.dashtune.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemResolverTest {

    private lateinit var repository: MediaRepository
    private lateinit var resolver: MediaItemResolver

    @Before
    fun setUp() {
        repository = mockk()
        resolver = MediaItemResolver(repository)
    }

    private fun buildMediaItem(
        mediaId: String,
        mediaType: Int,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        uri: String? = null,
        isAudiobook: Boolean = false,
        parentKey: String? = null
    ): MediaItem {
        val extras = Bundle()
        if (isAudiobook) extras.putBoolean(IS_AUDIOBOOK_KEY, true)
        if (parentKey != null) extras.putString(PARENT_KEY, parentKey)

        val metadata = MediaMetadata.Builder()
            .setTitle("Item $mediaId")
            .setMediaType(mediaType)
            .setIsPlayable(isPlayable)
            .setIsBrowsable(isBrowsable)
            .setExtras(extras)
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)

        if (uri != null) {
            builder.setUri(uri)
        }

        return builder.build()
    }

    // --- resolveMediaItems tests ---

    @Test
    fun `playlist is expanded into child tracks`() = runTest {
        val playlist = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false
        )
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val track2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("playlist-1") } returns playlist
        coEvery { repository.getChildren("playlist-1") } returns listOf(track1, track2)
        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("track-2") } returns track2

        val result = resolver.resolveMediaItems(listOf(playlist))

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `album is expanded into child tracks`() = runTest {
        val album = buildMediaItem(
            mediaId = "album-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false
        )
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("album-1") } returns album
        coEvery { repository.getChildren("album-1") } returns listOf(track)
        coEvery { repository.getItem("track-1") } returns track

        val result = resolver.resolveMediaItems(listOf(album))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `track is added directly without expansion`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track

        val result = resolver.resolveMediaItems(listOf(track))

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `single-file audiobook plays directly without expansion`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )

        coEvery { repository.getItem("book-1") } returns audiobook

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(1, result.size)
        assertEquals("book-1", result[0].mediaId)
    }

    @Test
    fun `multi-chapter audiobook expands into chapters`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = true,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )
        val chapter1 = buildMediaItem(
            mediaId = "chapter-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/chapter-1"
        )
        val chapter2 = buildMediaItem(
            mediaId = "chapter-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            isAudiobook = true,
            uri = "http://server/audio/chapter-2"
        )

        coEvery { repository.getItem("book-1") } returns audiobook
        coEvery { repository.getChildren("book-1") } returns listOf(chapter1, chapter2)
        coEvery { repository.getItem("chapter-1") } returns chapter1
        coEvery { repository.getItem("chapter-2") } returns chapter2

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(2, result.size)
        assertEquals("chapter-1", result[0].mediaId)
        assertEquals("chapter-2", result[1].mediaId)
    }

    @Test
    fun `mixed list of playlist and tracks resolves correctly`() = runTest {
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val playlist = buildMediaItem(
            mediaId = "playlist-1",
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false
        )
        val playlistTrack = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("track-1") } returns track1
        coEvery { repository.getItem("playlist-1") } returns playlist
        coEvery { repository.getChildren("playlist-1") } returns listOf(playlistTrack)
        coEvery { repository.getItem("track-2") } returns playlistTrack

        val result = resolver.resolveMediaItems(listOf(track1, playlist))

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `non-playable non-expandable item is skipped`() = runTest {
        val folder = buildMediaItem(
            mediaId = "folder-1",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            isPlayable = false,
            isBrowsable = true
        )

        coEvery { repository.getItem("folder-1") } returns folder

        val result = resolver.resolveMediaItems(listOf(folder))

        assertEquals(0, result.size)
    }

    @Test
    fun `expandable item with empty children and URI plays directly`() = runTest {
        val audiobook = buildMediaItem(
            mediaId = "book-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = true,
            isAudiobook = true,
            uri = "http://server/audio/book-1"
        )

        coEvery { repository.getItem("book-1") } returns audiobook
        coEvery { repository.getChildren("book-1") } returns emptyList()

        val result = resolver.resolveMediaItems(listOf(audiobook))

        assertEquals(1, result.size)
        assertEquals("book-1", result[0].mediaId)
    }

    @Test
    fun `expandable item with empty children and no URI is skipped`() = runTest {
        val album = buildMediaItem(
            mediaId = "album-1",
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("album-1") } returns album
        coEvery { repository.getChildren("album-1") } returns emptyList()

        val result = resolver.resolveMediaItems(listOf(album))

        assertEquals(0, result.size)
    }

    // --- isSingleItemWithParent tests ---

    @Test
    fun `isSingleItemWithParent returns true when item has PARENT_KEY`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )

        coEvery { repository.getItem("track-1") } returns track

        assertTrue(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns true when DB has parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns "album-1"

        assertTrue(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns false when no parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns null

        assertFalse(resolver.isSingleItemWithParent(listOf(track)))
    }

    @Test
    fun `isSingleItemWithParent returns false for multiple items`() = runTest {
        val track1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )
        val track2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )

        assertFalse(resolver.isSingleItemWithParent(listOf(track1, track2)))
    }

    // --- expandSingleItem tests ---

    @Test
    fun `expandSingleItem returns siblings from parent`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            parentKey = "album-1"
        )
        val sibling1 = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val sibling2 = buildMediaItem(
            mediaId = "track-2",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-2"
        )

        coEvery { repository.getItem("track-2") } returns track
        coEvery { repository.getChildren("album-1") } returns listOf(sibling1, sibling2)
        coEvery { repository.getItem("track-1") } returns sibling1

        val result = resolver.expandSingleItem(track)

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `expandSingleItem returns item itself when no parent found`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns null

        val result = resolver.expandSingleItem(track)

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }

    @Test
    fun `expandSingleItem uses DB parent when extras parent missing`() = runTest {
        val track = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )
        val sibling = buildMediaItem(
            mediaId = "track-1",
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            uri = "http://server/audio/track-1"
        )

        coEvery { repository.getItem("track-1") } returns track
        coEvery { repository.getContentParentId("track-1") } returns "album-1"
        coEvery { repository.getChildren("album-1") } returns listOf(sibling)

        val result = resolver.expandSingleItem(track)

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :automotive:testDebugUnitTest --tests "com.chamika.dashtune.media.MediaItemResolverTest" --quiet`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add automotive/src/test/java/com/chamika/dashtune/media/MediaItemResolverTest.kt
git commit -m "test: add MediaItemResolver tests for playlist/album/audiobook resolution"
```

---

### Task 4: Write MediaItemFactory tests

**Files:**
- Create: `automotive/src/test/java/com/chamika/dashtune/media/MediaItemFactoryTest.kt`

- [ ] **Step 1: Create the test file**

Create `automotive/src/test/java/com/chamika/dashtune/media/MediaItemFactoryTest.kt`:

```kotlin
package com.chamika.dashtune.media

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.UniversalAudioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryTest {

    private lateinit var factory: MediaItemFactory
    private lateinit var context: Context
    private lateinit var jellyfinApi: ApiClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        jellyfinApi = mockk(relaxed = true)

        val mockUniversalAudioApi = mockk<UniversalAudioApi>()
        every { jellyfinApi.universalAudioApi } returns mockUniversalAudioApi
        every { mockUniversalAudioApi.getUniversalAudioStreamUrl(any(), any(), any(), any(), any(), any(), any()) } returns
            "http://server/audio/stream"

        factory = MediaItemFactory(context, jellyfinApi, 256)
    }

    private fun baseItem(
        type: BaseItemKind,
        name: String = "Test Item",
        childCount: Int? = null
    ): BaseItemDto {
        return BaseItemDto(
            id = UUID.randomUUID(),
            type = type,
            name = name,
            childCount = childCount,
        )
    }

    @Test
    fun `create music artist is browsable and not playable`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ARTIST))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ARTIST, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create music album is playable and not browsable`() {
        val item = factory.create(baseItem(BaseItemKind.MUSIC_ALBUM))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, item.mediaMetadata.mediaType)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `create playlist is playable and not browsable and has no URI`() {
        val item = factory.create(baseItem(BaseItemKind.PLAYLIST))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_PLAYLIST, item.mediaMetadata.mediaType)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `create audio track is playable and has streaming URI`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertNotNull(item.localConfiguration)
    }

    @Test
    fun `create audiobook has audiobook flag and streaming URI`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK))

        assertTrue(item.mediaMetadata.isPlayable == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, item.mediaMetadata.mediaType)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertNotNull(item.localConfiguration)
    }

    @Test
    fun `create audiobook with children is browsable`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK, childCount = 5))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `create audiobook without children is not browsable`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO_BOOK, childCount = 0))

        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `create folder is browsable and not playable`() {
        val item = factory.create(baseItem(BaseItemKind.FOLDER))

        assertTrue(item.mediaMetadata.isBrowsable == true)
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `create unsupported type throws exception`() {
        factory.create(baseItem(BaseItemKind.MOVIE))
    }

    @Test
    fun `create audio track with audiobook flag preserves flag`() {
        val item = factory.create(baseItem(BaseItemKind.AUDIO), isAudiobook = true)

        assertTrue(item.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
    }

    @Test
    fun `create sets mediaId from item id`() {
        val dto = baseItem(BaseItemKind.AUDIO)
        val item = factory.create(dto)

        assertEquals(dto.id.toString(), item.mediaId)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :automotive:testDebugUnitTest --tests "com.chamika.dashtune.media.MediaItemFactoryTest" --quiet`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add automotive/src/test/java/com/chamika/dashtune/media/MediaItemFactoryTest.kt
git commit -m "test: add MediaItemFactory tests for item creation per type"
```

---

### Task 5: Write MediaRepository tests

**Files:**
- Create: `automotive/src/test/java/com/chamika/dashtune/data/MediaRepositoryTest.kt`

- [ ] **Step 1: Create the test file**

Create `automotive/src/test/java/com/chamika/dashtune/data/MediaRepositoryTest.kt`:

```kotlin
package com.chamika.dashtune.data

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaRepositoryTest {

    private lateinit var dao: MediaCacheDao
    private lateinit var tree: JellyfinMediaTree
    private lateinit var itemFactory: MediaItemFactory
    private lateinit var repository: MediaRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        tree = mockk(relaxed = true)
        itemFactory = mockk(relaxed = true)
        every { itemFactory.streamingUri(any()) } returns "http://server/audio/stream"
        repository = MediaRepository(dao, tree, itemFactory)
    }

    // --- getItem tests ---

    @Test
    fun `getItem returns cached item when DAO has data`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "My Track",
            subtitle = "Artist Name",
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = 180000L,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertEquals("track-1", result.mediaId)
        assertEquals("My Track", result.mediaMetadata.title.toString())
        assertEquals("Artist Name", result.mediaMetadata.albumArtist.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, result.mediaMetadata.mediaType)
        assertTrue(result.mediaMetadata.isPlayable == true)
        assertFalse(result.mediaMetadata.isBrowsable == true)
    }

    @Test
    fun `getItem falls back to tree when not cached`() = runTest {
        val treeItem = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Tree Track")
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        coEvery { dao.getItem("track-1") } returns null
        coEvery { tree.getItem("track-1") } returns treeItem

        val result = repository.getItem("track-1")

        assertEquals("track-1", result.mediaId)
        assertEquals("Tree Track", result.mediaMetadata.title.toString())
    }

    @Test
    fun `getItem returns static item for ROOT_ID`() = runTest {
        val rootItem = MediaItem.Builder()
            .setMediaId("ROOT_ID")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Root")
                    .build()
            )
            .build()

        coEvery { tree.getItem("ROOT_ID") } returns rootItem

        val result = repository.getItem("ROOT_ID")

        assertEquals("ROOT_ID", result.mediaId)
    }

    // --- Cache roundtrip tests ---

    @Test
    fun `cached playable music item gets streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertNotNull(result.localConfiguration)
        assertEquals("http://server/audio/stream", result.localConfiguration?.uri.toString())
    }

    @Test
    fun `cached audiobook item gets streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "book-1",
            parentId = "books",
            title = "Audiobook",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = """{"is_audiobook":true}"""
        )

        coEvery { dao.getItem("book-1") } returns entity

        val result = repository.getItem("book-1")

        assertNotNull(result.localConfiguration)
    }

    @Test
    fun `cached non-playable item has no URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "artist-1",
            parentId = "root",
            title = "Artist",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
            isPlayable = false,
            isBrowsable = true,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("artist-1") } returns entity

        val result = repository.getItem("artist-1")

        assertNull(result.localConfiguration)
    }

    @Test
    fun `cached item preserves extras including audiobook flag`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "chapter-1",
            parentId = "book-1",
            title = "Chapter 1",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = """{"is_audiobook":true,"PARENT_KEY":"book-1"}"""
        )

        coEvery { dao.getItem("chapter-1") } returns entity

        val result = repository.getItem("chapter-1")

        assertTrue(result.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true)
        assertEquals("book-1", result.mediaMetadata.extras?.getString(PARENT_KEY))
    }

    @Test
    fun `cached item preserves favorite status`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "fav",
            title = "Fav Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = true,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        val rating = result.mediaMetadata.userRating as? HeartRating
        assertNotNull(rating)
        assertTrue(rating!!.isHeart)
    }

    @Test
    fun `cached item preserves duration`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "track-1",
            parentId = "album-1",
            title = "Track",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = 240000L,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("track-1") } returns entity

        val result = repository.getItem("track-1")

        assertEquals(240000L, result.mediaMetadata.durationMs)
    }

    @Test
    fun `cached playlist item has no streaming URI`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "playlist-1",
            parentId = "playlists",
            title = "My Playlist",
            subtitle = null,
            artUri = null,
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 0,
            durationMs = null,
            isFavorite = false,
            extras = null
        )

        coEvery { dao.getItem("playlist-1") } returns entity

        val result = repository.getItem("playlist-1")

        assertNull(result.localConfiguration)
    }

    // --- getChildren tests ---

    @Test
    fun `getChildren returns cached items when available`() = runTest {
        val entities = listOf(
            CachedMediaItemEntity(
                mediaId = "track-1",
                parentId = "album-1",
                title = "Track 1",
                subtitle = null,
                artUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                sortOrder = 0,
                durationMs = null,
                isFavorite = false,
                extras = null
            ),
            CachedMediaItemEntity(
                mediaId = "track-2",
                parentId = "album-1",
                title = "Track 2",
                subtitle = null,
                artUri = null,
                mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
                isPlayable = true,
                isBrowsable = false,
                sortOrder = 1,
                durationMs = null,
                isFavorite = false,
                extras = null
            )
        )

        coEvery { dao.getChildrenByParent("album-1") } returns entities

        val result = repository.getChildren("album-1")

        assertEquals(2, result.size)
        assertEquals("track-1", result[0].mediaId)
        assertEquals("track-2", result[1].mediaId)
    }

    @Test
    fun `getChildren falls back to tree when cache empty`() = runTest {
        val treeItems = listOf(
            MediaItem.Builder()
                .setMediaId("track-1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Track 1")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        )

        coEvery { dao.getChildrenByParent("album-1") } returns emptyList()
        coEvery { tree.getChildren("album-1") } returns treeItems

        val result = repository.getChildren("album-1")

        assertEquals(1, result.size)
        assertEquals("track-1", result[0].mediaId)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :automotive:testDebugUnitTest --tests "com.chamika.dashtune.data.MediaRepositoryTest" --quiet`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add automotive/src/test/java/com/chamika/dashtune/data/MediaRepositoryTest.kt
git commit -m "test: add MediaRepository tests for cache roundtrip and data integrity"
```

---

### Task 6: Clean up and run full test suite

**Files:**
- Delete: `automotive/src/test/java/com/chamika/dashtune/ExampleUnitTest.kt`

- [ ] **Step 1: Delete the placeholder test**

```bash
rm automotive/src/test/java/com/chamika/dashtune/ExampleUnitTest.kt
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew :automotive:testDebugUnitTest --quiet`
Expected: All tests pass (approximately 30+ tests)

- [ ] **Step 3: Verify build still succeeds**

Run: `./gradlew :automotive:assembleDebug --quiet`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "chore: remove placeholder test, all regression tests passing"
```
