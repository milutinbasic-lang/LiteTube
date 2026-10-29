# LiteLauncher

A home screen for Android that looks like **postmarketOS** running its default phone shell,
[Phosh](https://gitlab.gnome.org/World/Phosh/phosh), and is designed to cost almost nothing in
CPU, memory or battery.

```
┌──────────────────────────────────────┐
│ ◢ ◠          12:34            85% ▮ │  ← top panel (tap/pull: notifications · quick settings)
├──────────────────────────────────────┤
│      (🔍 Search apps             )   │
│   📞      ✉️      🌐      📷          │  ← favorites
│   ────────────────────────────────   │
│   ▣      ▣      ▣      ▣             │
│   App    App    App    App           │  ← all apps, A–Z
│   ▣      ▣      ▣      ▣             │
├──────────────────────────────────────┤
│                  ︿                   │  ← home bar
└──────────────────────────────────────┘
```

## What it looks like

- **Top panel**: black, with the cellular and Wi-Fi indicators on the left, the clock in the
  middle and the battery level plus a vertical battery icon on the right, drawn like the Adwaita
  symbolic icons Phosh uses. Tap or pull down on the left half for notifications, on the right half
  for quick settings. Android's own status bar is hidden while you're on the home screen.
- **Overview**: Phosh's dark overview with the rounded *Search apps* entry, a favorites row and
  the grid of every app with its name underneath.
- **Home bar**: the black strip with the up chevron at the bottom. Tap it to clear the search and
  jump to the top; tap again to start typing a search.

## Using it

- **Tap** an app to open it. **Long-press** for *Add to / Remove from Favorites*, *App Info* and
  *Uninstall*.
- **Search**: start typing; names that start with what you typed come first. Press *Go* on the
  keyboard to open the first match.
- On first launch the favorites are your default phone, messages, browser and camera apps.
- Work-profile apps are listed too, with their badge.
- To make it your home screen: install it, press Home and pick *LiteLauncher* → *Always*
  (or *Settings → Apps → Default apps → Home app*).

## Why it's light

- **No libraries at all**: no AndroidX, no Material Components, no coroutines. Only the Android
  framework, so the APK is tiny and there is little code to load.
- **One view per app cell**: each grid cell is a single `TextView` with the icon as its compound
  drawable, inside a recycling `GridView`. The panels are single custom-drawn views.
- **Icons are rendered once, at screen size**, off the main thread, into a small LRU cache
  (≤ 12 MB, ≤ 1/10 of the heap) instead of keeping the apps' full-size icons in memory. The cache is
  dropped when Android asks the launcher to trim memory.
- **No polling, no services, no wake-ups**: the clock uses the system's once-a-minute tick, and
  battery/network come from system broadcasts that are only registered while the home screen is
  visible. The app list is only reloaded when a package actually changes.
- **No wallpaper compositing**: the home screen draws a flat background, like Phosh's overview,
  so the GPU never blends a full-screen wallpaper.
- No network access, no analytics, no runtime permissions (only normal install-time ones).

## Building

The launcher is a separate Gradle project in this folder; it reuses the repository's wrapper:

```sh
./gradlew -p launcher assembleRelease   # from the repository root
```

CI builds it in `.github/workflows/launcher.yml` and uploads the APK as the
`litelauncher-apk` artifact. Signing works the same way as LiteTube's (see the main README).

Requires Android 5.0 (API 21) or newer.

*postmarketOS and Phosh are separate projects; this launcher only imitates their look and is not
affiliated with them.*
