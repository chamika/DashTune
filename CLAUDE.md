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
| Browse Categories | `browse_categories` | Latest,Favourites,Books,Playlists | Min 2, max 4 from: Latest, Favourites, Books, Playlists, Random |

## Manifest Components
- **DashTuneMusicService**: `foregroundServiceType="mediaPlayback"`, intent filters for Media3 + legacy MediaBrowserService
- **SignInActivity**: `android.intent.action.ACTION_SIGN_IN`
- **SettingsActivity**: `android.intent.action.APPLICATION_PREFERENCES`
- **AuthenticatorService**: `android.accounts.AccountAuthenticator`
- **AlbumArtContentProvider**: authority `com.chamika.dashtune`
- Requires `android.hardware.type.automotive`
