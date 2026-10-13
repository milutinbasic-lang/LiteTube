# LiteTube

A lightweight, private YouTube client for Android.

- **No account.** Nothing to sign in to. Subscriptions are stored only on your phone.
- **No tracking.** No analytics, no crash reporters, no Google Play Services, no Firebase.
- **No ads.** YouTube's ads are never even downloaded, and sponsor segments inside videos are
  skipped automatically with [SponsorBlock](https://sponsor.ajay.app).
- **Background playback.** Keeps playing with the screen off or while using other apps, with
  notification, lock-screen and headphone/Bluetooth controls.
- **Built for low-end phones.** Runs on Android 5.0+ with a small APK, small memory footprint and
  hardware-friendly video formats.
- **Dark mode.** Follow system / Light / Dark / Black (AMOLED).
- **Free software.** GPL-3.0.

## Features

| Screen | What it does |
|---|---|
| Home | Newest videos from your subscriptions, newest first. No recommendation algorithm. Cached, so it opens instantly (even offline). Pull to refresh. |
| Search | Videos and channels, with infinite scrolling. |
| Player | Quality picker, **captions** (off by default, any language, remembered), **audio-only mode**, **background playback**, sponsor skipping, **comments**, fullscreen on rotate, "Up next" list, subscribe. |
| Tools (⋮) | **Download video** (MP4), **download audio** (M4A), **save current frame** as an image, **summarize** (chapters + key moments), share. |
| Navigation | Home button on every screen. While something plays, Home shows a mini player (play/pause, close, tap to return). |
| Channel | Channel info and all uploads, with subscribe/unsubscribe. |
| Subscriptions | List, unsubscribe (long-press), **import** from NewPipe (`.json`) or Google Takeout (`subscriptions.csv`), **export** (NewPipe-compatible). |
| Links | Opens YouTube links clicked or shared from other apps. |

Long-press any video for: *Play audio only*, *Open channel*, *Share*.

## Ad blocking

Browsers like Brave block ads by filtering the requests a web page makes. LiteTube doesn't need a
filter list: it never loads YouTube's web pages or player. It asks for the video files directly,
so pre-roll, mid-roll, banner and overlay ads are **never requested at all**. There's nothing to block
and no data is spent on them.

What a request blocker *can't* catch are ads read by the creator inside the video itself.
For those, LiteTube uses **SponsorBlock**, a free, open, community-maintained database of
timestamps, and jumps over them automatically. Choose what to skip in *Settings → Ad and sponsor
blocking*: sponsors, self-promotion, "like and subscribe" reminders (on by default), and optionally
intros, endcards, previews, filler, and non-music sections of music videos.

SponsorBlock lookups are private: only the first 4 characters of a SHA-256 hash of the video ID
are sent. The server replies with segments for every video sharing that prefix, and the app picks
the right one locally. Skips are triggered by the player at exact positions, without a
polling loop, so it costs essentially no CPU. Turn it off in Settings to make no requests at all.

## Captions

Off by default. Tap **CC** in the player to pick a language while the video plays. LiteTube
remembers your choice (e.g. Serbian) and turns it on automatically for the next video that has it.
Only the chosen language is downloaded.

## Downloads and tools

The **⋮** button in the player opens a small sheet with:

- **Download video**: pick a resolution. YouTube serves HD video and audio separately, so LiteTube
  downloads both and joins them into one MP4 with Android's built-in muxer: no re-encoding, no
  quality loss, no extra libraries. Saved to **Movies/LiteTube**, so it shows up in your gallery.
- **Download audio**: saved as **M4A (AAC)** to **Music/LiteTube**. It isn't MP3 because Android has
  no MP3 encoder: converting would need a bundled encoder, take CPU time on slow phones, and lose
  quality. M4A plays in every music app and is smaller than MP3 at the same quality.
- **Save current frame**: the exact frame on screen, at full video resolution, to **Pictures/LiteTube**.
- **Summarize**: the video's chapters plus 3–8 "key moments" picked from its captions, each with a
  timestamp you can tap to jump there. Worked out on your phone: no AI service, nothing uploaded. It
  picks the most informative passages rather than writing new sentences, and needs captions or
  chapters to work.

Downloads run in the background with a progress notification, one at a time, in 2 MB chunks that
are retried on a flaky connection.

## Background playback

- Press **Home**, switch apps or turn the screen off: playback continues, with a media
  notification and lock-screen controls. Headphone and Bluetooth buttons work too.
- While in the background, video decoding is switched off and only the audio plays, which
  saves a lot of battery on weak phones. The picture comes back when you return.
- Press **Back** in the player, or pause and swipe the notification away, to stop.
- Tapping the notification reopens the player without restarting the video.
- Prefer the old behaviour? Turn off *Settings → Playback → Background playback*.

## How it stays light

- Plain Android Views and RecyclerView. No Jetpack Compose, no WebView.
- One shared player instance for the whole app. In the background only audio is decoded.
- Small list thumbnails (picks the smallest image ≥ 320px), loaded as RGB_565 bitmaps with capped
  memory/disk caches. Thumbnails can be switched off entirely.
- **Low-power playback** (on by default) prefers H.264 at ≤30fps, which nearly every phone decodes in
  hardware. Default quality cap is 360p (configurable from 144p to 1080p).
- Smaller player buffers (15–30s instead of 50s) to save RAM and data.
- Subscription feed uses YouTube's tiny RSS feeds, fetched 4 at a time.
- Screens don't reload on rotation. Release builds are minified and resource-shrunk with R8.

## Privacy details

- YouTube data comes from [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor),
  which reads YouTube's public web pages and APIs. No API key, no Google account.
- No cookies are stored. The HTTP client has no cookie jar.
- Settings, subscriptions and the cached feed live in the app's private storage. Android backup is
  disabled.
- Videos still stream from YouTube's servers, so YouTube can see your IP address. Use a VPN or Tor
  (e.g. Orbot in VPN mode) if you need to hide it.
- Permissions: `INTERNET`; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `FOREGROUND_SERVICE_DATA_SYNC` and `WAKE_LOCK` for background playback and downloads (granted
  automatically, no access to your data); `POST_NOTIFICATIONS` (asked once, optional, only to show
  download progress on Android 13+); `WRITE_EXTERNAL_STORAGE` **only on Android 5–9** and only when
  you first save a file. No location, contacts, camera or microphone.
- The only servers contacted are YouTube's and, if enabled, SponsorBlock's (`sponsor.ajay.app`).

## Getting the APK

### Option A: GitHub Actions (no Android tools needed)

1. Push this project to a GitHub repository.
2. The **Build APK** workflow runs automatically (or start it from the *Actions* tab).
3. Download the `litetube-apks` artifact and install `app-release.apk` on your phone.
   You will need to allow installing from unknown sources.

By default the release APK is signed with a throwaway debug key. Each CI run can generate a
different one, so to **update** an installed copy you'd have to uninstall it first. To sign with your
own permanent key, create one:

```sh
keytool -genkeypair -v -keystore release.jks -alias litetube -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # copy the output
```

…and add these repository secrets: `LITETUBE_KEYSTORE_BASE64`, `LITETUBE_KEYSTORE_PASSWORD`,
`LITETUBE_KEY_ALIAS`, `LITETUBE_KEY_PASSWORD`. Never commit the keystore.

### Option B: build locally

Requires JDK 17 and the Android SDK (or Android Studio).

```sh
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## When YouTube changes something

YouTube changes its internals often, and that can break playback or search in every third-party
client. The fix is usually to bump NewPipe Extractor to its
[latest release](https://github.com/TeamNewPipe/NewPipeExtractor/releases) in `app/build.gradle.kts`:

```kotlin
implementation("com.github.TeamNewPipe:NewPipeExtractor:vX.Y.Z")
```

If a progressive stream fails, the player automatically retries with YouTube's HLS stream when one
is available.

## Known limitations

- No picture-in-picture or play queue yet.
- No playlists, comments or subtitles yet.
- On Android 12+, tapping YouTube links opens LiteTube only if you enable it under
  *App info → Open by default → Add link*. Sharing a link to LiteTube always works.

## Project layout

```
app/src/main/java/app/litetube/
├── App.kt                 init: NewPipe Extractor, image loader, theme
├── net/                   OkHttp-based downloader for NewPipe Extractor
├── data/                  YouTube access, subscriptions, feed cache, settings
├── player/                shared player, background service, SponsorBlock, captions,
│                          summarizer, stream selection, YouTube-compatible media sources
├── download/              background downloads, chunked fetching, MP4 muxing, saving
└── ui/                    activities, list adapter, helpers
```

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Uses NewPipe Extractor (GPL-3.0), AndroidX Media3,
OkHttp and Coil (Apache-2.0).

LiteTube is not affiliated with or endorsed by YouTube or Google.
