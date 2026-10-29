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
import android.view.ViewGroup
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
 * The whole launcher: Phosh's overview screen, with a search entry, a row of favorites and a grid
 * of every app. Built only on framework widgets (GridView recycles its cells, and each cell is one
 * TextView), with one background thread for the app list and icons.
 */
class LauncherActivity : Activity() {

    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var prefs: SharedPreferences
    private lateinit var icons: IconLoader
    private lateinit var status: StatusMonitor

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
        icons = IconLoader(resources, worker, (52 * resources.displayMetrics.density).toInt())

        val topBar = findViewById<TopBarView>(R.id.top_bar)
        status = StatusMonitor(this, topBar)
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

        findViewById<View>(R.id.home_bar).setOnClickListener {
            if (search.text.isNotEmpty() || grid.firstVisiblePosition > 0) reset() else focusSearch()
        }

        setUpWindow()
        favorites = prefs.getString(KEY_FAVORITES, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        launcherApps.registerCallback(packageCallback, main)
    }

    /** Hide Android's status bar (our top bar replaces it) and lay out around cutouts and the nav bar. */
    @Suppress("DEPRECATION")
    private fun setUpWindow() {
        val root = findViewById<View>(R.id.root)
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
        // Phosh forgets the search once you've left the overview.
        if (search.text.isNotEmpty()) search.text.clear()
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
        reset()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to; just leave search.
        reset()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) icons.clear()
    }

    private fun reset() {
        search.text.clear()
        hideKeyboard()
        grid.smoothScrollToPosition(0)
    }

    private fun focusSearch() {
        search.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
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
            val defaults = if (firstRun) defaultFavorites(apps) else null
            main.post {
                if (generation != loadGeneration || isDestroyed) return@post
                allApps = apps
                if (defaults != null) saveFavorites(defaults)
                applyFilter()
                renderFavorites()
            }
        }
    }

    /** First run: the default phone, messages, browser and camera apps, as Phosh's favorites. */
    private fun defaultFavorites(apps: List<App>): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        val me = Process.myUserHandle()
        return intents.mapNotNull { intent ->
            val pkg = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: return@mapNotNull null
            apps.firstOrNull { it.packageName == pkg && it.user == me }?.key
        }.distinct()
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

    private fun renderFavorites() {
        favoritesRow.removeAllViews()
        val byKey = allApps.associateBy { it.key }
        for (app in favorites.mapNotNull { byKey[it] }.take(MAX_FAVORITES)) {
            val cell = LayoutInflater.from(this).inflate(R.layout.item_app, favoritesRow, false) as TextView
            cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            cell.text = app.label
            icons.bind(cell, app)
            cell.setOnClickListener { launch(app, it) }
            cell.setOnLongClickListener { showAppMenu(app, it); true }
            favoritesRow.addView(cell)
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
    }
}
