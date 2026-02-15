# DashTune

A fully-featured Jellyfin music player for Android Automotive OS (AAOS) with offline download capabilities.

## Overview

DashTune brings the complete Jellyfin music library experience to your car's infotainment system. Stream your music collection, download tracks for offline playback, and enjoy seamless integration with Android Automotive OS.

## Features

### 🎵 Music Streaming & Playback
- **Media3 ExoPlayer** - Modern, high-performance audio playback
- **Browse Library** - Latest albums, random albums, favorites, and playlists
- **Search** - Find artists, albums, playlists, and tracks
- **Playback Controls** - Play, pause, skip, seek with shuffle and repeat modes
- **State Persistence** - Automatically resume where you left off

### 📥 Offline Playback
- **Smart Caching** - Automatic caching of streamed tracks (configurable size: 100MB - 2GB)
- **Prefetch Next Tracks** - Downloads next 5 tracks in queue respecting shuffle/repeat
- **DownloadManager Integration** - Efficient background downloads with 3 parallel connections
- **LRU Cache Eviction** - Automatically manages storage by removing least recently used tracks

### 🔐 Authentication
- **QuickConnect** - Pair with Jellyfin server using a simple code
- **Username/Password** - Traditional login support
- **Android AccountManager** - Secure token storage and management
- **Multi-Server Support** - Switch between different Jellyfin servers

### 🎨 AAOS Integration
- **Native Media UI** - Integrated with Android Automotive media player
- **Album Art** - ContentProvider-based album art delivery with disk caching
- **Scrobbling** - Automatic playback tracking to Jellyfin
- **Favorites** - Heart/unheart tracks with HeartRating API
- **Dark Theme** - Optimized for in-car viewing with high contrast

### ⚙️ Settings
- **Bitrate Selection** - Direct stream or transcode (320/256/192/160/128 kbps)
- **Cache Size** - Configure offline storage (100MB/200MB/500MB/1GB/2GB)
- **Version Info** - Display app and Jellyfin SDK versions

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **Architecture:** Single automotive module with Hilt DI
- **Media:** AndroidX Media3 (ExoPlayer + MediaLibraryService)
- **Jellyfin SDK:** org.jellyfin.sdk:jellyfin-core:1.6.1
- **Dependency Injection:** Hilt 2.51.1
- **HTTP Client:** OkHttp 4.12.0
- **UI:** XML layouts with ViewBinding
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
│       │   ├── di/                             # Hilt dependency injection
│       │   ├── media/                          # Media tree & item factory
│       │   ├── settings/                       # Settings UI
│       │   └── signin/                         # Sign-in flow (server + credentials)
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
- Android Studio Hedgehog (2023.1.1) or later
- JDK 11 or later
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
2. **Enter Server URL** - Your Jellyfin server address (e.g., `https://jellyfin.myserver.com:8096`)
3. **Authenticate** - Use QuickConnect or username/password
4. **Browse & Play** - Start streaming your music!

## Features in Detail

### Browse Categories
- **Latest** - Recently added albums
- **Random** - Shuffled album discovery
- **Favourites** - Your hearted tracks, albums, and artists
- **Playlists** - All your Jellyfin playlists

### Offline Mode
- Tracks are automatically cached as you stream
- Next 5 tracks in queue are prefetched in the background
- Works seamlessly even when network is unavailable
- Configure cache size in Settings

### Playback State Persistence
- Current playlist, position, and track are saved
- Shuffle and repeat modes are preserved
- Resume playback after app restart or device reboot

## Dependencies

Key libraries:
- `androidx.media3:media3-exoplayer:1.7.1`
- `androidx.media3:media3-session:1.7.1`
- `org.jellyfin.sdk:jellyfin-core:1.6.1`
- `com.google.dagger:hilt-android:2.51.1`
- `com.squareup.okhttp3:okhttp:4.12.0`
- `androidx.preference:preference-ktx:1.2.1`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0`

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

**Playback issues:**
- Check network connectivity
- Try different bitrate settings
- Verify audio codec compatibility (app supports: FLAC, MP3, M4A, AAC, OGG)

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

## License

TBD

## Acknowledgments

Built with reference to:
- [SharkMarmalade](https://github.com/bendardenne/SharkMarmalade) - Jellyfin AAOS player
- Plex AAOS - Offline caching patterns
- [Jellyfin Android SDK](https://github.com/jellyfin/jellyfin-sdk-kotlin)

---

**Note:** This is an unofficial third-party client for Jellyfin. Not affiliated with the Jellyfin project.
