package app.litetube.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    // Same generic desktop browser UA that NewPipe uses, so requests don't stand out.
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // No CookieJar: nothing is persisted between requests or sessions.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
