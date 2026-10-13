package app.litetube

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Keeps the last crash on the device (never sent anywhere) so the next launch can show it
 * and the user can choose to share it.
 */
object CrashLog {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(describe(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(error: Throwable): String =
        "LiteTube ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n\n" + Log.getStackTraceString(error)

    /** Returns the saved crash report once, then forgets it. */
    fun take(): String? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
