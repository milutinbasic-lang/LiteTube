package app.litelauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Puts LiteLauncher's top bar over Android's status bar in every app, as Phosh's panel is on
 * postmarketOS.
 *
 * An accessibility service is the only way a normal app can draw over the status bar. This one
 * reads nothing on screen and gets no accessibility events worth mentioning: it only owns the
 * overlay window and, when you pull the bar down, asks Android to open notifications or quick
 * settings. The bar steps aside while an app is fullscreen (videos, camera), and its receivers
 * are off while the screen is.
 */
class TopBarService : AccessibilityService() {

    private var bar: TopBarView? = null
    private var monitor: StatusMonitor? = null
    private var monitoring = false
    private var statusBarShown = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) startMonitor() else stopMonitor()
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
        monitor = StatusMonitor(this, view, null)
        startMonitor()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        instance = this
        onStateChanged?.invoke()
    }

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Never connected.
        }
        stopMonitor()
        bar?.let { windowManager().removeView(it) }
        bar = null
        super.onDestroy()
        onStateChanged?.invoke()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The status bar's height differs between portrait and landscape.
        bar?.let { windowManager().updateViewLayout(it, layoutParams()) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TEMP diagnostics
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = windowManager().currentWindowMetrics.windowInsets
            android.util.Log.d("LLTopBar", "event ${event?.eventType} pkg=${event?.packageName} metricsVisible=" +
                "${insets.isVisible(WindowInsets.Type.statusBars())} " +
                "top=${insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top}")
        }
    }

    override fun onInterrupt() {}

    /** Turns the service off again (from the launcher's menu). */
    fun turnOff() {
        if (Build.VERSION.SDK_INT >= 24) disableSelf()
    }

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
        if (!statusBarShown) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        gravity = Gravity.TOP
        title = "LiteLauncher top bar"
        if (Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    /** Hide while Android hides its status bar, i.e. while an app is fullscreen. */
    @Suppress("DEPRECATION")
    private fun trackStatusBarVisibility(view: TopBarView) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                android.util.Log.d("LLTopBar", "insets visible=${insets.isVisible(WindowInsets.Type.statusBars())} " +
                    "ignoring=${insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())}")
                setStatusBarShown(true)
                (v as TopBarView).cutouts = insets.displayCutout?.boundingRects ?: emptyList()
                insets
            }
        } else {
            view.setOnSystemUiVisibilityChangeListener { flags ->
                setStatusBarShown(flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                view.setOnApplyWindowInsetsListener { v, insets ->
                    (v as TopBarView).cutouts = insets.displayCutout?.boundingRects ?: emptyList()
                    insets
                }
            }
        }
    }

    private fun setStatusBarShown(shown: Boolean) {
        if (shown == statusBarShown) return
        statusBarShown = shown
        // The window itself stays (hiding it would also stop the insets that tell us when to come
        // back); it just turns invisible and lets touches through to the fullscreen app.
        bar?.let {
            it.alpha = if (shown) 1f else 0f
            windowManager().updateViewLayout(it, layoutParams())
        }
        if (shown) startMonitor() else stopMonitor()
    }

    companion object {
        /** The running service, if the user has turned it on. It lives in the launcher's process. */
        var instance: TopBarService? = null
            private set

        /** Called on the main thread when the service starts or stops. */
        var onStateChanged: (() -> Unit)? = null
    }
}
