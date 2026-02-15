# DashTune

A Jellyfin music player for Android Automotive OS (AAOS).

## Overview

DashTune is an Android application that brings Jellyfin music streaming to your car's infotainment system, targeting Android Automotive OS.

## Project Structure

```
DashTune/
├── automotive/   # Android Automotive OS module
├── shared/       # Shared library (MediaBrowserService, common logic)
└── gradle/       # Gradle wrapper and version catalog
```

- **shared** — Contains `MyMusicService`, a `MediaBrowserServiceCompat` implementation that provides media browsing and playback controls to the system.
- **automotive** — AAOS-specific module declaring `android.hardware.type.automotive` and the `audio` app category.

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 28 (Android 9.0)
- **Target SDK:** 36
- **AndroidX Media** for `MediaBrowserServiceCompat` / `MediaSessionCompat`
- **Material Design 3**

## Building

Open the project in Android Studio or build from the command line:

```bash
./gradlew assembleDebug
```

To build only the automotive module:

```bash
./gradlew :automotive:assembleDebug
```

## Running on AAOS

You can run the automotive build on:

- **Android Automotive OS emulator** — Available through Android Studio's AVD Manager (select an Automotive system image).
- **A physical AAOS head unit** — Install via ADB.

The app registers as a media source through `MediaBrowserService`, so it will appear in the car's media app list.

## License

TBD
