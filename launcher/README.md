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
- **Top bar in all apps** (optional add-on): the launcher's top bar can replace Android's
  status bar everywhere, like Phosh's panel on postmarketOS. Android only lets an app cover the
  status bar as an *accessibility service*, and Google Play Protect blocks installing downloaded
  apps that contain one ("App blocked to protect your device"). So this lives in a separate,
  optional app, **LiteLauncher Top Bar** (`litelauncher-topbar.apk`); the launcher itself has no
  accessibility service and installs normally.
  1. Install `litelauncher-topbar.apk`. If Play Protect blocks it, either turn off *Play Store →
     your profile → Play Protect → ⚙ → Scan apps with Play Protect*, install, and turn it back on;
     or install it from a computer with `adb install litelauncher-topbar.apk`.
  2. Long-press the home screen → *Top bar in all apps* → *Open settings*, and turn on
     *LiteLauncher top bar* (or open the *LiteLauncher Top Bar* app, which goes to the same place).
     If the switch is greyed out as a *restricted setting* (Android 13+): *Settings → Apps →
     LiteLauncher Top Bar → ⋮ → Allow restricted settings*, then try again.

  The service reads nothing on your screen; it only draws the bar, and pulling it down opens
  Android's notifications (left half) or quick settings (right half). It sits at the very top
  (covering a camera cutout's strip like Android's bar does, with the clock moved beside a centre
  notch), steps aside while an app is fullscreen (videos, camera), and stops its updates while the
  screen is off.
  - Notification icons aren't shown in the bar (pull it down to see notifications).
  - It notices fullscreen when an app opens a window or the phone rotates. A video player that
    goes fullscreen with no new window and no rotation may keep the bar until the next window
    change.
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

The launcher is a separate Gradle project in this folder (`app/` the launcher, `topbar/` the
optional add-on, `shared/` the top bar code both use); it reuses the repository's wrapper:

```sh
./gradlew -p launcher assembleRelease   # from the repository root
```

The icons are generated by `tools/make_icons.py` (edit the shapes there, then run it).

CI builds it in `.github/workflows/launcher.yml` and uploads the APK as the
`litelauncher-apk` artifact (`litelauncher.apk` and `litelauncher-topbar.apk`). Signing works the same way as LiteTube's (see the main README).

Requires Android 5.0 (API 21) or newer.

*postmarketOS and Phosh are separate projects; this launcher only imitates their look and is not
affiliated with them.*
