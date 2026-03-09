# Offline Media Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Cache the full browsable media tree in a local Room database so the app works immediately on startup without network, and syncs when connectivity is available.

**Architecture:** A `MediaRepository` layer sits between `DashTuneSessionCallback` and `JellyfinMediaTree`. It implements cache-first reads from Room DB, falling back to network via `JellyfinMediaTree`. Sync is triggered manually (button) or automatically (on network if >6h since last sync). The browse tree is recursively synced including tracks within albums/playlists.

**Tech Stack:** Room (SQLite), Hilt, ConnectivityManager, Media3 CommandButton

**Design Doc:** `docs/plans/2026-03-07-offline-media-cache-design.md`

---

### Task 1: Add Room Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `automotive/build.gradle.kts`

**Step 1: Add Room version and libraries to version catalog**

In `gradle/libs.versions.toml`, add:

```toml
# In [versions] section, add:
room = "2.7.1"

# In [libraries] section, add:
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

**Step 2: Add Room dependencies to build.gradle.kts**

In `automotive/build.gradle.kts`, add to `dependencies` block:

```kotlin
implementation(libs.androidx.room.runtime)
implementation(libs.androidx.room.ktx)
ksp(libs.androidx.room.compiler)
```

**Step 3: Build to verify dependencies resolve**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml automotive/build.gradle.kts
git commit -m "feat: add Room database dependencies"
```

---

### Task 2: Create Room Entity

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/data/db/CachedMediaItemEntity.kt`

**Step 1: Create the entity class**

```kotlin
package com.chamika.dashtune.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "cached_media_items",
    primaryKeys = ["mediaId", "parentId"]
)
data class CachedMediaItemEntity(
    val mediaId: String,
    @ColumnInfo(index = true)
    val parentId: String,
    val title: String,
    val subtitle: String?,
    val artUri: String?,
    val mediaType: Int,
    val isPlayable: Boolean,
    val isBrowsable: Boolean,
    val sortOrder: Int,
    val durationMs: Long?,
    val isFavorite: Boolean,
    val extras: String?
)
```

Notes:
- Composite PK `(mediaId, parentId)` because the same item can appear under multiple parents (e.g., album in both Latest and Favourites).
- `extras` stores a JSON string with Bundle data like group title, parent key, content style hints.
- `subtitle` stores `albumArtist` for albums/tracks.
- `durationMs` and `isFavorite` are used for track metadata.

**Step 2: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/data/db/CachedMediaItemEntity.kt
git commit -m "feat: add CachedMediaItemEntity Room entity"
```

---

### Task 3: Create Room DAO

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/data/db/MediaCacheDao.kt`

**Step 1: Create the DAO interface**

```kotlin
package com.chamika.dashtune.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MediaCacheDao {

    @Query("SELECT * FROM cached_media_items WHERE parentId = :parentId ORDER BY sortOrder ASC")
    suspend fun getChildrenByParent(parentId: String): List<CachedMediaItemEntity>

    @Query("SELECT * FROM cached_media_items WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getItem(mediaId: String): CachedMediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedMediaItemEntity>)

    @Query("DELETE FROM cached_media_items WHERE parentId = :parentId")
    suspend fun deleteByParent(parentId: String)

    @Query("DELETE FROM cached_media_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) > 0 FROM cached_media_items")
    suspend fun hasData(): Boolean
}
```

**Step 2: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/data/db/MediaCacheDao.kt
git commit -m "feat: add MediaCacheDao for cached media items"
```

---

### Task 4: Create Room Database

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/data/db/DashTuneDatabase.kt`

**Step 1: Create the database class**

```kotlin
package com.chamika.dashtune.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CachedMediaItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DashTuneDatabase : RoomDatabase() {
    abstract fun mediaCacheDao(): MediaCacheDao
}
```

**Step 2: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/data/db/DashTuneDatabase.kt
git commit -m "feat: add DashTuneDatabase Room database"
```

---

### Task 5: Create Hilt Database Module

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/di/DatabaseModule.kt`

**Step 1: Create the Hilt module**

```kotlin
package com.chamika.dashtune.di

import android.content.Context
import androidx.room.Room
import com.chamika.dashtune.data.db.DashTuneDatabase
import com.chamika.dashtune.data.db.MediaCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashTuneDatabase {
        return Room.databaseBuilder(
            context,
            DashTuneDatabase::class.java,
            "dashtune_db"
        ).build()
    }

    @Provides
    fun provideMediaCacheDao(database: DashTuneDatabase): MediaCacheDao {
        return database.mediaCacheDao()
    }
}
```

**Step 2: Build to verify Room + Hilt setup compiles**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/di/DatabaseModule.kt
git commit -m "feat: add Hilt DatabaseModule for Room"
```

