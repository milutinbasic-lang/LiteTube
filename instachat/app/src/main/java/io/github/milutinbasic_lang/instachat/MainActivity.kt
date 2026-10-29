package io.github.milutinbasic_lang.instachat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/**
 * The whole app: a WebView showing Instagram's own web inbox (instagram.com/direct), with every
 * other part of Instagram blocked or hidden. You log in on Instagram's real login page; the
 * session cookie is kept in the app's private storage, so you stay logged in across restarts
 * until you log out (launcher long-press → Log out) or Instagram ends the session.
 * While the app is in the background, [MessageCheckService] shows notifications for new messages.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    /** The last page that was allowed; a post or reel can't lead on to another one. */
    private var currentUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        val root = FrameLayout(this).apply { addView(webView) }
        setContentView(root)
        applyWindowInsets(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = cleanUserAgent(userAgentString)
        }
        webView.webViewClient = ChatOnlyClient()
        webView.webChromeClient = ChromeClient()

        when {
            savedInstanceState != null -> webView.restoreState(savedInstanceState)
            intent?.action == ACTION_LOGOUT -> logOut()
            else -> openChatFrom(intent)
        }
        if (webView.url == null) webView.loadUrl(UrlRules.INBOX_URL)
        askForNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_LOGOUT) {
            logOut()
            webView.loadUrl(UrlRules.INBOX_URL)
        } else {
            openChatFrom(intent)
        }
    }

    /** Tapping a message notification opens that chat. */
    private fun openChatFrom(intent: Intent?) {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (UrlRules.decide(url) == UrlRules.Action.ALLOW) webView.loadUrl(url)
    }

    // Android 13+ needs permission to show notifications. Asked once; can be changed later in
    // the system settings.
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /** Forgets the session: cookies, page storage and cache. The login page shows next. */
    private fun logOut() {
        CookieManager.getInstance().removeSessionCookies(null)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        currentUrl = null
        MessageCheckService.cancel(this)
        Toast.makeText(this, R.string.logged_out, Toast.LENGTH_SHORT).show()
    }

    // Android 15 draws apps edge-to-edge; keep the page clear of the status bar, navigation bar
    // and keyboard. Older versions resize for the keyboard by themselves (adjustResize).
    private fun applyWindowInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        // The inbox shows what's new; notifications for it are no longer needed.
        getSystemService(NotificationManager::class.java).cancelAll()
    }

    override fun onStop() {
        isVisible = false
        MessageCheckService.start(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        // Write the session cookie to disk now, in case the app is killed in the background.
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // First let the page close an open video viewer, which has no history entry of its own.
        webView.evaluateJavascript("!!(window.__chatOnlyBack && window.__chatOnlyBack())") { closed ->
            if (closed == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    private fun openExternally(url: String) {
        val intent = if (url.startsWith("intent:")) {
            try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } catch (_: Exception) {
                return
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBlocked() {
        Toast.makeText(this, R.string.blocked, Toast.LENGTH_SHORT).show()
    }

    private inner class ChatOnlyClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return when (UrlRules.decide(url, currentUrl)) {
                UrlRules.Action.ALLOW -> false
                UrlRules.Action.GO_TO_INBOX -> {
                    view.loadUrl(UrlRules.INBOX_URL)
                    true
                }
                UrlRules.Action.BLOCK -> {
                    showBlocked()
                    true
                }
                UrlRules.Action.EXTERNAL -> {
                    openExternally(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Catches redirects and direct loads that shouldOverrideUrlLoading doesn't see.
            guard(view, url)
        }

        // Instagram switches pages without reloading (history.pushState). This is the only
        // callback that sees those, so the URL is checked again here.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            guard(view, url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            view.evaluateJavascript(ChatOnlyScript.JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(ChatOnlyScript.JS, null)
            CookieManager.getInstance().flush()
        }

        private fun guard(view: WebView, url: String) {
            when (UrlRules.decide(url, currentUrl)) {
                UrlRules.Action.ALLOW -> currentUrl = url
                UrlRules.Action.GO_TO_INBOX -> view.post { view.loadUrl(UrlRules.INBOX_URL) }
                UrlRules.Action.BLOCK -> {
                    showBlocked()
                    view.post {
                        view.stopLoading()
                        if (view.canGoBack()) view.goBack() else view.loadUrl(UrlRules.INBOX_URL)
                    }
                }
                UrlRules.Action.EXTERNAL -> {
                    view.post {
                        view.stopLoading()
                        if (view.canGoBack()) view.goBack() else view.loadUrl(UrlRules.INBOX_URL)
                    }
                    openExternally(url)
                }
            }
        }
    }

    private inner class ChromeClient : WebChromeClient() {

        // Attaching photos and videos to messages.
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQUEST_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        // Voice messages need the microphone (and the camera, if Instagram asks for it).
        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    else -> null
                }
            }
            if (needed.isEmpty()) {
                request.deny()
                return
            }
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                grantAllowed(request)
            } else {
                pendingPermission?.deny()
                pendingPermission = request
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSION)
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission == request) pendingPermission = null
        }
    }

    private fun grantAllowed(request: PermissionRequest) {
        val granted = request.resources.filter {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (requestCode != REQUEST_PERMISSION) return
        pendingPermission?.let { grantAllowed(it) }
        pendingPermission = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        const val ACTION_LOGOUT = "io.github.milutinbasic_lang.instachat.LOGOUT"
        private const val REQUEST_FILE = 1
        private const val REQUEST_PERMISSION = 2
        private const val REQUEST_NOTIFICATIONS = 3

        /** Whether the app is on screen; no notifications are needed then. */
        @Volatile
        var isVisible = false

        // Instagram nags WebViews to "open the app"; look like regular mobile Chrome instead.
        fun cleanUserAgent(userAgent: String) = userAgent
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    }
}
