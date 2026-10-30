# LiteLauncher

A home screen for Android that looks like **postmarketOS** running its default phone shell,
[Phosh](https://gitlab.gnome.org/World/Phosh/phosh), and is designed to cost almost nothing in
CPU, memory or battery.

```
 Home screen                              App drawer (swipe up or tap ︿)
┌──────────────────────────────────┐    ┌──────────────────────────────────┐
│ ◢ ◠        12:34          85% ▮ │    │ ◢ ◠        12:34          85% ▮ │
│                                  │    │    (🔍 Search apps          )    │
│             12:34                │    │   📞     ✉️     🌐     📷        │
│      Thursday, October 30        │    │   ──────────────────────────     │
│                                  │    │   ▣     ▣     ▣     ▣            │
│      /\    /\/\     /\           │    │   App   App   App   App          │
│   /\/  \/\/    \/\_/  \/\        │    │   ▣     ▣     ▣     ▣            │
│   📞     ✉️     🌐     📷         │    │   App   App   App   App          │
├──────────────────────────────────┤    ├──────────────────────────────────┤
│               ︿                 │    │               ﹀                 │
└──────────────────────────────────┘    └──────────────────────────────────┘
```

## What it looks like

- **Top panel**: black, with the cellular and Wi-Fi indicators on the left, the clock in the
  middle and the battery level plus a vertical battery icon on the right, drawn like the Adwaita
  symbolic icons Phosh uses. Tap or pull down on the left half for notifications, on the right half
  for quick settings. Android's own status bar is hidden while you're on the home screen.
- **Home screen**: a postmarketOS-style mountain wallpaper with Phosh's large clock and date, and
  your favorites in a dock at the bottom.
- **App drawer**: Phosh's dark overview with the rounded *Search apps* entry, a favorites row and
  the grid of every app with its name underneath.
- **Home bar**: the black strip at the bottom. Its chevron points up on the home screen (tap to
  open the drawer) and down in the drawer (tap to close it).
- **postmarketOS-style icons**: Google's and AOSP's default apps (Phone, Messages, Chrome,
  Camera, Settings, Contacts, Clock, Calculator, Calendar, Photos/Gallery, Files, Maps, Gmail,
  YouTube, YouTube Music, Play Store, Keep) and some open-source ones (Firefox, F-Droid, VLC,
  OsmAnd, Organic Maps, Termux, LiteTube…) get flat Adwaita-style icons like the GNOME apps on
  postmarketOS. Whatever your default dialer, SMS app, browser and camera are, they're themed too.
  Other apps keep their own icon.

## Using it

- **Swipe up** anywhere on the home screen, or tap the home bar, to open the app drawer.
  **Swipe down** on the grid (when it's at the top), tap the home bar, or press Back/Home to
  close it. After you open an app, Home brings you back to the home screen.
- **Tap** an app to open it. **Long-press** for *Add to / Remove from Favorites*, *App Info* and
  *Uninstall*.
- **Long-press the home screen** to switch off the postmarketOS icons, to show your system
  wallpaper instead of the built-in one, or to choose **Use Android status bar**, which hides
  LiteLauncher's top bar and shows your phone's own status bar instead (the launcher then also
  stops watching battery and signal, since it has nothing to show them on).
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
  dropped when Android asks the launcher to trim memory. The postmarketOS icons are tiny vector
  drawables.
- **The wallpaper is drawn, not loaded**: a gradient and three shapes, redrawn only when the
  clock changes, instead of a full-screen bitmap (several MB) or Android's wallpaper layer. While
  the drawer is open the home screen underneath isn't drawn at all.
- **No polling, no services, no wake-ups**: the clock uses the system's once-a-minute tick, and
  battery/network come from system broadcasts that are only registered while the home screen is
  visible. The app list is only reloaded when a package actually changes.
- No network access, no analytics, no runtime permissions (only normal install-time ones).

## Building

The launcher is a separate Gradle project in this folder; it reuses the repository's wrapper:

```sh
./gradlew -p launcher assembleRelease   # from the repository root
```

The icons are generated by `tools/make_icons.py` (edit the shapes there, then run it).

CI builds it in `.github/workflows/launcher.yml` and uploads the APK as the
`litelauncher-apk` artifact. Signing works the same way as LiteTube's (see the main README).

Requires Android 5.0 (API 21) or newer.

*postmarketOS and Phosh are separate projects; this launcher only imitates their look and is not
affiliated with them.*
