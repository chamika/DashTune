# DashTune

A fully-featured Jellyfin music and audiobook player for Android Automotive OS (AAOS) with offline download capabilities.

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.chamika.dashtune)

## Trailer

[![DashTune trailer](design/dashtune-trailer.gif)](https://www.youtube.com/watch?v=e2Kbul_xeHM)

## Overview

DashTune brings the complete Jellyfin music library experience to your car's infotainment system. Stream your music collection, browse audiobooks, download tracks for offline playback, and enjoy seamless integration with Android Automotive OS.

## Features

### 🎵 Music Streaming & Playback
- **Media3 ExoPlayer** - Modern, high-performance audio playback
- **Home Tab** - Titled rows (Continue listening, Made for you, New for you, Jump back in, Mixes) of tiles that each start a long queue, plus shuffle tiles
- **Configurable Browse Categories** - Choose 2-4 tabs from Home, Latest, Favourites, Books, Playlists, Random, Folders, Artists, Albums, Genres
- **Search** - Find artists, albums, playlists, books, and tracks
- **Voice Control** - "Play X on DashTune" plays an artist radio, a track, an album, a playlist or a book; "Play DashTune" restores your saved queue
- **Playback Controls** - Play, pause, skip, seek with shuffle and repeat modes
- **State Persistence** - Resume where you left off after an app restart or device reboot, with your queue, position, shuffle and repeat modes restored
- **Playback Recovery** - Music returns after a long phone call, and if the system kills the app mid-listen, the queue comes back paused and ready to resume

### 📚 Audiobook Support
- **Browsable Library** - Books appear as a top-level category with folder hierarchy (collections → books → chapters)
- **Multi-Chapter Playback** - Chapters play as an ordered playlist with next/previous navigation
- **Server-Synced Position** - Playback position saved to Jellyfin server every 30 seconds during playback and on pause/stop
- **Resume Anywhere** - Resume from saved position after app restart or on another device
- **Progress Indicators** - AAOS completion status shows read progress on chapter items
- **Auto-Disable Shuffle** - Shuffle automatically turns off for audiobook playback and is restored when you switch back to music

### 📥 Offline Playback
- **Smart Caching** - Automatic caching of streamed tracks (configurable size: 100 MB to 10 GB, or Unlimited)
- **Prefetch Next Tracks** - Downloads upcoming tracks in the queue, respecting shuffle/repeat (Off, 3, 5, 10, 15 or 20; default 5)
- **Cache Favourites** - Optionally pre-download all favourite songs for offline playback
- **Offline Browsing** - Library data is cached locally and synced in the background, so you can still browse without a connection
- **DownloadManager Integration** - Efficient background downloads with 3 parallel connections
- **LRU Cache Eviction** - Automatically manages storage by removing least recently used tracks (unless the cache size is Unlimited)

### 🔐 Authentication
- **QuickConnect** - Pair with Jellyfin server using a simple code
- **Username/Password** - Traditional login support
- **Android AccountManager** - Secure token storage and management
- **Self-Hosted Servers** - Approve a certificate Android rejects after checking its fingerprint, and manage approved certificates in Settings
- **Protected Plain HTTP** - http is only allowed to local hosts, or to a host you accept in a warning at sign-in
- **One Account at a Time** - To switch servers, sign out and sign back in (signing out clears the cached library and artwork)

### 🎨 AAOS Integration
- **Native Media UI** - Integrated with Android Automotive media player
- **Album Art** - ContentProvider-based album art delivery with disk caching
- **Scrobbling** - Reports playback start, progress, pauses and stops to Jellyfin; stops that happen offline are sent later
- **Favorites** - Heart/unheart tracks with HeartRating API
- **Dark Theme** - Optimized for in-car viewing with high contrast

### ⚙️ Settings
- **Bitrate Selection** - Direct stream or transcode (320/256/192/160/128 kbps)
- **Cache Size** - Configure offline storage (100 MB/200 MB/500 MB/1 GB/2 GB/5 GB/10 GB/Unlimited)
- **Offline Song Count** - How many upcoming tracks to prefetch (Off/3/5/10/15/20)
- **Cache Favourites** - Pre-download all favourite songs for offline playback
- **Browse Categories** - Choose which categories appear in the media player (min 2, max 4)
- **Sync Library** - Run a sync on demand and see when the library last synced
- **Clear Library Cache** - Remove cached library data and artwork (downloaded music is kept)
- **Trusted Certificates** - Review and remove certificates you approved during sign-in
- **Force Exit** - Stop playback and shut down DashTune
- **Version Info** - Display app and Jellyfin API versions
- **Sign Out** - Sign out of your Jellyfin server

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **Architecture:** Single automotive module with Hilt DI
- **Media:** AndroidX Media3 (ExoPlayer + MediaLibraryService) 1.9.2
- **Jellyfin SDK:** org.jellyfin.sdk:jellyfin-core:1.8.6
- **Dependency Injection:** Hilt 2.59.1
- **HTTP Client:** OkHttp 5.3.2
- **Database:** Room (media cache, parent relationships)
- **UI:** XML layouts with ViewBinding
- **Crash & Usage Reporting:** Firebase Analytics and Crashlytics (disabled in debug builds)
- **Caching:** Guava cache for metadata, SimpleCache for media files

## Project Structure

```
DashTune/
├── automotive/
│   └── src/main/
│       ├── java/com/chamika/dashtune/
│       │   ├── DashTuneApplication.kt          # Hilt app
│       │   ├── DashTuneMusicService.kt         # MediaLibraryService with offline cache
│       │   ├── DashTuneSessionCallback.kt      # Media session callbacks
│       │   ├── AlbumArtContentProvider.kt      # Album art delivery
│       │   ├── CommandButtons.kt               # Shuffle/Repeat buttons
│       │   ├── auth/                           # Authentication & account management
│       │   ├── data/                           # MediaRepository, Room DB (browse cache, saved queue)
│       │   ├── di/                             # Hilt dependency injection
│       │   ├── media/                          # Media tree & item factory
│       │   ├── settings/                       # Settings UI
│       │   ├── signin/                         # Sign-in flow (server + credentials)
│       │   └── tls/                            # Trusted certificates and plain http policy
│       └── res/
│           ├── drawable/                       # Vector icons for categories
│           ├── layout/                         # Sign-in & settings layouts
│           ├── mipmap-*/                       # App launcher icons
│           ├── values/                         # Strings, colors, themes, arrays
│           └── xml/                            # Preferences, authenticator, AAOS config
└── gradle/                                     # Gradle wrapper & version catalog
```

## Building

### Prerequisites
- The latest stable Android Studio (the project uses AGP 9.4.1)
- JDK 17
- Android SDK with API 36

### Build Commands

Build debug APK:
```bash
./gradlew :automotive:assembleDebug
```

Build release APK:
```bash
./gradlew :automotive:assembleRelease
```

Install to connected device:
```bash
./gradlew :automotive:installDebug
```

## Testing

**Neither suite needs a real Jellyfin server.** The end-to-end tests run against a simulated
Jellyfin server that starts inside the app's own process, so they are fully self-contained.

### Unit tests

Robolectric + MockK, no device or emulator required. CI runs these, along with the end-to-end
suite, on every pull request:

```bash
./gradlew :automotive:testDebugUnitTest
```

Results are written to `automotive/build/test-results/testDebugUnitTest/`.

### End-to-end tests

These bind a real `MediaBrowser` to the real `DashTuneMusicService` — the same interface an AAOS
head unit uses — and cover browsing, pagination, playback, audiobooks and failure handling.

They need a **running Android Automotive emulator**. The manifest requires
`android.hardware.type.automotive`, so the APK will not install on a phone image (see
[Running on AAOS](#running-on-aaos) for creating an automotive AVD):

```bash
# Boot an automotive AVD headlessly
$ANDROID_HOME/emulator/emulator -avd <your_automotive_avd> -no-window -no-audio -no-snapshot &

./gradlew :automotive:connectedDebugAndroidTest
```

Reports are written to `automotive/build/reports/androidTests/connected/debug/index.html`.

### Where the tests live

| Path | Contents |
|------|----------|
| `automotive/src/test/` | Unit tests (ViewModels, repository, media tree, DAO, item factory) |
| `automotive/src/androidTest/` | End-to-end tests |
| `automotive/src/androidTest/.../fake/` | The simulated Jellyfin server and its fixtures |
| `automotive/src/androidTest/.../support/` | Test harness — server startup, sign-in, MediaBrowser helpers |

When the app starts calling a new Jellyfin endpoint, add a route for it to `FakeJellyfinServer`.
Unhandled routes deliberately return HTTP 501 and are recorded, so a missing endpoint surfaces as
a clear failure rather than a silently empty browse list.

## Running on AAOS

### Android Automotive OS Emulator
1. Open Android Studio → AVD Manager
2. Create New Virtual Device → Automotive → Polestar 2
3. Select system image (API 33+ recommended)
4. Launch emulator and install APK

### Physical AAOS Device
1. Enable Developer Options on your AAOS head unit
2. Enable USB debugging
3. Connect via USB and install:
```bash
adb install automotive/build/outputs/apk/debug/automotive-debug.apk
```

The app will appear in the car's media app list.

## Initial Setup

1. **Launch DashTune** from the AAOS media apps
2. **Enter Server URL** - Your Jellyfin server address (e.g., `https://jellyfin.example.com`, or `http://192.168.1.10:8096` on your local network)
3. **Review Any Warning** - You'll be asked to confirm a certificate Android doesn't trust, or plain http to a non-local host
4. **Authenticate** - Use QuickConnect or username/password
5. **Browse & Play** - Start streaming your music!

## Browse Categories

| Category | What it shows |
|----------|---------------|
| **Home** | Titled rows of tiles that each start a long queue |
| **Latest** | Recently added albums |
| **Favourites** | Your hearted tracks, albums, artists, and books |
| **Books** | Audiobooks with folder hierarchy and chapter navigation |
| **Playlists** | All your Jellyfin playlists |
| **Random** | Shuffled album discovery |
| **Folders** | Your library's folders, with recursive shuffle |
| **Artists** | Album artists, A-Z |
| **Albums** | All albums, A-Z |
| **Genres** | Albums grouped by genre |

Categories are configurable in Settings (min 2, max 4). Defaults: Home, Favourites, Books, Playlists.

## Dependencies

Key libraries:
- `androidx.media3:media3-exoplayer:1.9.2`
- `androidx.media3:media3-session:1.9.2`
- `org.jellyfin.sdk:jellyfin-core:1.8.6`
- `com.google.dagger:hilt-android:2.59.1`
- `com.squareup.okhttp3:okhttp:5.3.2`
- `androidx.preference:preference-ktx:1.2.1`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0`
- `androidx.room:room-runtime:2.7.1` (media cache database)
- `com.google.firebase:firebase-bom:34.9.0` (Analytics and Crashlytics)

See `gradle/libs.versions.toml` for complete dependency list.

## Known Limitations

- Requires Android Automotive OS (not compatible with Android Auto projection)
- Offline playback limited by configured cache size
- Album art requires network connection for first load (then cached)
- Video playback not supported (audio only)

## Troubleshooting

**Icon doesn't appear in media player:**
- Completely uninstall and reinstall the app
- Clear AAOS media cache: Settings → Apps → Media → Storage → Clear Cache

**Authentication fails:**
- Verify server URL is correct and reachable
- Check that Jellyfin server allows remote connections
- For QuickConnect: ensure it's enabled in Jellyfin server settings
- For a self-signed certificate: check the fingerprint against your server, then choose **Trust this certificate**
- For plain http to a non-local host: use https if you can, or accept the **Connection not encrypted** warning

**Playback issues:**
- Check network connectivity
- Try different bitrate settings
- Verify audio codec compatibility (app supports: FLAC, MP3, M4A, AAC, OGG)

## Privacy

Release builds send anonymous usage data and crash reports to Firebase; debug builds don't. See the
[privacy policy](PRIVACY-POLICY.md) for details.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

Before opening a pull request, please run the [tests](#testing). CI runs the unit tests and the
end-to-end suite (on an automotive emulator) on every pull request.

## License

TBD

## Acknowledgments

Built with reference to:
- [SharkMarmalade](https://github.com/bendardenne/SharkMarmalade) - Jellyfin AAOS player
- Plex AAOS - Offline caching patterns
- [Jellyfin Android SDK](https://github.com/jellyfin/jellyfin-sdk-kotlin)

---

**Note:** This is an unofficial third-party client for Jellyfin. Not affiliated with the Jellyfin project.
