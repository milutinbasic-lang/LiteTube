package app.litetube.data

import org.json.JSONObject

sealed interface ListItem

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val views: Long,
    val uploadedText: String?,
    val uploadedMillis: Long,
    val isLive: Boolean,
) : ListItem {

    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("title", title)
        .put("uploader", uploader)
        .put("uploaderUrl", uploaderUrl)
        .put("thumbnail", thumbnail)
        .put("duration", durationSec)
        .put("views", views)
        .put("uploadedText", uploadedText)
        .put("uploaded", uploadedMillis)
        .put("live", isLive)

    companion object {
        fun fromJson(o: JSONObject) = VideoItem(
            url = o.getString("url"),
            title = o.optString("title"),
            uploader = o.optString("uploader"),
            uploaderUrl = o.optStringOrNull("uploaderUrl"),
            thumbnail = o.optStringOrNull("thumbnail"),
            durationSec = o.optLong("duration", -1),
            views = o.optLong("views", -1),
            uploadedText = o.optStringOrNull("uploadedText"),
            uploadedMillis = o.optLong("uploaded", 0),
            isLive = o.optBoolean("live"),
        )
    }
}

data class ChannelItem(
    val url: String,
    val name: String,
    val avatar: String?,
    val subscribers: Long,
) : ListItem

data class Comment(
    val author: String,
    val textHtml: String?,
    val textPlain: String?,
    val likes: Int,
    val date: String?,
    val replyCount: Int,
    val repliesPage: org.schabi.newpipe.extractor.Page?,
    val pinned: Boolean,
    val hearted: Boolean,
    val isReply: Boolean,
)

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
