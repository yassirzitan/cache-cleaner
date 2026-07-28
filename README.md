# Cache Cleaner

A guided cache-cleaning app for Android. **Important:** no app installed from outside the
system partition can silently wipe other apps' cache — Android blocks that on purpose
(the `CLEAR_APP_CACHE` permission is `signature|privileged`, unavailable to normal apps
since Android 6.0). This app instead does the best legitimate thing possible:

1. Scans every installed app and shows its real cache size (largest first).
2. Lets you tap any app to jump straight to its **Settings → App Info → Storage &
   cache** screen, where the "Clear cache" button lives.
3. **Guided Clean mode**: opens each app's info screen one after another.
4. **Auto-Click (Accessibility)**: optional. If you enable the
   `CacheClearAccessibilityService` in Settings → Accessibility, Guided Clean
   becomes fully hands-off. On each App Info screen it opens, the service taps
   **Storage**, waits for that screen, taps **Clear cache**, then presses Back
   twice (out of Storage, out of App Info) and the app automatically opens the
   next one. It only ever matches the exact "Storage" row and the "Clear cache"
   button — a hard-coded blacklist stops it from ever touching "Clear storage",
   "Clear data", "Uninstall", "Force stop", or "Disable", and it only acts while
   a Guided Clean run is actively in progress.

If you skip enabling Accessibility, Guided Clean still works — you just tap
"Clear cache" and Back yourself on each screen, same as before.

## Opening the project

1. Open this folder in **Android Studio** (Hedgehog/2023.1 or newer). It will
   auto-generate the Gradle wrapper on first sync — no manual setup needed.
2. Let it sync, then Run on a device or emulator (min SDK 26 / Android 8.0).

## First run

- The app needs the special **Usage access** permission to read per-app cache sizes
  (this is a system-level permission with no runtime popup — the app opens Settings
  and tells you which toggle to flip).
- `QUERY_ALL_PACKAGES` is declared so the app can see every installed package on
  Android 11+. It's a normal manifest permission (auto-granted at install), but note
  that Play Store review requires a justification for it if you ever publish this.
- **Accessibility service** (optional, for hands-off Auto-Click): also a manual,
  explicit toggle — Android has no runtime popup for it either. Enable it from the
  "Enable Auto-Click" button, which deep-links to Settings → Accessibility. Play
  Store review scrutinizes accessibility-service apps heavily and requires a clear
  disclosure of what the service does, which is why the in-app description spells
  out exactly what it clicks and when.

## Files

- `MainActivity.kt` — scans apps via `StorageStatsManager`, drives the guided walkthrough.
- `AppCacheInfo.kt` / `AppListAdapter.kt` — data model + RecyclerView list.
- `AndroidManifest.xml` — the two permissions above.

## What this app deliberately does NOT do

It does not (and cannot, without root or a system-signed build) clear another app's
cache with a single tap with zero user interaction. If you want that, the only real
paths are:
- Root the device and shell `pm clear -c <package>` per app.
- Ship this as a preinstalled system app signed with the platform key.
- Use `adb shell pm trim-caches <bytes>` from a PC, which isn't app-triggerable.
