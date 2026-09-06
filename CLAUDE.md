# DashTune

Jellyfin music and audiobook player for Android Automotive OS (AAOS) with offline download capabilities.

## Build & Run

```bash
# Debug build
./gradlew :automotive:assembleDebug

# Release bundle
./gradlew :automotive:bundleRelease

# Install on connected device/emulator
./gradlew :automotive:installDebug
```

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

1. Bump `versionCode` (increment by 1) and `versionName` (semver) in `automotive/build.gradle.kts`
2. Commit **only** `automotive/build.gradle.kts` with this exact message format:

```
Release v<versionName>(<versionCode>)

<Short one-line description of the release>

Full release notes:

- <bullet 1>
- <bullet 2>
```

Example: `Release v1.2.2(18)`

- **Min SDK**: 28 (Android 9)
- **Target SDK**: 36
- **Compile SDK**: 36
- **JVM**: 17
- **Kotlin**: 2.3.10
- **Gradle**: Uses version catalog (`gradle/libs.versions.toml`)

## Project Structure

Single module: `automotive/`

```
automotive/src/main/java/com/chamika/dashtune/
├── DashTuneApplication.kt          # @HiltAndroidApp entry point
├── DashTuneMusicService.kt         # MediaLibraryService - core playback service
├── DashTuneSessionCallback.kt      # Media session callbacks, browsing tree
├── AlbumArtContentProvider.kt      # ContentProvider serving album art to system UI
├── CommandButtons.kt               # Shuffle/repeat command button definitions
├── Constants.kt                    # App constants (LOG_TAG)
├── FirebaseUtils.kt                # Safe Firebase Analytics/Crashlytics wrapper
├── auth/
│   ├── Authenticator.kt            # Android AccountAuthenticator
│   ├── AuthenticatorService.kt     # Authenticator bound service
│   └── JellyfinAccountManager.kt   # Account storage wrapper
├── data/
│   ├── MediaRepository.kt          # Cache layer: syncs Jellyfin items to Room DB
│   └── db/
│       ├── CachedMediaItemEntity.kt # Room entity for cached media items
│       ├── DashTuneDatabase.kt     # Room database definition
│       └── MediaCacheDao.kt        # DAO for media cache queries
├── di/
│   └── DashTuneModule.kt           # Hilt module (Jellyfin SDK, AccountManager, Room)
├── media/
│   ├── JellyfinMediaTree.kt        # Browsable media tree with Guava cache
│   └── MediaItemFactory.kt         # Converts Jellyfin DTOs to Media3 MediaItems
├── signin/
│   ├── SignInActivity.kt           # Sign-in host activity
│   ├── SignInViewModel.kt          # Server ping, QuickConnect, username/password auth
│   ├── ServerSignInFragment.kt     # Server URL input
│   └── CredentialsFragment.kt      # QuickConnect + credentials form
└── settings/
    ├── SettingsActivity.kt         # Settings host activity
    ├── SettingsFragment.kt         # Preference screen
    └── SettingsViewModel.kt        # Version info
```

## Architecture

### Tech Stack
- **Media**: Media3 ExoPlayer + MediaLibraryService (1.9.2)
- **Jellyfin SDK**: `org.jellyfin.sdk:jellyfin-core` (1.8.6)
- **DI**: Hilt (2.59.1) with KSP
- **Networking**: OkHttp 5.3.2 (album art), Jellyfin SDK (API calls)
- **Analytics**: Firebase Analytics + Crashlytics (disabled in debug)
- **UI**: XML layouts, ViewBinding, dark theme

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

### Home Tab
- One browse node (`HOME_ID`) whose children carry `EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE`, which the
  AAOS Media Center renders as titled rows. Sections, in order: Continue listening, Made for you,
  New for you, Jump back in, Mixes.
- **Every tile is a container.** A tile that ends after one track defeats the point, so each one
  resolves to a long queue: the saved music queue, the in-progress book, an artist instant mix, a
  recently added album plus the rest of the newest 50 tracks, a genre shuffle, or a library shuffle.
- Tiles the browser hands back as a bare media id are dispatched on their prefix in
  `MediaItemResolver`: `RADIO_ARTIST:`, `NEW_ALBUM:`, plus the fixed ids `RESUME_QUEUE_ID`,
  `SHUFFLE_FAVOURITES_ID`, `SHUFFLE_NEW_ID`, `SHUFFLE_LIBRARY_ID`. Genre mixes reuse `SHUFFLE_GENRE:`.
- `HomeSections` runs the five sections in parallel, each under its own 5s timeout; a section that
  fails or is slow is dropped so the rest of Home still renders inside the 8s browse timeout.
- Row width comes from the display width alone (`HomeLayout`): 4 tiles at 1200dp and above, else 3.
  AAOS never reports its grid column count. Sections are trimmed to that so none of them wraps.
- Tile *shape* is not controllable. The content style API offers list, grid, category list, category
  grid, a per-item override and group titles, and nothing for artwork shape — the OEM decides. The
  shuffle tiles use `CATEGORY_GRID_ITEM` so their tintable vector icon is drawn with margins.
- Home is cached in memory for 2 minutes and written through to Room for offline. It is invalidated
  when playback stops and when a queue is set, since four of the five sections come from listening
  history. `BrowseCategoriesMigration` adds Home to an existing user's tabs once, dropping the last
  tab if that would exceed four.

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

## User-Configurable Settings
| Setting | Key | Default | Options |
|---------|-----|---------|---------|
| Bitrate | `bitrate` | Direct stream | Direct stream, 320k, 256k, 192k, 160k, 128k |
| Cache Size | `cache_size` | 200 MB | 100, 200, 500, 1024, 2048 MB |
| Offline Song Count | `prefetch_count` | 5 | Off (0), 3, 5, 10, 15, 20 |
| Browse Categories | `browse_categories` | Home,Favourites,Books,Playlists | Min 2, max 4 from: Home, Latest, Favourites, Books, Playlists, Random, Folders, Artists, Albums, Genres |

## Manifest Components
- **DashTuneMusicService**: `foregroundServiceType="mediaPlayback"`, intent filters for Media3 + legacy MediaBrowserService
- **SignInActivity**: `android.intent.action.ACTION_SIGN_IN`
- **SettingsActivity**: `android.intent.action.APPLICATION_PREFERENCES`
- **AuthenticatorService**: `android.accounts.AccountAuthenticator`
- **AlbumArtContentProvider**: authority `com.chamika.dashtune`
- Requires `android.hardware.type.automotive`
