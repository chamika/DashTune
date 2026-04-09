# Audiobook Support — Design Spec

**Issue:** [#8 — Audiobooks not being displayed](https://github.com/chamika/DashTune/issues/8)

## Problem

DashTune only browses and plays music-typed content from Jellyfin. Users with audiobook libraries (Jellyfin `BaseItemKind.AUDIO_BOOK`) cannot see or play their books. Additionally, audiobooks require chapter-level playback position tracking (synced to the server), which the current implementation lacks.

## Approach

Extend the existing media tree, factory, and playback infrastructure to handle audiobooks as a first-class content type. Add a configurable categories setting so users choose which 2–4 browse categories appear. Enhance playback position reporting with 30-second server sync for audiobook chapters.

## 1. Configurable Browse Categories

### Current State

`JellyfinMediaTree.getChildren(ROOT_ID)` returns a hardcoded list: Latest Albums, Random Albums, Favourites, Playlists.

### Design

**New constant:** `BOOKS_ID` in `MediaItemFactory.Companion`.

**New setting:** `MultiSelectListPreference` in `preferences.xml` with key `"browse_categories"`:

| Entry       | Value        |
|-------------|--------------|
| Latest      | `latest`     |
| Favourites  | `favourites` |
| Books       | `books`      |
| Playlists   | `playlists`  |
| Random      | `random`     |

- **Default selected:** `latest`, `favourites`, `books`, `playlists`
- **Constraints:** Min 2, max 4 selections. Enforced in `SettingsFragment` via `OnPreferenceChangeListener` — reject changes that violate the constraint with a toast message.
- **Display order is canonical:** Regardless of selection order, categories always appear as Latest → Favourites → Books → Playlists → Random in the media tree.

**`JellyfinMediaTree.getChildren(ROOT_ID)`** reads the `browse_categories` preference and returns only the selected categories in canonical order.

**`MediaRepository.sync()`** also reads the preference so it only syncs the active categories.

**`MediaItemFactory.books()`** creates the Books category node:
- Icon: `ic_book` (new drawable resource)
- `MEDIA_TYPE_FOLDER_MIXED` (contains browsable audiobooks, not directly playable)
- Grid content style for children

## 2. Audiobook Browsing & Media Tree

### API Integration

**`JellyfinMediaTree.getBooks()`** — New method:
```
api.itemsApi.getItems(
    includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
    recursive = true,
    sortBy = listOf(ItemSortBy.SORT_NAME),
    limit = MAX_ITEMS
)
```

This returns all audiobooks across the user's libraries. The hierarchical structure (sub-folders, collections) is handled by the existing `getItemChildren()` method when the user browses deeper into a specific audiobook.

### MediaItemFactory Changes

**`create()` method** — Add `BaseItemKind.AUDIO_BOOK` case dispatching to new `forAudiobook()`.

**`forAudiobook()`** — Similar to `forAlbum()`:
- `isBrowsable = false`, `isPlayable = true` (so tapping plays from start; AAOS handles the album-like expansion)
- `mediaType = MEDIA_TYPE_ALBUM` — ensures `resolveMediaItems` in the callback correctly expands it into child tracks
- Uses same `artUri()` logic for cover art
- Sets `"is_audiobook" = true` in extras on the audiobook container itself

**`groupForItem()`** — Add `AUDIO_BOOK` case returning the `R.string.books` group string.

### Child Item Handling

Audiobook chapters are returned by Jellyfin as `AUDIO` items when querying children of an `AUDIO_BOOK`. The existing `getItemChildren()` method already handles this via `parentId` queries. Chapters flow through the existing `forTrack()` path, which produces playable `MEDIA_TYPE_MUSIC` items with streaming URIs.

**Audiobook flag propagation:** Add an `isAudiobook: Boolean = false` parameter to both `create()` and `forTrack()`. When `JellyfinMediaTree.getItemChildren()` resolves children of an `AUDIO_BOOK` parent, it passes `isAudiobook = true` to `itemFactory.create()`. The `forTrack()` method then sets `"is_audiobook" = true` in the track's extras bundle. This allows downstream code (playback position, shuffle) to detect audiobook tracks.

### Search

Add audiobook search to `JellyfinMediaTree.search()`:
```
api.itemsApi.getItems(
    recursive = true,
    searchTerm = query,
    includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
    limit = 10
)
```
With group label from `R.string.books`.

### Sync

`MediaRepository.sync()` includes `BOOKS_ID` in the synced sections when Books is an active category. `syncChildrenRecursively` handles audiobook children (chapters) the same way it handles album tracks — no special casing needed.

## 3. Playback Position Tracking

### 30-Second Progress Reporting

A new `Runnable` (`audiobookProgressPoll`) in `DashTuneMusicService`:
- Runs every **30 seconds** while an audiobook track is playing
- Calls `playStateApi.reportPlaybackProgress(PlaybackProgressInfo(...))` with current `positionTicks`
- Started/stopped alongside the existing 1-second `playbackPoll`
- Only active when `isPlayingAudiobook` is true

### Detecting Audiobook Content

When a track starts playing (`EVENT_MEDIA_ITEM_TRANSITION`), check the `"is_audiobook"` extra on the current media item. Store a boolean `isPlayingAudiobook` flag on the service.

### Resume from Server Position

When the user taps an audiobook (triggering `onSetMediaItems` → `expandSingleItem`):

1. Expand the audiobook into its chapter list (the existing flow)
2. Query each chapter's `userData` via `jellyfinApi.itemsApi.getItems(parentId = bookId, fields = listOf(ItemFields.USER_DATA))` to find the last-played chapter — identified by the chapter with `userData.playbackPositionTicks > 0` and the most recent `userData.lastPlayedDate`
3. **3-second timeout** — Wrap the API call in `withTimeoutOrNull(3000)`. If it fails or times out, fall back to locally saved position.
4. Use the server data to set `startIndex` (the last-played chapter's index in the expanded list) and `startPositionMs` (derived from `playbackPositionTicks / 10_000`)
5. Return these in the `MediaItemsWithStartPosition`

### Local Fallback

The existing SharedPreferences-based position tracking (`PLAYLIST_TRACK_POSITON_MS_PREF`, `PLAYLIST_INDEX_PREF`) serves as the local fallback. For audiobooks specifically, also save the book's mediaId (key: `"audiobook_last_id"`) alongside the position so we can distinguish book positions from music positions.

## 4. Shuffle Behavior for Audiobooks

When content switches to audiobook:
1. Save current shuffle state to SharedPreferences (`"shuffle_before_audiobook"`)
2. Force `player.shuffleModeEnabled = false`
3. Update command buttons via `session.setMediaButtonPreferences()`

When content switches back to non-audiobook (music):
1. Restore shuffle state from `"shuffle_before_audiobook"`
2. Update command buttons

Detection point: `onSetMediaItems` in `DashTuneSessionCallback`, after resolving the media items. Check if the resolved content contains audiobook tracks (via the `"is_audiobook"` extra).

## 5. Offline Downloads for Books

**No changes needed.** The existing prefetch pipeline is content-type-agnostic:
- `prefetchNextTracks()` in `DashTuneMusicService` works on streaming URIs regardless of content type
- Audiobook chapters are resolved as `AUDIO` items with streaming URIs
- The `prefetch_count` and `cache_size` settings apply uniformly
- Audiobook files (typically lower bitrate than music) work well within existing cache sizes

## Files Changed

| File | Change |
|------|--------|
| `MediaItemFactory.kt` | Add `BOOKS_ID` constant, `books()` category node, `forAudiobook()` method, update `create()` |
| `JellyfinMediaTree.kt` | Add `getBooks()`, update `getChildren(ROOT_ID)` to read preference, update `getItem()` for BOOKS, update `search()` |
| `MediaRepository.kt` | Add `BOOKS_ID` to `staticIds`, update `sync()` to read preference for active categories |
| `DashTuneSessionCallback.kt` | Update `onSetMediaItems` for audiobook resume position + shuffle override |
| `DashTuneMusicService.kt` | Add `audiobookProgressPoll`, `isPlayingAudiobook` flag, shuffle save/restore logic |
| `SettingsFragment.kt` | Add min/max validation for `browse_categories` preference |
| `preferences.xml` | Add `MultiSelectListPreference` for browse categories |
| `strings.xml` | Add strings: "Books", "Browse categories", validation messages |
| `arrays.xml` | Add category entries/values arrays |
| New drawable: `ic_book.xml` | Book icon for the Books category |

## Not In Scope

- Per-book position persistence across app reinstalls (server handles this)
- Audiobook-specific playback speed controls (potential future feature)
- Separate "audiobook" media type in the AAOS UI (we reuse the album paradigm)
