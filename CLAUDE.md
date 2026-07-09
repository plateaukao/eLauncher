# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

eLauncher is an intentionally minimal Android home-screen launcher, optimized for eInk/ePaper devices (Onyx Boox Note, Bigme HiBreak). The scope is deliberately barebones — see README.md's "Contributing" note before adding features. Written in Java (no Kotlin), single `:app` module, ~1000 lines total.

## Build & Run

```bash
./gradlew assembleDebug          # build debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease        # release build (minify + shrink, signed with debug key)
./gradlew installDebug           # build + install to connected device/emulator
./gradlew lint                   # Android lint
```

- Toolchain: AGP 8.5.2, `compileSdk`/`targetSdk` 34, `minSdk` 24, Java 8 source/target.
- Release builds use `minifyEnabled` + `shrinkResources` and are signed with the **debug** signing config (no separate release keystore configured).
- There are no unit/instrumentation tests in the repo despite the configured `AndroidJUnitRunner`.
- `fastlane/metadata/android/` holds F-Droid store metadata (title, descriptions, screenshots).

## Architecture

Two activities, no fragments-based navigation, no DI, no architecture framework. State lives in `SharedPreferences` (default prefs).

**`MainActivity`** is the launcher itself and contains almost all logic. It manages two views inside one layout (`activity_main.xml`), toggled by `changeLayout(home, animated)`:
- **HomeScreen** — a `LinearLayout` of `TextView`s built programmatically in `onCreate`. Count comes from the `number_of_apps_preference` pref (default 8). Each slot's assigned app is stored as two prefs: slot label under key `"<i>"` and package under key `"p<i>"`. Long-press a slot to pick/rename an app. If USAGE_STATS permission is granted, an extra italic "last used app" slot is appended.
- **AppDrawer** — a `RecyclerView` + bottom search `EditText`. Driven by `recyclerAdapter`.

**Gestures** are central to the UX and split across two mechanisms:
- `SwipeListener` (inner class, a `GestureDetector`) on the homescreen: swipe up → app drawer, swipe down → notification panel (via reflection on `android.app.StatusBarManager#expandNotificationsPanel`), swipe left → default browser, swipe right → dialer/camera, double-tap → previous launcher, long-press → `SettingsActivity`.
- `dispatchTouchEvent` + `onBackPressed` in `MainActivity` reinterpret **edge** swipes (left/right 50dp edges) so the system back gesture instead triggers browser/dialer, and blocks genuine back gestures. This duplicates the left/right swipe behavior for edge-to-edge devices.

**`recyclerAdapter`** implements fuzzy search via `Filterable`. `fuzzyContains` matches query chars in order (subsequence match). Matched characters get an `UnderlineSpan`; **a single remaining result auto-launches** (`listener.onClick`), as does an exact-length full match — this is the OLauncher-style type-to-launch behavior. Currently-running apps (from usage stats) are bolded. Note spans are mutated on the shared `SpannableString` and cleared/reapplied in `onBindViewHolder`.

**Usage-stats integration** (requires `PACKAGE_USAGE_STATS`, prompted on first launch): `listActiveProcessPackages()` finds recently-active apps (last 60 min) to bold them; `lastActiveProcessPackage()` finds the most-recent foreground app for the "last app" homescreen slot, excluding launchers, browser, dialer, settings, and a hardcoded list of Bigme `com.xrz.*` middleware packages.

**`SettingsActivity`** hosts a `PreferenceFragmentCompat` from `res/xml/root_preferences.xml`. Any swipe-up, long-press, or back press calls `restartApplication()` (relaunch MainActivity with `CLEAR_TASK`) so pref changes (app count, dark mode) take effect by full rebuild rather than live update.

## Bigme HiBreak specifics

`BigmeShims` and `UnlockReceiver` exist solely for the Bigme HiBreak, gated on `Build.MODEL.equals("HiBreak")`. The device's stock launcher controls hardware gestures; when eLauncher replaces it, `queryLauncherProvider()` pokes `com.xrz.LauncherProvider` (called from `onCreate`, `onResume`, and on unlock broadcasts) to keep that gesture process alive. Changes here only affect that one device and are no-ops elsewhere.

## Supernote (Ratta) specifics

`SupernoteShims`, gated on `Build.MANUFACTURER == "Supernote"`, no-ops elsewhere:

- **Status bar must stay hidden** (`hideStatusBar`, called from both activities' `onCreate` and `MainActivity.onResume`). Ratta's SystemUI never draws the AOSP status bar, but if the home window leaves it requested-visible the ROM insets the home task 213px below the top after a bar show/hide cycle and never restores it — a dead black band. All stock Ratta apps run immersive.
- **"Last note" / "last document" home slots** replicate the stock launcher slide-bar panel's intents (reverse-engineered from `GesturePresenter` in SupernoteLauncher.apk). Last note path comes from `/storage/emulated/0/.noteCache/noteLastFile.xml` (requires All Files Access — `MANAGE_EXTERNAL_STORAGE` in the manifest; a tap redirects to the grant screen until granted); last document comes from the Document app's exported `FileStateProvider` (`content://com.ratta.supernote.document.provider.file/status`, state 1 = opened). Both fall back to a plain app launch. The slots are appended before the "last app" slot — `changeLayout`/`homeUpdateUsage` assume the last grid child is the last-app slot.

## Theming (eInk)

Two themes in `themes.xml`: `AppTheme` (light, default) and `AppTheme.InvertedDark`. Selected in `MainActivity.onCreate` from `dark_mode_preference`, defaulting to the system night-mode setting. Status/navigation bar colors are matched to the background to keep the screen flat for eInk. Colors are pure black/white (`colors.xml`) for maximum eInk contrast.

## Conventions

- Java, Android-framework-direct style. Class `recyclerAdapter` is intentionally lowercase (pre-existing).
- Note the logic duplication between `changeLayout`'s home branch and `homeUpdateUsage()` (both re-bold running homescreen apps) — keep them in sync if editing usage-stats display.
