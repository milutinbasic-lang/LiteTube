package app.litetube.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the last feed on disk so the app opens instantly, even offline. */
object FeedCache {
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "feed.json")
    }

    data class Snapshot(val savedAt: Long, val items: List<VideoItem>)

    fun load(): Snapshot = runCatching {
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("items")
        Snapshot(o.getLong("savedAt"), (0 until arr.length()).map { VideoItem.fromJson(arr.getJSONObject(it)) })
    }.getOrElse { Snapshot(0, emptyList()) }

    fun save(items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("savedAt", System.currentTimeMillis()).put("items", arr).toString())
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}
