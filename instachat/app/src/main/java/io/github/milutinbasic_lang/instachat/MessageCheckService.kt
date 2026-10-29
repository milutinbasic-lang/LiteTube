package io.github.milutinbasic_lang.instachat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Message notifications. Instagram's web push doesn't work inside an app, so while the app is in
 * the background this checks the inbox every few minutes, the same request Instagram's website
 * makes, using the session you logged in with. A notification is shown for each chat whose newest
 * message is from someone else and hasn't been seen yet (in this app or anywhere else).
 *
 * Android decides exactly when background jobs run: with the screen off for a long time
 * (Doze), checks are batched and can be delayed.
 */
class MessageCheckService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        if (MainActivity.isVisible) return false // You're looking at the inbox already.
        val cookies = CookieManager.getInstance().getCookie(INSTAGRAM)
        if (cookies == null || !cookies.contains("sessionid=")) {
            cancel(this) // Logged out: nothing to check until the next login.
            return false
        }
        val userAgent = MainActivity.cleanUserAgent(WebSettings.getDefaultUserAgent(this))
        thread(name = "message-check") {
            try {
                check(cookies, userAgent)
            } catch (_: Exception) {
                // Offline, timeout, Instagram changed something... try again next time.
            }
            scheduleSoon(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    private fun check(cookies: String, userAgent: String) {
        val json = fetchInbox(cookies, userAgent) ?: return
        val latest = Inbox.parse(json, cookie(cookies, "ds_user_id")) ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // The first check only takes note of what's there, so you don't get old messages.
        val firstCheck = !prefs.getBoolean(KEY_STARTED, false)
        val nm = getSystemService(NotificationManager::class.java)
        val edit = prefs.edit()
        for (chat in latest) {
            val key = "last_" + chat.threadId
            if (chat.handled) {
                nm.cancel(chat.threadId, NOTIFICATION_ID)
            } else if (prefs.getString(key, null) != chat.itemId && !firstCheck) {
                notify(nm, chat)
            }
            edit.putString(key, chat.itemId)
        }
        edit.putBoolean(KEY_STARTED, true).apply()
    }

    private fun fetchInbox(cookies: String, userAgent: String): String? {
        val conn = URL(INBOX_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Referer", UrlRules.INBOX_URL)
            conn.setRequestProperty("X-IG-App-ID", "936619743392459")
            conn.setRequestProperty("X-ASBD-ID", "129477")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            cookie(cookies, "csrftoken")?.let { conn.setRequestProperty("X-CSRFToken", it) }
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun notify(nm: NotificationManager, chat: Inbox.Latest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val url = "https://www.instagram.com/direct/t/${chat.threadId}/"
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(url))
        val pending = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = chat.groupTitle ?: chat.senderName
        val text = if (chat.groupTitle != null) "${chat.senderName}: ${chat.text}" else chat.text
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.icon_background))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(chat.threadId, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission not granted (Android 13+).
        }
    }

    companion object {
        private const val INSTAGRAM = "https://www.instagram.com"
        private const val INBOX_API =
            "https://www.instagram.com/api/v1/direct_v2/inbox/?persistentBadging=true&folder=&limit=20&thread_message_limit=1"
        private const val CHANNEL = "messages"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "notifications"
        private const val KEY_STARTED = "started"
        private const val JOB_PERIODIC = 1
        private const val JOB_SOON = 2

        /** How often to check while the app is in the background (Android may stretch this). */
        private const val CHECK_EVERY_MS = 3 * 60_000L

        private fun cookie(cookies: String, name: String): String? =
            cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

        /** Starts checking: a check in a few minutes, plus a 15-minute backup that survives reboots. */
        fun start(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, MessageCheckService::class.java)
            if (scheduler.getPendingJob(JOB_PERIODIC) == null) {
                scheduler.schedule(
                    JobInfo.Builder(JOB_PERIODIC, service)
                        .setPeriodic(15 * 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build()
                )
            }
            scheduleSoon(context)
        }

        private fun scheduleSoon(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_SOON, ComponentName(context, MessageCheckService::class.java))
                    .setMinimumLatency(CHECK_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Stops checking and forgets what was seen (on logout). */
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancelAll()
            context.getSystemService(NotificationManager::class.java).cancelAll()
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        }
    }
}
