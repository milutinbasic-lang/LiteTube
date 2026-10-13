package app.litetube

import android.app.Application
import app.litetube.data.FeedCache
import app.litetube.data.Prefs
import app.litetube.data.Subscriptions
import app.litetube.net.Http
import app.litetube.net.OkHttpDownloader
import app.litetube.player.PlayerController
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.init(this)
        Prefs.applyNightMode()
        Subscriptions.init(this)
        FeedCache.init(this)
        PlayerController.init(this)

        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(Http.client),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }

    // Small caches and RGB_565 bitmaps keep memory use low on budget phones.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnails"))
                .maxSizeBytes(40L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
