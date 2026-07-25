# Android Automotive (AAOS) — emulator and UI navigation

DashTune targets Android Automotive OS. The generic device guidance in `interact.md` still
applies; this covers what is different.

## The emulator

The AAOS AVD on this machine is **`Automotive_Portrait`** (the other AVD, `Pixel_8`, is a phone
and will not run this app — the manifest requires
`<uses-feature android:name="android.hardware.type.automotive" android:required="true"/>`).

```bash
android emulator list
android emulator start Automotive_Portrait
adb devices                     # first emulator is emulator-5554
```

Always pass `-s <serial>` to `adb` — with a phone also attached, unqualified commands hit the
wrong device or fail outright.

Booting is slow. Poll for readiness rather than sleeping:

```bash
adb -s emulator-5554 wait-for-device
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
```

## Reaching DashTune

DashTune has **no launcher activity** — it is a `MediaLibraryService` that the car's media host
binds to. There is no icon to tap in a launcher. The path is:

1. Start the media host:
   `adb -s emulator-5554 shell am start -n com.android.car.media/.MediaActivity`
2. Open the media **app switcher** inside that UI (the app name / source selector, usually top
   of the screen).
3. Pick **DashTune** from the list of media sources.
4. You land on DashTune's browse-category grid (Albums, Artists, Playlists, Folders, … —
   the exact set depends on the `browse_categories` preference).

To force a clean re-bind after installing a new build, `am force-stop com.android.car.media`
first — otherwise the host keeps the old service connection and you debug stale code.

## Navigating the UI

**Resolve elements from the UI tree; do not guess tap coordinates.** Portrait automotive
screens are unusual sizes, and a tap that lands in a gap looks exactly like a broken feature —
this is what turns a 10-minute check into an hour.

```bash
android layout                  # JSON UI tree
android layout --diff           # what changed after an action
android screen capture --annotate
android screen resolve "Playlists row"
```

Fall back to `adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml` if the `android` CLI
is unavailable. Only use `adb shell input tap <x> <y>` with coordinates you read out of the
tree, and re-dump after every navigation — list rows shift as content loads.

## Sanity checks

```bash
adb -s emulator-5554 shell pidof com.chamika.dashtune       # service actually running?
adb -s emulator-5554 shell dumpsys media_session | head -40 # who owns the session
```

For the logcat loop and media-tree debugging, use the `jellyfin-debug` skill.
