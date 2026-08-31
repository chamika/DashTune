# DashTune

Jellyfin music and audiobook player for Android Automotive OS (AAOS) with offline download capabilities.

## Testing

```bash
# Unit tests (Robolectric + mockk) — what CI runs on every PR
./gradlew :automotive:testDebugUnitTest

# End-to-end tests against a simulated Jellyfin server.
# Needs a booted AAOS emulator: the manifest requires android.hardware.type.automotive,
# so the APK will not install on a phone image.
$ANDROID_HOME/emulator/emulator -avd Automotive_Portrait -no-window -no-audio -no-snapshot &
./gradlew :automotive:connectedDebugAndroidTest
```

The E2E suite (`automotive/src/androidTest/`) runs a `FakeJellyfinServer` (MockWebServer) in
the app's own process on `127.0.0.1`, points `DashTuneMusicService` at it by storing an
account and sending `LOGIN_COMMAND`, then drives a real `MediaBrowser` — the same interface
an AAOS head unit uses. It covers browsing, pagination, playback, audiobooks and failure
modes. Fixtures live in `androidTest/.../fake/`; add a route to `FakeJellyfinServer` when the
app starts calling a new Jellyfin endpoint (unhandled routes return 501 and are recorded).

Tests that assert audio actually plays construct the rule with `foregroundForPlayback = true`
— Android 15's audio focus hardening denies focus to an app whose only running component is a
bound service.

## Releasing

Use the `release` skill — it bumps `versionCode`/`versionName` in
`automotive/build.gradle.kts` and writes the required commit message format.

## Architecture

### Media Service Flow
1. `DashTuneMusicService` (MediaLibraryService) creates ExoPlayer + MediaLibrarySession in `onCreate()`
2. `DashTuneSessionCallback` handles browsing (onGetLibraryRoot, onGetChildren, onSearch) and playback commands
3. `JellyfinMediaTree` builds the browsable hierarchy from user-configured categories (Latest, Favourites, Books, Playlists, Random)
4. `MediaItemFactory` converts Jellyfin `BaseItemDto` to Media3 `MediaItem` for artists, albums, playlists, tracks, and audiobooks
5. `AlbumArtContentProvider` serves album art via `content://` URIs so the AAOS system UI can display them

### Authentication
- Android AccountManager stores server URL + access token
- Two auth methods: QuickConnect (polling) and username/password
- `SignInActivity` → `ServerSignInFragment` (ping) → `CredentialsFragment` (auth)
- On login success, sends `LOGIN_COMMAND` to service which updates API client and refreshes media tree

### Offline & Caching
- ExoPlayer `CacheDataSource` wraps HTTP requests with disk cache (LRU eviction)
- Cache size configurable: 100MB - 2GB (default 200MB)
- On track change, prefetches next 5 tracks via `DownloadManager`
- Playback position saved every 1s, restored on playback resumption

### Audiobook Support
- `MediaItemFactory.forAudiobook()` creates browsable/playable items with `IS_AUDIOBOOK_KEY` metadata flag
- Browse hierarchy: Books category → Folders/Collections → Individual books → Chapters
- Multi-chapter audiobooks expand into ordered playlists via `expandSingleItem()` in `DashTuneSessionCallback`
- Position saved to Jellyfin server via `itemsApi.updateItemUserData()` (UserData API) on pause/stop
- Position restored from server via `userLibraryApi.getItem()` → `userData.playbackPositionTicks`
- AAOS completion status extras show progress bars on chapter items in browse UI
- Shuffle auto-disabled when audiobook content detected, restored when switching to music
- `MediaRepository` caches audiobook items in Room DB with parent relationships for offline browsing

### Playback State Persistence
- Playlist track IDs, current index, position, repeat mode, shuffle state saved to SharedPreferences
- Restored via `onPlaybackResumption()` callback

## Key Jellyfin APIs Used
- `userLibraryApi` - Browse library, latest items, favourites, get item userData
- `itemsApi` - Query/search items, update user data (audiobook position persistence)
- `artistsApi` - Album artists
- `playStateApi` - Report playback start/stop (session tracking)
- `universalAudioApi` - Streaming URLs with transcoding
- `systemApi` - Server ping
- `quickConnectApi` - QuickConnect auth flow
- `ImageApi` - Album art URLs
