package app.litetube.ui

import android.content.Context
import android.text.format.DateUtils
import app.litetube.R
import app.litetube.data.VideoItem
import java.util.Locale

object Format {

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun compact(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> oneDecimal(n / 1_000.0) + "K"
        n < 1_000_000_000 -> oneDecimal(n / 1_000_000.0) + "M"
        else -> oneDecimal(n / 1_000_000_000.0) + "B"
    }

    private fun oneDecimal(v: Double): String =
        if (v >= 100 || v % 1.0 < 0.05) v.toLong().toString()
        else String.format(Locale.getDefault(), "%.1f", v)

    fun views(context: Context, n: Long): String? =
        if (n < 0) null else context.getString(R.string.views, compact(n))

    fun subscribers(context: Context, n: Long): String? =
        if (n < 0) null else context.getString(R.string.subscribers, compact(n))

    fun age(millis: Long, fallback: String?): String? =
        if (millis > 0) {
            DateUtils.getRelativeTimeSpanString(
                millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } else {
            fallback
        }

    fun videoMeta(context: Context, v: VideoItem, includeDuration: Boolean): String = listOfNotNull(
        if (includeDuration) duration(v.durationSec).ifEmpty { null } else null,
        v.uploader.ifEmpty { null },
        // The subscription feed reports 0 views for brand-new uploads; don't show that.
        if (v.isLive) context.getString(R.string.live) else views(context, v.views.takeIf { it > 0 } ?: -1),
        if (v.isLive) null else age(v.uploadedMillis, v.uploadedText),
    ).joinToString(" · ")
}
