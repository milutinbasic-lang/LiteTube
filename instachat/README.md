# InstaChat

An Android app for Instagram **chats only**: your Direct inbox and conversations, and nothing else.
No feed, reels, explore, stories or posts.

- **Log in once.** You sign in on Instagram's own login page (2FA and "Log in with Facebook" work).
  The session is kept in the app's private storage, so you stay logged in after closing the app
  or restarting the phone.
- **Chats only.** The bottom navigation (Home, Search, Reels, Create, Profile) is hidden, and any
  attempt to open a non-chat page is blocked. If someone sends you a reel or post, tapping it shows
  "Only chats are available" instead of opening it.
- **Send photos and voice messages.** The file picker and microphone work. The microphone
  permission is only requested the first time you record a voice message.
- **Links open in your browser.** Links people send you open outside the app.
- **Log out:** long-press the app icon → *Log out*. This clears the session from the phone.
- **Dark mode** follows the system setting.
- No trackers, no extra libraries. The app is a single screen.

## How it works

Instagram doesn't offer a public messaging API for personal accounts, so InstaChat shows Instagram's
own mobile website (`instagram.com/direct/inbox/`) in a WebView and restricts it:

- `UrlRules.kt` decides which pages may load: `/direct/…`, the login/account pages, and Facebook
  login. The home page redirects to the inbox. Everything else on instagram.com is blocked, and
  other sites open in your browser. Because Instagram changes pages without reloading, every URL
  change is checked as well as every page load.
- `ChatOnlyScript.kt` is injected into the page and hides the navigation bar. It keeps doing that
  as Instagram redraws.
- Cookies are flushed to disk whenever a page finishes loading and when the app goes to the
  background, so the login survives the app being killed.

## Limitations

- **No push notifications.** Instagram's web notifications don't work inside a WebView. Open the app
  to check for new messages (they appear instantly while it's open).
- **No voice/video calls.** Instagram's mobile website doesn't offer them.
- It depends on Instagram's website. If Instagram changes its layout, a hidden button might
  reappear. The URL blocking still stops it from opening.

## Build

The **Build InstaChat APK** GitHub Actions workflow builds a release APK on every push that changes
`instachat/`. Download it from the run's *Artifacts* (`instachat-apk`) and install it on your phone.

To build locally (needs the Android SDK):

```sh
cd instachat
./gradlew assembleRelease   # or assembleDebug
```

To sign with your own key, so new versions install over old ones, set the repository secrets
`INSTACHAT_KEYSTORE_BASE64`, `INSTACHAT_KEYSTORE_PASSWORD`, `INSTACHAT_KEY_ALIAS` and
`INSTACHAT_KEY_PASSWORD`. Without them, CI signs each build with a new debug key, so you must
uninstall the old version before installing a new one, and that logs you out.
