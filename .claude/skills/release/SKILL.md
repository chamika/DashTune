---
name: release
description: Prepare a DashTune release — bump version in automotive/build.gradle.kts, increment versionCode, and commit with the standard release message generated from changes since the last release. Use when the user asks to "create a release", "cut a release", "do a release", or "prepare a release".
---

# Release

Prepare a new DashTune release commit: bump the version, increment the version code, and commit with the standard release message format built from the changes since the last release.

## Steps

### 1. Read the current version

Read `versionCode` and `versionName` from `automotive/build.gradle.kts`:

```bash
grep -n "versionCode\|versionName" automotive/build.gradle.kts
```

### 2. Ask which version bump is needed

Ask the user whether this is a **major**, **minor**, or **patch** bump (semver: `major.minor.patch`). Show them the current `versionName` and what each option would produce so the choice is concrete. Wait for the answer before continuing.

- **major**: `X.y.z` → `(X+1).0.0`
- **minor**: `x.Y.z` → `x.(Y+1).0`
- **patch**: `x.y.Z` → `x.y.(Z+1)`

`versionCode` is **always** incremented by exactly 1, regardless of the bump type.

### 3. Gather the changes since the last release

Find the previous release commit and list the commits since then. These become the release notes.

```bash
# Find the most recent release commit
LAST_RELEASE=$(git log --oneline --grep="^Release v" -n 1 --format=%H)
# List commit subjects since that release (excludes the release commit itself)
git log --format="%s" "$LAST_RELEASE"..HEAD
```

Use these commit subjects to compose the release notes. Clean them up into clear, user-facing bullet points:
- Merge commits (e.g. "Merge pull request #NN…") should be dropped or summarized by the change they brought in, not listed verbatim.
- Rephrase terse/technical subjects into readable notes where helpful, but keep them faithful to what actually changed.

### 4. Edit `automotive/build.gradle.kts`

Update both lines:
- `versionCode = <old + 1>`
- `versionName = "<new semver>"`

### 5. Commit

Commit **only** `automotive/build.gradle.kts` using this exact format:

```
Release v<versionName>(<versionCode>)

<non-technical short one-line description for the Google Play release description>

Full release notes:

- <change 1>
- <change 2>
- <change 3>
```

- Line 1: `Release v<versionName>(<versionCode>)` — e.g. `Release v1.2.6(22)`
- Line 3: a single short, non-technical sentence summarizing the release, suitable for a Google Play "What's new" description.
- The `Full release notes:` section lists every notable change as bullet points, derived from the commits in step 3.

Example commit message:

```
Release v1.2.5(21)

Fix blank screen on fresh install plus offline error dialog and auto-resume crash

Full release notes:

- Fix blank Media Center screen when opening the app while signed out — the sign-in prompt now appears correctly again
- Fix the "Something went wrong / Check Google Play" dialog that appeared on the Now Playing screen when the car had no internet
- Skip auto-resuming previously played tracks that aren't cached on the device when offline, instead of getting stuck on "Loading content…"
- Album art now falls back to a placeholder when offline instead of failing
```

Make the release commit on the **current working branch** — do not switch to or commit directly on `main`. The release rides along with whatever feature branch is checked out (it lands on `main` later when that branch is merged), so any not-yet-merged changes on the branch are part of this release and must be reflected in the notes.

Stage and commit only the gradle file:

```bash
git add automotive/build.gradle.kts
git commit -F <message-file>
```

Do **not** add the `Co-Authored-By` trailer or any other footer to release commits — keep the message exactly in the format above.

### 6. Report

Tell the user the new version (`v<versionName>(<versionCode>)`) and show the commit. Do not push unless the user asks.
