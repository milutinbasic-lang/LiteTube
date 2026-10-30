package app.litelauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.Collator

/**
 * The whole launcher, laid out like Phosh: a home screen (wallpaper, clock, favorites dock) and,
 * pulled up over it, the app drawer (Phosh's overview: search entry, favorites, grid of every app).
 * Built only on framework widgets (GridView recycles its cells, and each cell is one TextView),
 * with one background thread for the app list and icons.
 */
class LauncherActivity : Activity() {

    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var prefs: SharedPreferences
    private lateinit var icons: IconLoader
    private lateinit var status: StatusMonitor

    private lateinit var root: View
    private lateinit var home: HomeView
    private lateinit var dock: LinearLayout
    private lateinit var drawer: View
    private lateinit var homeBar: HomeBarView
    private lateinit var search: EditText
    private lateinit var favoritesRow: LinearLayout
    private lateinit var favoritesDivider: View
    private lateinit var grid: GridView
    private val adapter = AppAdapter()

    private val workerThread = HandlerThread("launcher").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var allApps: List<App> = emptyList()
    private var favorites: List<String> = emptyList()
    private var appsDirty = true
    private var resumed = false
    private var drawerOpen = false
    private var loadGeneration = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(names: Array<String>, user: UserHandle, replacing: Boolean) =
            names.forEach(::changed)
        override fun onPackagesUnavailable(names: Array<String>, user: UserHandle, replacing: Boolean) =
            names.forEach(::changed)

