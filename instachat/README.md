# InstaChat

An Android app for Instagram **chats only**: your Direct inbox and conversations, and nothing else.
No feed, reels, explore, stories or posts.

- **Log in once.** You sign in on Instagram's own login page (2FA and "Log in with Facebook" work).
  The session is kept in the app's private storage, so you stay logged in after closing the app
  or restarting the phone.
- **Chats only.** The bottom navigation (Home, Search, Reels, Create, Profile) is hidden, and any
  attempt to open a non-chat page is blocked.
- **Watch what people send you, without falling into the feed.** A post, reel or video someone
  sends you opens, but you can't scroll on from it: swiping is disabled in the video viewer,
  the "more posts" under a post are hidden, and moving from one post or reel to another is
  blocked. Back closes the viewer and returns to the chat.
- **Notifications for new messages**, while the app is in the background. Tap one to open that
  chat. Messages you already read elsewhere (the Instagram app or a computer) aren't notified.
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
  as Instagram redraws. It also locks swiping in full-screen video viewers and reels-style lists,
  and hides the "more posts" below a single post.
- `MessageCheckService.kt` checks for new messages in the background about every 3 minutes,
  using the same inbox request Instagram's website makes, with your saved session. It only runs
  while you're logged in and the app isn't open. `Inbox.kt` reads the response.
- Cookies are flushed to disk whenever a page finishes loading and when the app goes to the
  background, so the login survives the app being killed.

## Limitations

- **Notifications can be late.** Instagram doesn't offer push notifications to apps like this, so the
  app checks every few minutes. When the phone has been idle with the screen off for a while,
  Android groups background work together, and notifications can come later than that. If they
  stop entirely, exempt InstaChat from battery optimization in the system settings.
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
