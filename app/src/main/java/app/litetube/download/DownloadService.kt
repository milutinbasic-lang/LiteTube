package app.litetube.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.litetube.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One queued download. Video downloads with separate audio are muxed into a single MP4. */
data class DownloadJob(
    val title: String,
    val kind: SaveTarget.Kind,
    val videoUrl: String?,
    val audioUrl: String?,
    val label: String,
)

/**
 * Runs downloads one at a time in the background with a progress notification,
 * so the user can keep watching or leave the app.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        private const val PROGRESS_ID = 2001

        fun enqueue(context: Context, job: DownloadJob) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra("title", job.title)
                .putExtra("kind", job.kind.name)
                .putExtra("video", job.videoUrl)
                .putExtra("audio", job.audioUrl)
                .putExtra("label", job.label)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<DownloadJob>()
    private var running = false
    @Volatile private var stopped = false
    private var lastPercent = -1
    private var nextResultId = 3000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.downloads))
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, progressNotification(getString(R.string.download_starting), -1), type)
        intent?.let {
            queue.addLast(
                DownloadJob(
                    title = it.getStringExtra("title").orEmpty(),
                    kind = SaveTarget.Kind.valueOf(it.getStringExtra("kind") ?: "VIDEO"),
                    videoUrl = it.getStringExtra("video"),
                    audioUrl = it.getStringExtra("audio"),
                    label = it.getStringExtra("label").orEmpty(),
                )
            )
        }
        if (!running) runNext()
        return START_NOT_STICKY
    }

    private fun runNext() {
        val job = queue.removeFirstOrNull()
        if (job == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = true
        lastPercent = -1
        scope.launch {
            try {
                val location = withContext(Dispatchers.IO) { process(job) }
                notifyResult(job, getString(R.string.download_done, location), success = true)
            } catch (e: Throwable) {
                notifyResult(job, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName), success = false)
            } finally {
                running = false
                runNext()
            }
        }
    }

    private fun process(job: DownloadJob): String {
        val work = File(cacheDir, "downloads").apply { mkdirs() }
        val parts = listOfNotNull(job.videoUrl, job.audioUrl)
        val files = parts.mapIndexed { i, _ -> File(work, "part$i") }
        try {
            parts.forEachIndexed { i, url ->
                ChunkDownloader.download(url, files[i], { stopped }) { done, total ->
                    // Overall progress across all parts.
                    val share = if (total > 0) done.toDouble() / total else 0.0
                    val percent = ((i + share) * 100 / parts.size).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyProgress(job, percent)
                    }
                }
            }
            val (ext, mime) = if (job.kind == SaveTarget.Kind.AUDIO) "m4a" to "audio/mp4" else "mp4" to "video/mp4"
            val target = SaveTarget.create(this, job.kind, "${job.title}.$ext", mime)
            try {
                Muxer.mux(files, target)
                target.publish()
            } catch (e: Throwable) {
                target.discard()
                throw e
            }
            return target.location
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun progressNotification(text: String, percent: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyProgress(job: DownloadJob, percent: Int) {
        val text = "${job.title} · ${job.label} · $percent%"
        scope.launch { post(PROGRESS_ID, progressNotification(text, percent)) }
    }

    private fun notifyResult(job: DownloadJob, text: String, success: Boolean) {
        val open = if (success) {
            PendingIntent.getActivity(
                this, 0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
            )
        } else {
            null
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(job.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(nextResultId++, notification)
    }

    private fun post(id: Int, notification: android.app.Notification) {
        // Without the notification permission (Android 13+) downloads still work, just silently.
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        super.onDestroy()
    }
}