        private fun changed(packageName: String) {
            icons.evictPackage(packageName)
            if (resumed) reloadApps() else appsDirty = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        userManager = getSystemService(Context.USER_SERVICE) as UserManager
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        icons = IconLoader(this, worker, (52 * resources.displayMetrics.density).toInt())
        icons.themed = prefs.getBoolean(KEY_THEMED_ICONS, true)

        root = findViewById(R.id.root)
        home = findViewById(R.id.home)
        dock = findViewById(R.id.dock)
        drawer = findViewById(R.id.drawer)
        homeBar = findViewById(R.id.home_bar)
        home.onSwipeUp = ::openDrawer
        home.onLongPress = ::showHomeMenu

        val topBar = findViewById<TopBarView>(R.id.top_bar)
        status = StatusMonitor(this, topBar, home)
        topBar.onPull = ::expandStatusBar

        search = findViewById(R.id.search)
        favoritesRow = findViewById(R.id.favorites)
        favoritesDivider = findViewById(R.id.favorites_divider)
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, view, position, _ -> launch(adapter.getItem(position), view) }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            showAppMenu(adapter.getItem(position), view); true
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO && adapter.count > 0) {
                launch(adapter.getItem(0), grid.getChildAt(0) ?: search)
                true
            } else false
        }

        homeBar.setOnClickListener { if (drawerOpen) closeDrawer(true) else openDrawer() }
        grid.setOnTouchListener(PullDownToClose())

        setUpWindow()
        applyWallpaperMode()
        favorites = prefs.getString(KEY_FAVORITES, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        launcherApps.registerCallback(packageCallback, main)
    }

    /** Hide Android's status bar (our top bar replaces it) and lay out around cutouts and the nav bar. */
    @Suppress("DEPRECATION")
    private fun setUpWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
    }

    override fun onStart() {
        super.onStart()
        status.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (appsDirty) reloadApps()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        status.stop()
        // Like Phosh, coming back after opening an app lands on the home screen.
        closeDrawer(false)
        super.onStop()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(packageCallback)
        workerThread.quitSafely()
        super.onDestroy()
    }

    /** Home pressed while already home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer(true)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to: leave search, then the drawer.
        if (search.text.isNotEmpty()) search.text.clear() else closeDrawer(true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) icons.clear()
    }

    // --- Home screen and drawer ---

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        homeBar.pointsDown = true
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = drawer.height * 0.15f
        drawer.animate().alpha(1f).translationY(0f).setDuration(ANIMATION_MS).withLayer()
            // The opaque drawer covers the home screen, so stop drawing it underneath.
            .withEndAction { if (drawerOpen) home.visibility = View.INVISIBLE }
            .start()
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        homeBar.pointsDown = false
        hideKeyboard()
        home.visibility = View.VISIBLE
        drawer.animate().cancel()
        if (animate) {
            drawer.animate().alpha(0f).translationY(drawer.height * 0.15f).setDuration(ANIMATION_MS).withLayer()
                .withEndAction { if (!drawerOpen) hideDrawer() }
                .start()
        } else {
            hideDrawer()
        }
    }

    private fun hideDrawer() {
        drawer.visibility = View.GONE
        if (search.text.isNotEmpty()) search.text.clear()
        grid.setSelection(0)
    }

    /** Swiping down on the app grid while it's scrolled to the top closes the drawer. */
    private inner class PullDownToClose : View.OnTouchListener {
        private var downY = 0f
        private var atTop = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    val first = grid.getChildAt(0)
                    atTop = grid.firstVisiblePosition == 0 && (first == null || first.top >= grid.paddingTop)
                }
                MotionEvent.ACTION_MOVE ->
                    if (atTop && event.y - downY > 96 * resources.displayMetrics.density) {
                        atTop = false
                        closeDrawer(true)
                    }
            }
            return false
        }
    }

    private fun showHomeMenu() {
        val menu = PopupMenu(this, dock)
        menu.menu.add(0, MENU_THEMED_ICONS, 0, R.string.themed_icons).isCheckable = true
        menu.menu.findItem(MENU_THEMED_ICONS).isChecked = icons.themed
        menu.menu.add(0, MENU_SYSTEM_WALLPAPER, 1, R.string.system_wallpaper).isCheckable = true
        menu.menu.findItem(MENU_SYSTEM_WALLPAPER).isChecked = prefs.getBoolean(KEY_SYSTEM_WALLPAPER, false)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_THEMED_ICONS -> {
                    icons.themed = !icons.themed
                    prefs.edit().putBoolean(KEY_THEMED_ICONS, icons.themed).apply()
                    icons.clear()
                    adapter.notifyDataSetChanged()
                    renderFavorites()
                }
                MENU_SYSTEM_WALLPAPER -> {
                    prefs.edit().putBoolean(KEY_SYSTEM_WALLPAPER, !item.isChecked).apply()
                    applyWallpaperMode()
                }
            }
            true
        }
        menu.show()
    }

    /**
     * Our own drawn wallpaper by default; the system one only on request, since showing it makes
     * Android composite a full-screen wallpaper layer under the launcher.
     */
    private fun applyWallpaperMode() {
        val system = prefs.getBoolean(KEY_SYSTEM_WALLPAPER, false)
        home.drawWallpaper = !system
        if (system) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(0))
            root.background = null
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(BLACK))
            root.setBackgroundColor(BLACK)
        }
    }

    private fun hideKeyboard() {
        search.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
    }

    // --- App list ---

    private fun reloadApps() {
        appsDirty = false
        val generation = ++loadGeneration
        val firstRun = !prefs.contains(KEY_FAVORITES)
        worker.post {
            val collator = Collator.getInstance()
            val apps = ArrayList<App>()
            for (user in userManager.userProfiles) {
                val serial = userManager.getSerialNumberForUser(user)
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == packageName) continue
                    apps += App(info.label.toString().trim(), info, user, serial)
                }
            }
            apps.sortWith { a, b -> collator.compare(a.label, b.label) }
            val defaultApps = defaultApps()
            IconTheme.roles = defaultApps
            val me = Process.myUserHandle()
            val defaults = if (firstRun) {
                defaultApps.keys.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && it.user == me }?.key }
            } else null
            main.post {
                if (generation != loadGeneration || isDestroyed) return@post
                allApps = apps
                if (defaults != null) saveFavorites(defaults)
                applyFilter()
                renderFavorites()
            }
        }
    }

    /**
     * The default phone, messages, browser and camera apps, each with its postmarketOS-style
     * icon. They're also the first-run favorites, like Phosh's.
     */
    private fun defaultApps(): Map<String, Int> {
        val roles = listOf(
            Intent(Intent.ACTION_DIAL) to R.drawable.pm_dialer,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to R.drawable.pm_messages,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")) to R.drawable.pm_browser,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE) to R.drawable.pm_camera,
        )
        val result = LinkedHashMap<String, Int>()
        for ((intent, icon) in roles) {
            val pkg = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: continue
            // "android" is the chooser shown when no default is set.
            if (pkg != "android" && pkg !in result) result[pkg] = icon
        }
        return result
    }

    private fun applyFilter() {
        val query = search.text.toString().trim().lowercase()
        adapter.items = if (query.isEmpty()) allApps else {
            val (prefix, rest) = allApps.filter { it.searchLabel.contains(query) }
                .partition { it.searchLabel.startsWith(query) }
            prefix + rest
        }
        val showFavorites = query.isEmpty() && favoritesRow.childCount > 0
        favoritesRow.visibility = if (showFavorites) View.VISIBLE else View.GONE
        favoritesDivider.visibility = favoritesRow.visibility
    }

    /** Favorites appear twice: in the home screen's dock and at the top of the drawer. */
    private fun renderFavorites() {
        val byKey = allApps.associateBy { it.key }
        val apps = favorites.mapNotNull { byKey[it] }.take(MAX_FAVORITES)
        for (row in listOf(dock, favoritesRow)) {
            row.removeAllViews()
            for (app in apps) {
                val cell = LayoutInflater.from(this).inflate(R.layout.item_app, row, false) as TextView
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                cell.text = app.label
                if (row === dock) cell.setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
                icons.bind(cell, app)
                cell.setOnClickListener { launch(app, it) }
                cell.setOnLongClickListener { showAppMenu(app, it); true }
                row.addView(cell)
            }
        }
        applyFilter()
    }

    private fun saveFavorites(keys: List<String>) {
        favorites = keys
        prefs.edit().putString(KEY_FAVORITES, keys.joinToString("\n")).apply()
    }

    // --- Actions ---

    private fun launch(app: App, from: View) {
        val bounds = Rect().also { from.getGlobalVisibleRect(it) }
        val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            launcherApps.startMainActivity(app.info.componentName, app.user, bounds, options)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: App, anchor: View) {
        val isFavorite = app.key in favorites
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_FAVORITE, 0, if (isFavorite) R.string.remove_favorite else R.string.add_favorite)
        menu.menu.add(0, MENU_INFO, 1, R.string.app_info)
        if (app.user == Process.myUserHandle()) menu.menu.add(0, MENU_UNINSTALL, 2, R.string.uninstall)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FAVORITE -> {
                    saveFavorites(if (isFavorite) favorites - app.key else favorites + app.key)
                    renderFavorites()
                }
                MENU_INFO -> try {
                    val bounds = Rect().also { anchor.getGlobalVisibleRect(it) }
                    launcherApps.startAppDetailsActivity(app.info.componentName, app.user, bounds, null)
                } catch (e: Exception) {
                }
                MENU_UNINSTALL -> try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
                } catch (e: Exception) {
                }
            }
            true
        }
        menu.show()
    }

    /** Opens Android's notification shade or quick settings from our top bar. */
    @SuppressLint("WrongConstant")
    private fun expandStatusBar(quickSettings: Boolean) {
        try {
            val service = getSystemService("statusbar") ?: return
            val method = if (quickSettings) "expandSettingsPanel" else "expandNotificationsPanel"
            service.javaClass.getMethod(method).invoke(service)
        } catch (e: Exception) {
            // Not available on this ROM; nothing else we can do without system privileges.
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        var items: List<App> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as TextView?
                ?: layoutInflater.inflate(R.layout.item_app, parent, false) as TextView
            val app = items[position]
            view.text = app.label
            icons.bind(view, app)
            return view
        }
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val MAX_FAVORITES = 5
        const val MENU_FAVORITE = 1
        const val MENU_INFO = 2
        const val MENU_UNINSTALL = 3
        const val MENU_THEMED_ICONS = 4
        const val MENU_SYSTEM_WALLPAPER = 5
        const val KEY_THEMED_ICONS = "themed_icons"
        const val KEY_SYSTEM_WALLPAPER = "system_wallpaper"
        const val ANIMATION_MS = 200L
        const val BLACK = 0xff000000.toInt()
    }
}
