# Offline Media Cache Design

## Problem

When the car starts, internet connectivity takes time to establish. The app immediately tries to load the media tree (Latest Albums, Random Albums, Favourites, Playlists) from the Jellyfin server. These calls fail, and the app enters a bad state with no way to recover without restarting.

## Solution

Cache the full browsable media tree in a local Room database. On startup, serve cached data immediately. Sync with the server when network is available (auto-sync if >6 hours since last sync, or manually via a sync button).

## Architecture: Option B — Separate MediaRepository Layer

`MediaRepository` sits between `DashTuneSessionCallback` and `JellyfinMediaTree`. It owns the cache-first / network-fallback logic, Room DAO, and sync coordination. `JellyfinMediaTree` remains a pure network client.

## Room Database Schema

### Entity: `CachedMediaItemEntity`

| Column | Type | Description |
|--------|------|-------------|
| `mediaId` | String (PK) | Jellyfin item UUID |
| `parentId` | String (indexed) | Section/parent it belongs to |
| `title` | String | Display title |
| `subtitle` | String? | Artist name or description |
| `artUri` | String? | Album art content:// URI |
| `mediaType` | Int | MediaMetadata.MEDIA_TYPE_* constant |
| `isPlayable` | Boolean | Whether the item is directly playable |
| `isBrowsable` | Boolean | Whether item has children |
| `sortOrder` | Int | Preserve ordering within parent |
| `extras` | String? | JSON blob for additional metadata (parent key, group title, etc.) |

### Parent ID Mapping

| Cached Item | parentId value |
|-------------|----------------|
| Albums inside "Latest Albums" | `LATEST_ALBUMS` |
| Albums inside "Random Albums" | `RANDOM_ALBUMS` |
| Tracks/albums/artists inside "Favourites" | `FAVOURITES` |
| Playlists inside "Playlists" | `PLAYLISTS` |
| Tracks inside a specific album | Album's UUID |
| Tracks inside a specific playlist | Playlist's UUID |
| Albums under an artist | Artist's UUID |

### DAO: `MediaCacheDao`

- `getChildrenByParent(parentId: String): List<CachedMediaItemEntity>`
- `insertAll(items: List<CachedMediaItemEntity>)`
- `deleteByParent(parentId: String)`
- `deleteAll()`
- `hasData(): Boolean`

### Database: `DashTuneDatabase`

Single Room database with `CachedMediaItemEntity` table.

## MediaRepository

### Dependencies
- `MediaCacheDao`
- `JellyfinMediaTree`
- `ConnectivityManager`

### Cache-First Strategy

```
getChildren(parentId):
  1. Check DB for cached items under parentId
  2. If cache exists -> return cached items immediately
  3. If cache is empty -> try network via JellyfinMediaTree
     - On success -> save to DB, return items
     - On failure -> return empty list
```

### Recursive Sync Flow

```
sync():
  1. Fetch all 4 root sections' children from network
  2. For each browsable item (album, playlist, artist):
     - Fetch its children (tracks) from network
     - For artists: fetch their albums, then each album's tracks
  3. Within a DB transaction: clear all cached data, insert everything
  4. Notify session via notifyChildrenChanged()
  5. Show toast: "Library synced"
```

Sync depth:
- Level 0: Root -> 4 sections
- Level 1: Section -> albums / playlists / artists / tracks
- Level 2: Album/playlist -> tracks, Artist -> albums
- Level 3: Artist's album -> tracks

No item count limits — full library is synced.

### Key Behaviors
- `getItem(id)` — same cache-first pattern
- `search(query)` — always network, no offline search
- Sync protected by `Mutex` to prevent concurrent syncs
- On sync failure, old cached data is preserved (only replace within transaction on full success)

## Sync Button

- Custom `SYNC_COMMAND` in `DashTuneSessionCallback`
- `CommandButton` with `SLOT_OVERFLOW` (alongside shuffle/repeat)
- Icon: refresh/sync icon
- Always runs sync regardless of timestamp

## Auto-Sync on Connectivity

- `ConnectivityManager.NetworkCallback` registered in `DashTuneMusicService.onCreate()`
- `lastSyncTimestamp` stored in SharedPreferences
- On `onAvailable`:
  - If last sync > 6 hours ago (or never synced) -> trigger sync
  - Otherwise -> do nothing
- On successful sync -> update `lastSyncTimestamp`, show toast: "Library synced"
- Callback unregistered in `onDestroy()`

## Startup Behavior

| Scenario | Behavior |
|----------|----------|
| Offline, has cache | Show cached data immediately |
| Offline, no cache (first launch) | Show empty/auth error state |
| Online, sync < 6h ago | Show cached data, no auto-sync |
| Online, sync > 6h ago | Show cached data, auto-sync in background, refresh UI on completion |
| User presses sync button | Always sync, update timestamp |

## File Changes

### New Files

| File | Purpose |
|------|---------|
| `data/db/DashTuneDatabase.kt` | Room database class |
| `data/db/MediaCacheDao.kt` | DAO for cached media items |
| `data/db/CachedMediaItemEntity.kt` | Room entity |
| `data/MediaRepository.kt` | Cache-first logic, sync coordination |
| `di/DatabaseModule.kt` | Hilt module providing Room DB and DAO |

### Modified Files

| File | Changes |
|------|---------|
| `JellyfinMediaTree.kt` | Remove MAX_ITEMS limit |
| `DashTuneSessionCallback.kt` | Use MediaRepository instead of JellyfinMediaTree; add SYNC_COMMAND |
| `CommandButtons.kt` | Add sync button with SLOT_OVERFLOW |
| `DashTuneMusicService.kt` | Register/unregister NetworkCallback; trigger auto-sync |
| `DashTuneModule.kt` | Provide MediaRepository dependencies |
| `build.gradle.kts` | Add Room dependencies + KSP processor |

### Unchanged
- `MediaItemFactory.kt`
- `AlbumArtContentProvider.kt`
- Auth/sign-in code
- Search remains network-only

### Dependencies Added
- `androidx.room:room-runtime`
- `androidx.room:room-ktx`
- `androidx.room:room-compiler` (KSP)
