package app.litelauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Puts LiteLauncher's top bar over Android's status bar in every app, as Phosh's panel is on
 * postmarketOS.
 *
 * It lives in its own small add-on app: Google Play Protect blocks installing any downloaded app
 * that contains an accessibility service, and the launcher itself shouldn't be blocked for an
 * optional feature.
 *
 * An accessibility service is the only way a normal app can draw over the status bar. This one
 * reads nothing on screen: it owns the overlay window, notices when the foreground window changes
 * (to step aside while an app is fullscreen, like videos or the camera) and, when you pull the
 * bar down, asks Android to open notifications or quick settings. Its receivers are off while
 * the screen is, or while the bar is hidden.
 */
class TopBarService : AccessibilityService() {

    private var bar: TopBarView? = null
    private var monitor: StatusMonitor? = null
    private var monitoring = false
    private var statusBarShown = true
    private val handler = Handler(Looper.getMainLooper())
    private val statusBarCheck = Runnable { checkStatusBar() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (statusBarShown) startMonitor()
                scheduleStatusBarCheck()
            } else {
                stopMonitor()
            }
        }
    }

    override fun onServiceConnected() {
        val view = TopBarView(this, null)
        view.onPull = { quickSettings ->
            performGlobalAction(
                if (quickSettings && Build.VERSION.SDK_INT >= 17) GLOBAL_ACTION_QUICK_SETTINGS
                else GLOBAL_ACTION_NOTIFICATIONS
            )
        }
        trackStatusBarVisibility(view)
        windowManager().addView(view, layoutParams())
        bar = view
        monitor = StatusMonitor(this, view)
        startMonitor()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Never connected.
        }
        handler.removeCallbacks(statusBarCheck)
        stopMonitor()
        bar?.let { windowManager().removeView(it) }
        bar = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The status bar's height differs between portrait and landscape.
        bar?.let { windowManager().updateViewLayout(it, layoutParams()) }
        scheduleStatusBarCheck()
    }

    /** Apps switch, open dialogs or go fullscreen here; that's when the status bar may change. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scheduleStatusBarCheck()
    }

    override fun onInterrupt() {}

    private fun startMonitor() {
        if (!monitoring) { monitor?.start(withBar = true); monitoring = true }
    }

    private fun stopMonitor() {
        if (monitoring) { monitor?.stop(); monitoring = false }
    }

    private fun windowManager() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        statusBarHeight(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (!statusBarShown) {
            // Window alpha (applied by the compositor): a root view's own alpha isn't honoured.
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "LiteLauncher top bar"
        if (Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * The real status bar's height. On phones with a camera cutout it's taller than the
     * status_bar_height resource, so on Android 11+ ask the window manager instead.
     */
    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = windowManager().currentWindowMetrics.windowInsets
            val top = maxOf(
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top,
                insets.getInsets(WindowInsets.Type.displayCutout()).top,
            )
            if (top > 0) return top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fromResource = if (id != 0) resources.getDimensionPixelSize(id) else 0
        return maxOf(fromResource, (24 * resources.displayMetrics.density).toInt())
    }

    /**
     * Hide while Android hides its status bar, i.e. while an app is fullscreen.
     *
     * Android 11+ doesn't tell windows layered above the status bar (like this overlay) about it,
     * so we ask what an ordinary app window would see, each time the foreground window changes.
     * Apps often go fullscreen a moment (up to a few seconds) after their window appears, hence
     * the re-checks.
     */
    private fun scheduleStatusBarCheck() {
        if (Build.VERSION.SDK_INT < 30) return
        handler.removeCallbacks(statusBarCheck)
        handler.post(statusBarCheck)
        handler.postDelayed(statusBarCheck, 400)
        handler.postDelayed(statusBarCheck, 1500)
        handler.postDelayed(statusBarCheck, 3000)
    }

    private fun checkStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            setStatusBarShown(windowManager().currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars()))
        }
    }

    @Suppress("DEPRECATION")
    private fun trackStatusBarVisibility(view: TopBarView) {
        if (Build.VERSION.SDK_INT < 30) {
            // Before Android 11, every window hears about the status bar being hidden.
            view.setOnSystemUiVisibilityChangeListener { flags ->
                setStatusBarShown(flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                (v as TopBarView).cutouts = insets.displayCutout?.boundingRects ?: emptyList()
                insets
            }
        }
    }

    private fun setStatusBarShown(shown: Boolean) {
        if (shown == statusBarShown) return
        statusBarShown = shown
        // The window itself stays (hiding it would also stop the insets that tell us when to come
        // back); it just turns invisible and lets touches through to the fullscreen app.
        bar?.let { windowManager().updateViewLayout(it, layoutParams()) }
        if (shown) startMonitor() else stopMonitor()
    }
}