---

### Task 6: Add `streamingUri()` Helper to MediaItemFactory

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/media/MediaItemFactory.kt`

**Context:** When reconstructing track MediaItems from cache, we need to generate the streaming URL. `getUniversalAudioStreamUrl()` is just URL construction (no network call), so it works offline too. We extract this logic so `MediaRepository` can use it.

**Step 1: Add `streamingUri()` method**

Add this public method to `MediaItemFactory` (after the existing `artUri` method around line 267):

```kotlin
fun streamingUri(mediaId: String): String {
    val preferenceBitrate = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString("bitrate", "Direct stream")!!

    val bitrate = if (preferenceBitrate == "Direct stream") null else preferenceBitrate.toInt()

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
```

**Step 2: Refactor `forTrack()` to use the new method**

In `forTrack()` (line 205-256), replace lines 212-228 with:

```kotlin
val hasOwnImage = item.imageTags?.containsKey(ImageType.PRIMARY) == true
val artUrl = artUri(if (hasOwnImage) item.id else (item.albumId ?: item.id))
val audioStream = streamingUri(item.id.toString())
```

**Step 3: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/media/MediaItemFactory.kt
git commit -m "refactor: extract streamingUri() from forTrack()"
```

---

### Task 8: Create MediaRepository

**Files:**
- Create: `automotive/src/main/java/com/chamika/dashtune/data/MediaRepository.kt`

**Context:** This is the core of the feature. The repository provides cache-first reads and manages sync. It converts between `CachedMediaItemEntity` and Media3 `MediaItem`.

**Step 1: Create the MediaRepository class**

```kotlin
package com.chamika.dashtune.data

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.data.db.CachedMediaItemEntity
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.FAVOURITES
import com.chamika.dashtune.media.MediaItemFactory.Companion.LATEST_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.PARENT_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.PLAYLISTS
import com.chamika.dashtune.media.MediaItemFactory.Companion.RANDOM_ALBUMS
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MediaRepository(
    private val context: Context,
    private val dao: MediaCacheDao,
    private val tree: JellyfinMediaTree,
    private val itemFactory: MediaItemFactory
) {

    private val syncMutex = Mutex()

    suspend fun getItem(id: String): MediaItem {
        if (id == ROOT_ID || id == LATEST_ALBUMS || id == RANDOM_ALBUMS ||
            id == FAVOURITES || id == PLAYLISTS
        ) {
            return tree.getItem(id)
        }

        val cached = dao.getItem(id)
        if (cached != null) {
            return cached.toMediaItem()
        }

        return tree.getItem(id)
    }

    suspend fun getChildren(parentId: String): List<MediaItem> {
        if (parentId == ROOT_ID) {
            return tree.getChildren(ROOT_ID)
        }

        val cached = dao.getChildrenByParent(parentId)
        if (cached.isNotEmpty()) {
            return cached.map { it.toMediaItem() }
        }

        return try {
            val networkItems = tree.getChildren(parentId)
            cacheItems(parentId, networkItems)
            networkItems
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to get children for $parentId from network", e)
            emptyList()
        }
    }

    suspend fun search(query: String): List<MediaItem> {
        return tree.search(query)
    }

    suspend fun sync(): Boolean {
        return syncMutex.withLock {
            try {
                Log.i(LOG_TAG, "Starting library sync")

                val allEntities = mutableListOf<CachedMediaItemEntity>()
                val sectionIds = listOf(LATEST_ALBUMS, RANDOM_ALBUMS, FAVOURITES, PLAYLISTS)

                for (sectionId in sectionIds) {
                    try {
                        val children = tree.getChildren(sectionId)
                        children.forEachIndexed { index, item ->
                            allEntities.add(item.toEntity(sectionId, index))

                            if (item.mediaMetadata.isBrowsable == true ||
                                item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                                item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST ||
                                item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST
                            ) {
                                syncChildrenRecursively(item.mediaId, allEntities)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to sync section $sectionId", e)
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }

                dao.deleteAll()
                dao.insertAll(allEntities)

                Log.i(LOG_TAG, "Library sync complete: ${allEntities.size} items cached")
                true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Library sync failed", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                false
            }
        }
    }

    private suspend fun syncChildrenRecursively(
        parentId: String,
        allEntities: MutableList<CachedMediaItemEntity>
    ) {
        try {
            val children = tree.getChildren(parentId)
            children.forEachIndexed { index, item ->
                allEntities.add(item.toEntity(parentId, index))

                if (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST) {
                    syncChildrenRecursively(item.mediaId, allEntities)
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to sync children for $parentId", e)
        }
    }

    private suspend fun cacheItems(parentId: String, items: List<MediaItem>) {
        val entities = items.mapIndexed { index, item ->
            item.toEntity(parentId, index)
        }
        dao.deleteByParent(parentId)
        dao.insertAll(entities)
    }

    private fun MediaItem.toEntity(parentId: String, sortOrder: Int): CachedMediaItemEntity {
        val extrasJson = mediaMetadata.extras?.let { bundle ->
            val json = JSONObject()
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                }
            }
            json.toString()
        }

        return CachedMediaItemEntity(
            mediaId = mediaId,
            parentId = parentId,
            title = mediaMetadata.title?.toString() ?: "",
            subtitle = mediaMetadata.albumArtist?.toString(),
            artUri = mediaMetadata.artworkUri?.toString(),
            mediaType = mediaMetadata.mediaType ?: 0,
            isPlayable = mediaMetadata.isPlayable ?: false,
            isBrowsable = mediaMetadata.isBrowsable ?: false,
            sortOrder = sortOrder,
            durationMs = mediaMetadata.durationMs,
            isFavorite = (mediaMetadata.userRating as? HeartRating)?.isHeart ?: false,
            extras = extrasJson
        )
    }

    private fun CachedMediaItemEntity.toMediaItem(): MediaItem {
        val bundle = extras?.let { json ->
            Bundle().apply {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    when (val value = obj.get(key)) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setAlbumArtist(subtitle)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(isPlayable)
            .setMediaType(mediaType)
            .setDurationMs(durationMs)
            .setUserRating(HeartRating(isFavorite))

        artUri?.let { metadataBuilder.setArtworkUri(it.toUri()) }
        bundle?.let { metadataBuilder.setExtras(it) }

        val metadata = metadataBuilder.build()

        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)

        if (isPlayable && mediaType == MediaMetadata.MEDIA_TYPE_MUSIC) {
            builder.setUri(itemFactory.streamingUri(mediaId))
        }

        return builder.build()
    }
}
```

**Step 2: Build to verify**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/data/MediaRepository.kt
git commit -m "feat: add MediaRepository with cache-first reads and recursive sync"
```

---

### Task 9: Modify DashTuneSessionCallback to Use MediaRepository

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt`

**Context:** Replace direct `JellyfinMediaTree` usage with `MediaRepository`. The callback receives `MediaCacheDao` from the service and creates the repository alongside the tree in `ensureTreeInitialized()`.

**Step 1: Add MediaCacheDao parameter and SYNC_COMMAND**

Change the constructor to accept `MediaCacheDao`:

```kotlin
class DashTuneSessionCallback(
    private val service: DashTuneMusicService,
    private val accountManager: JellyfinAccountManager,
    private val jellyfinApi: ApiClient,
    private val mediaCacheDao: MediaCacheDao
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        const val LOGIN_COMMAND = "com.chamika.dashtune.COMMAND.LOGIN"
        const val REPEAT_COMMAND = "com.chamika.dashtune.COMMAND.REPEAT"
        const val SHUFFLE_COMMAND = "com.chamika.dashtune.COMMAND.SHUFFLE"
        const val SYNC_COMMAND = "com.chamika.dashtune.COMMAND.SYNC"

        const val PLAYLIST_IDS_PREF = "playlistIds"
        const val PLAYLIST_INDEX_PREF = "playlistIndex"
        const val PLAYLIST_TRACK_POSITON_MS_PREF = "playlistTrackPositionMs"
    }
```

**Step 2: Replace `tree` with `repository`**

Change the lateinit field:

```kotlin
private lateinit var repository: MediaRepository
```

**Step 3: Update `ensureTreeInitialized()` to create repository**

```kotlin
private fun ensureTreeInitialized(artSizeHint: Int? = null) {
    if (!::repository.isInitialized) {
        val artSize = artSizeHint ?: 1024
        Log.d(LOG_TAG, "Initializing media tree with art size: $artSize")

        val itemFactory = MediaItemFactory(service, jellyfinApi, artSize)
        val tree = JellyfinMediaTree(service, jellyfinApi, itemFactory)
        repository = MediaRepository(service, mediaCacheDao, tree, itemFactory)
    }
}
```

**Step 4: Replace all `tree.` calls with `repository.` calls**

In `onGetLibraryRoot` (line 106): `tree.getItem(ROOT_ID)` → `repository.getItem(ROOT_ID)`

In `onGetChildren` (line 142): `tree.getChildren(parentId)` → `repository.getChildren(parentId)`

In `onGetItem` (line 181): `tree.getItem(mediaId)` → `repository.getItem(mediaId)`

In `resolveMediaItems` (line 258): `tree.getItem(it.mediaId)` → `repository.getItem(it.mediaId)`

In `resolveMediaItems` (line 262): `tree.getChildren(item.mediaId)` → `repository.getChildren(item.mediaId)`

In `isSingleItemWithParent` (line 246): `tree.getItem(mediaItems[0].mediaId)` → `repository.getItem(mediaItems[0].mediaId)`

In `expandSingleItem` (line 250-251):
```kotlin
val parentId = repository.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)!!
return resolveMediaItems(repository.getChildren(parentId))
```

In `onSearch` (line 281): `tree.search(query)` → `repository.search(query)`

In `onGetSearchResult` (line 302): `tree.search(query)` → `repository.search(query)`

In `onPlaybackResumption` (line 328): `tree.getItem(it)` → `repository.getItem(it)`

**Step 5: Add SYNC_COMMAND handling in `onCustomCommand`**

Add to the `onConnect` method, in the `sessionCommands` builder:

```kotlin
.add(SessionCommand(SYNC_COMMAND, Bundle()))
```

Add to the `when` block in `onCustomCommand`:

```kotlin
SYNC_COMMAND -> {
    SuspendToFutureAdapter.launchFuture {
        val success = repository.sync()
        if (success) {
            session.notifyChildrenChanged(ROOT_ID, 4, null)
        }
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
}
```

Add missing import:

```kotlin
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.data.db.MediaCacheDao
```

**Step 6: Add a `sync()` method for the service to call**

```kotlin
suspend fun sync(): Boolean {
    if (!::repository.isInitialized) return false
    return repository.sync()
}
```

**Step 7: Build to verify** (will fail until Task 10 updates the service constructor call)

---

### Task 10: Add Sync Command Button

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/CommandButtons.kt`

**Step 1: Add sync button to `createButtons()`**

```kotlin
val sync = CommandButton.Builder(CommandButton.ICON_REFRESH)
    .setDisplayName("Sync Library")
    .setSessionCommand(SessionCommand(SYNC_COMMAND, Bundle.EMPTY))
    .setSlots(CommandButton.SLOT_OVERFLOW)
    .build()

return ImmutableList.of(shuffle, repeat, sync)
```

Add import:

```kotlin
import com.chamika.dashtune.DashTuneSessionCallback.Companion.SYNC_COMMAND
```

**Step 2: Build to verify** (will fail until Task 11 updates the service)

---

### Task 11: Update DashTuneMusicService — Inject DAO, Add NetworkCallback, Auto-Sync

**Files:**
- Modify: `automotive/src/main/java/com/chamika/dashtune/DashTuneMusicService.kt`
- Modify: `automotive/src/main/res/values/strings.xml`

**Step 1: Add string resource for sync toast**

In `strings.xml`, add:

```xml
<string name="library_synced">Library synced</string>
```

**Step 2: Inject MediaCacheDao and add NetworkCallback**

Add the injection field:

```kotlin
@Inject
lateinit var mediaCacheDao: MediaCacheDao
```

Add network callback field:

```kotlin
private lateinit var connectivityManager: android.net.ConnectivityManager
private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
```

Add imports:

```kotlin
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import com.chamika.dashtune.data.db.MediaCacheDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

Add a service-scoped coroutine scope:

```kotlin
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

**Step 3: Update callback construction to pass DAO**

In `onCreate()`, change line 182:

```kotlin
callback = DashTuneSessionCallback(this, accountManager, jellyfinApi, mediaCacheDao)
```

**Step 4: Register NetworkCallback in `onCreate()`**

Add after `if (accountManager.isAuthenticated)` block, at end of `onCreate()`:

```kotlin
connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
val networkRequest = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        Log.i(LOG_TAG, "Network available")
        if (!accountManager.isAuthenticated) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService)
        val lastSync = prefs.getLong("last_sync_timestamp", 0L)
        val sixHoursMs = 6 * 60 * 60 * 1000L

        if (System.currentTimeMillis() - lastSync > sixHoursMs) {
            serviceScope.launch {
                val success = callback.sync()
                if (success) {
                    prefs.edit { putLong("last_sync_timestamp", System.currentTimeMillis()) }
                    mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
                    Toast.makeText(
                        this@DashTuneMusicService,
                        R.string.library_synced,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
```

**Step 5: Update SYNC_COMMAND handler to update timestamp**

In `DashTuneSessionCallback.onCustomCommand`, update the SYNC_COMMAND case:

```kotlin
SYNC_COMMAND -> {
    return SuspendToFutureAdapter.launchFuture {
        val success = repository.sync()
        if (success) {
            PreferenceManager.getDefaultSharedPreferences(service).edit {
                putLong("last_sync_timestamp", System.currentTimeMillis())
            }
            session.notifyChildrenChanged(ROOT_ID, 4, null)
            Toast.makeText(service, R.string.library_synced, Toast.LENGTH_SHORT).show()
        }
        SessionResult(SessionResult.RESULT_SUCCESS)
    }
}
```

**Step 6: Unregister callback in `onDestroy()`**

Add before `super.onDestroy()`:

```kotlin
networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
```

**Step 7: Build to verify everything compiles**

Run: `./gradlew :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add automotive/src/main/java/com/chamika/dashtune/DashTuneMusicService.kt \
       automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt \
       automotive/src/main/java/com/chamika/dashtune/CommandButtons.kt \
       automotive/src/main/res/values/strings.xml
git commit -m "feat: integrate MediaRepository, sync button, and auto-sync on connectivity"
```

---

### Task 12: Final Build Verification and Manual Testing

**Step 1: Clean build**

Run: `./gradlew clean :automotive:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Manual test plan (on AAOS emulator or device)**

1. Fresh install → sign in → browse tree loads from network
2. Open overflow menu → verify "Sync Library" button appears alongside Shuffle/Repeat
3. Tap Sync Library → wait for toast "Library synced"
4. Kill app, disconnect network, relaunch → browse tree shows cached data
5. Reconnect network → if >6h since sync, auto-sync runs and toast appears
6. Change bitrate in settings → play a track → verify correct bitrate used (streaming URL regenerated)

**Step 3: Commit any fixes from testing**

---

## Summary of All File Changes

### New Files (5)
1. `automotive/src/main/java/com/chamika/dashtune/data/db/CachedMediaItemEntity.kt`
2. `automotive/src/main/java/com/chamika/dashtune/data/db/MediaCacheDao.kt`
3. `automotive/src/main/java/com/chamika/dashtune/data/db/DashTuneDatabase.kt`
4. `automotive/src/main/java/com/chamika/dashtune/di/DatabaseModule.kt`
5. `automotive/src/main/java/com/chamika/dashtune/data/MediaRepository.kt`

### Modified Files (6)
1. `gradle/libs.versions.toml` — add Room version + libraries
2. `automotive/build.gradle.kts` — add Room dependencies
3. `automotive/src/main/java/com/chamika/dashtune/media/MediaItemFactory.kt` — extract `streamingUri()`
4. `automotive/src/main/java/com/chamika/dashtune/media/JellyfinMediaTree.kt` — remove MAX_ITEMS limit
5. `automotive/src/main/java/com/chamika/dashtune/DashTuneSessionCallback.kt` — use MediaRepository, add SYNC_COMMAND
6. `automotive/src/main/java/com/chamika/dashtune/DashTuneMusicService.kt` — inject DAO, NetworkCallback, auto-sync
7. `automotive/src/main/java/com/chamika/dashtune/CommandButtons.kt` — add sync button
8. `automotive/src/main/res/values/strings.xml` — add sync toast string
