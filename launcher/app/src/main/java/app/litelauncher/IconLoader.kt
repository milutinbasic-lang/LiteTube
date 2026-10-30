package app.litelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Loads app icons off the main thread and keeps them in a small LRU cache.
 *
 * Icons are rendered once into a bitmap of exactly the on-screen size, so the cache never holds
 * the much larger source images (or adaptive-icon layers) apps ship with. Under memory pressure
 * the cache is simply dropped; icons are re-rendered as they scroll back into view.
 */
class IconLoader(private val context: Context, private val worker: Handler, private val sizePx: Int) {

    /** Use the postmarketOS-style icons from [IconTheme] where there is one. */
    @Volatile
    var themed = true

    private val resources = context.resources
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.densityDpi
    private val empty = ColorDrawable(0)

    // At most 1/10 of the heap and never more than 12 MB: ~120 icons at xxhdpi, ~250 at xhdpi.
    private val cache = object : LruCache<String, Bitmap>(
        minOf(Runtime.getRuntime().maxMemory() / 10, 12L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Which app each recycled view currently shows; read by the worker to skip stale requests. */
    private val wanted = WeakHashMap<TextView, String>()

    fun bind(view: TextView, app: App) {
        val cached = cache.get(app.key)
        if (cached != null) {
            synchronized(wanted) { wanted.remove(view) }
            show(view, cached)
            return
        }
        show(view, null)
        synchronized(wanted) { wanted[view] = app.key }
        worker.post {
            if (synchronized(wanted) { wanted[view] } != app.key) return@post
            val bitmap = cache.get(app.key) ?: render(app)?.also { cache.put(app.key, it) } ?: return@post
            main.post {
                if (synchronized(wanted) { wanted[view] } == app.key) {
                    synchronized(wanted) { wanted.remove(view) }
                    show(view, bitmap)
                }
            }
        }
    }

    private fun render(app: App): Bitmap? = try {
        val themedIcon = if (themed) IconTheme.iconFor(app) else 0
        val drawable = if (themedIcon != 0) {
            context.packageManager.getUserBadgedIcon(resources.getDrawable(themedIcon, null), app.user)
        } else {
            app.info.getBadgedIcon(density)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap
    } catch (e: Exception) {
        null // App was uninstalled meanwhile, or ships a broken icon.
    }

    private fun show(view: TextView, bitmap: Bitmap?) {
        // A null bitmap still reserves the icon's space, so labels don't jump when it arrives.
        val d = if (bitmap != null) BitmapDrawable(resources, bitmap) else empty
        d.setBounds(0, 0, sizePx, sizePx)
        view.setCompoundDrawables(null, d, null, null)
    }

    fun evictPackage(packageName: String) {
        val prefix = "$packageName/"
        for (key in cache.snapshot().keys) if (key.startsWith(prefix)) cache.remove(key)
    }

    fun clear() = cache.evictAll()
}
