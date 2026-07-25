---
name: jellyfin-debug
description: "Debug DashTune's media browsing and playback on the AAOS emulator — 'Media isn't available' errors, empty or wrong browse categories, playlists that won't load, the wrong song playing, or any MediaBrowser/Media3 issue. Covers the logcat loop, how to launch an app that has no launcher activity, and how to read the Jellyfin SDK sources."
---

# jellyfin-debug — DashTune media tree and playback

For diagnosing what DashTune serves into the AAOS media host: browse categories, folders,
playlists, and what actually plays when an item is selected.

## The app has no launcher activity

`AndroidManifest.xml` declares no LAUNCHER activity. The entry point is the **service**
`com.chamika.dashtune/.DashTuneMusicService` (a Media3 `MediaLibraryService`). `am start` on
the package will not work.

Exercise it by restarting the AAOS media host, which re-binds to the service:

```bash
SERIAL=emulator-5554
adb -s "$SERIAL" shell am force-stop com.android.car.media
adb -s "$SERIAL" shell am start -n com.android.car.media/.MediaActivity
```

Then select DashTune in the media app list. The two activities the app *does* export are
`signin.SignInActivity` (`ACTION_SIGN_IN`) and `settings.SettingsActivity`
(`APPLICATION_PREFERENCES`) — reachable by action, not from a launcher.

## Logging

**The app logs under exactly one tag: `DashTune`** (`Constants.LOG_TAG`; there are no
per-class `TAG` constants). So:

```bash
adb -s "$SERIAL" logcat -s DashTune:V
```

The Jellyfin SDK logs separately through SLF4J (`libs.slf4j.android`) under its own tags —
it will not appear under `DashTune`.

## The loop

Clear the log immediately before the action you want to observe, not at the start — otherwise
you read the previous run. Poll for the process instead of guessing with `sleep`:

```bash
SERIAL=emulator-5554
./gradlew :automotive:installDebug -q 2>&1 | tail -5
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop com.android.car.media
adb -s "$SERIAL" shell am start -n com.android.car.media/.MediaActivity

for i in $(seq 1 20); do
  adb -s "$SERIAL" shell pidof com.chamika.dashtune >/dev/null 2>&1 && break
  sleep 0.5
done
adb -s "$SERIAL" logcat -d -v time | grep -iE "DashTune|AndroidRuntime" | tail -60
```

Narrow with `grep -iE "onGet|onLoadChildren|folder|playlist|Exception"` once you know which
callback you care about. Screenshots go to the session scratchpad, not the repo:
`adb -s "$SERIAL" exec-out screencap -p > "$SCRATCH/shot.png"`.

For navigating the emulator UI to reach a category, see the `android-cli` skill's
`references/aaos.md` — use the UI tree, don't guess tap coordinates.

## Reading the Jellyfin SDK sources

The dependency is `org.jellyfin.sdk:jellyfin-core:1.8.6`. Sources jars are in the Gradle cache
but under content-hash directories that differ per machine, so **glob, never hardcode**:

```bash
find ~/.gradle/caches/modules-2/files-2.1/org.jellyfin.sdk -name '*-sources.jar'
unzip -l <jar>                        # locate the file
unzip -p <jar> org/jellyfin/sdk/model/api/BaseItemKind.kt
```

- `jellyfin-model-jvm` → `BaseItemKind`, `BaseItemDto`, the DTOs
- `jellyfin-api-jvm` → the `*Api` classes (`userLibraryApi`, `itemsApi`, `playStateApi`, …)

`unzip:*` is already allowlisted in `.claude/settings.local.json`.

## Known traps

**`BaseItemKind.COLLECTION_FOLDER` is the classic "Media isn't available" cause.** A
CollectionFolder is a top-level music library reachable only via the Folders category, and
`MediaItemFactory.create()` cannot build one — it throws. There is an explicit guard at
`automotive/src/main/java/com/chamika/dashtune/media/JellyfinMediaTree.kt:131`; the comment
above it explains the cold-tree-cache path that triggers it. If a new browse path throws
here, that is the shape of the bug.

**"Plays the wrong song" is usually resolution, not browsing.** When a selected item plays
something else, look at how media IDs are resolved into playable items
(`onAddMediaItems` / `onSetMediaItems` / playback resumption) rather than at the browse tree —
the tree can be correct while resolution picks the wrong index.

**Categories are user-configurable.** The `browse_categories` preference changes which
top-level nodes exist, so "category missing" may be settings, not a bug. Check the pref before
digging into `JellyfinMediaTree`.

## Verifying a fix

`./gradlew :automotive:testDebugUnitTest` is what CI runs (`.github/workflows/test.yml`).
Run it before asking the user to check on the emulator.
